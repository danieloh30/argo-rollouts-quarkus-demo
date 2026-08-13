package dev.danieloh.demo;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import io.quarkus.logging.Log;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Path("/api/terminal")
public class TerminalResource {

    @Inject
    KubernetesClient kubernetesClient;

    @ConfigProperty(name = "rollout.name", defaultValue = "quarkus-demo")
    String rolloutName;

    @ConfigProperty(name = "rollout.namespace", defaultValue = "quarkus-demo")
    String rolloutNamespace;

    @ConfigProperty(name = "agent.namespace", defaultValue = "openshift-gitops")
    String agentNamespace;

    @ConfigProperty(name = "agent.deployment", defaultValue = "kubernetes-agent")
    String agentDeployment;

    private static final ResourceDefinitionContext ROLLOUT_CONTEXT = new ResourceDefinitionContext.Builder()
            .withGroup("argoproj.io")
            .withVersion("v1alpha1")
            .withKind("Rollout")
            .withPlural("rollouts")
            .withNamespaced(true)
            .build();

    private static final ResourceDefinitionContext ANALYSIS_RUN_CONTEXT = new ResourceDefinitionContext.Builder()
            .withGroup("argoproj.io")
            .withVersion("v1alpha1")
            .withKind("AnalysisRun")
            .withPlural("analysisruns")
            .withNamespaced(true)
            .build();

    private static final ResourceDefinitionContext REPLICASET_CONTEXT = new ResourceDefinitionContext.Builder()
            .withGroup("apps")
            .withVersion("v1")
            .withKind("ReplicaSet")
            .withPlural("replicasets")
            .withNamespaced(true)
            .build();

    @GET
    @Path("/rollout")
    @Produces(MediaType.APPLICATION_JSON)
    public TerminalOutput getRolloutTerminal() {
        try {
            GenericKubernetesResource rollout = kubernetesClient
                    .genericKubernetesResources(ROLLOUT_CONTEXT)
                    .inNamespace(rolloutNamespace)
                    .withName(rolloutName)
                    .get();

            if (rollout == null) {
                return new TerminalOutput(false, "Rollout '" + rolloutName + "' not found in namespace '" + rolloutNamespace + "'");
            }

            String output = formatRolloutOutput(rollout);
            return new TerminalOutput(true, output);

        } catch (Exception e) {
            Log.debug("Cannot fetch rollout terminal output: " + e.getMessage());
            return new TerminalOutput(false, null);
        }
    }

    @GET
    @Path("/agent-logs")
    @Produces(MediaType.APPLICATION_JSON)
    public TerminalOutput getAgentLogs() {
        // Try Kubernetes client first
        try {
            List<Pod> pods = kubernetesClient.pods()
                    .inNamespace(agentNamespace)
                    .withLabel("app", agentDeployment)
                    .list()
                    .getItems();

            if (pods.isEmpty()) {
                pods = kubernetesClient.pods()
                        .inNamespace(agentNamespace)
                        .withLabel("app.kubernetes.io/name", agentDeployment)
                        .list()
                        .getItems();
            }

            if (!pods.isEmpty()) {
                Pod pod = pods.get(0);
                String logs = kubernetesClient.pods()
                        .inNamespace(agentNamespace)
                        .withName(pod.getMetadata().getName())
                        .tailingLines(200)
                        .getLog();

                if (logs != null && !logs.isEmpty()) {
                    return new TerminalOutput(true, logs);
                }
            }
        } catch (Exception e) {
            Log.debug("Kubernetes client cannot fetch agent logs: " + e.getMessage());
        }

        // Fallback: use oc/kubectl CLI
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "oc", "logs", "deployment/" + agentDeployment,
                    "-n", agentNamespace, "--tail=200");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String logs;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                logs = reader.lines().collect(Collectors.joining("\n"));
            }

            int exitCode = process.waitFor();
            if (exitCode == 0 && !logs.isEmpty()) {
                return new TerminalOutput(true, logs);
            }
        } catch (Exception e) {
            Log.debug("CLI fallback cannot fetch agent logs: " + e.getMessage());
        }

        return new TerminalOutput(false, null);
    }

    @SuppressWarnings("unchecked")
    private String formatRolloutOutput(GenericKubernetesResource rollout) {
        Map<String, Object> status = (Map<String, Object>) rollout.getAdditionalProperties().getOrDefault("status", Map.of());
        Map<String, Object> spec = (Map<String, Object>) rollout.getAdditionalProperties().getOrDefault("spec", Map.of());

        String phase = status.getOrDefault("phase", "Unknown").toString();
        String message = status.containsKey("message") ? status.get("message").toString() : "";

        int canaryWeight = 0;
        int currentStep = 0;
        int totalSteps = 0;

        Object currentStepIndex = status.get("currentStepIndex");
        if (currentStepIndex instanceof Number) {
            currentStep = ((Number) currentStepIndex).intValue();
        }

        Map<String, Object> strategy = (Map<String, Object>) spec.getOrDefault("strategy", Map.of());
        Map<String, Object> canary = (Map<String, Object>) strategy.getOrDefault("canary", Map.of());
        List<Object> steps = (List<Object>) canary.getOrDefault("steps", List.of());
        totalSteps = steps.size();

        Object canaryStatus = status.get("canary");
        if (canaryStatus instanceof Map) {
            Map<String, Object> canaryMap = (Map<String, Object>) canaryStatus;
            Object weights = canaryMap.get("weights");
            if (weights instanceof Map) {
                Map<String, Object> weightsMap = (Map<String, Object>) weights;
                Object canaryObj = weightsMap.get("canary");
                if (canaryObj instanceof Map) {
                    Object w = ((Map<String, Object>) canaryObj).get("weight");
                    if (w instanceof Number) canaryWeight = ((Number) w).intValue();
                } else if (canaryObj instanceof Number) {
                    canaryWeight = ((Number) canaryObj).intValue();
                }
            }
        }

        String stableRS = status.containsKey("stableRS") ? status.get("stableRS").toString() : "";
        String currentPodHash = status.containsKey("currentPodHash") ? status.get("currentPodHash").toString() : "";

        int replicas = 1;
        Object replicasObj = spec.get("replicas");
        if (replicasObj instanceof Number) replicas = ((Number) replicasObj).intValue();

        Object readyReplicas = status.get("readyReplicas");
        int ready = readyReplicas instanceof Number ? ((Number) readyReplicas).intValue() : replicas;
        Object availableReplicas = status.get("availableReplicas");
        int available = availableReplicas instanceof Number ? ((Number) availableReplicas).intValue() : ready;
        Object updatedReplicas = status.get("updatedReplicas");
        int updated = updatedReplicas instanceof Number ? ((Number) updatedReplicas).intValue() : replicas;

        String phaseIcon;
        switch (phase) {
            case "Healthy": phaseIcon = "✔"; break;
            case "Progressing": phaseIcon = "◑"; break;
            case "Paused": phaseIcon = "॥"; break;
            case "Degraded": phaseIcon = "✖"; break;
            default: phaseIcon = "?"; break;
        }

        String image = "N/A";
        try {
            Map<String, Object> template = (Map<String, Object>) spec.get("template");
            if (template != null) {
                Map<String, Object> templateSpec = (Map<String, Object>) template.get("spec");
                if (templateSpec != null) {
                    List<Map<String, Object>> containers = (List<Map<String, Object>>) templateSpec.get("containers");
                    if (containers != null && !containers.isEmpty()) {
                        image = containers.get(0).getOrDefault("image", "N/A").toString();
                    }
                }
            }
        } catch (Exception ignored) {}

        StringBuilder sb = new StringBuilder();
        sb.append("Name:            ").append(rolloutName).append("\n");
        sb.append("Namespace:       ").append(rolloutNamespace).append("\n");
        sb.append("Status:          ").append(phaseIcon).append(" ").append(phase).append("\n");
        if (!message.isEmpty()) {
            sb.append("Message:         ").append(message).append("\n");
        }
        sb.append("Strategy:        Canary\n");
        sb.append("  Step:          ").append(currentStep).append("/").append(totalSteps).append("\n");
        sb.append("  SetWeight:     ").append(canaryWeight).append("\n");
        sb.append("  ActualWeight:  ").append(canaryWeight).append("\n");
        sb.append("Images:          ").append(image);
        if ("Healthy".equals(phase) && canaryWeight >= 100) {
            sb.append(" (stable)");
        } else if (canaryWeight > 0) {
            sb.append(" (canary)");
        } else {
            sb.append(" (stable)");
        }
        sb.append("\n");
        sb.append("Replicas:\n");
        sb.append("  Desired:       ").append(replicas).append("\n");
        sb.append("  Current:       ").append(ready).append("\n");
        sb.append("  Updated:       ").append(updated).append("\n");
        sb.append("  Ready:         ").append(ready).append("\n");
        sb.append("  Available:     ").append(available).append("\n");

        // Tree view
        sb.append("\n");
        sb.append(phaseIcon).append(" ").append(rolloutName).append("  Rollout  ").append(phaseIcon).append(" ").append(phase).append("\n");

        // Get ReplicaSets and Pods
        try {
            List<Pod> allPods = kubernetesClient.pods()
                    .inNamespace(rolloutNamespace)
                    .withLabel("app", rolloutName)
                    .list()
                    .getItems();

            // Group pods by their rollouts-pod-template-hash (Argo Rollouts label)
            Map<String, List<Pod>> podsByHash = allPods.stream()
                    .filter(p -> p.getMetadata().getLabels() != null && p.getMetadata().getLabels().containsKey("rollouts-pod-template-hash"))
                    .collect(Collectors.groupingBy(p -> p.getMetadata().getLabels().get("rollouts-pod-template-hash")));

            // Get AnalysisRuns
            List<GenericKubernetesResource> analysisRuns = new ArrayList<>();
            try {
                analysisRuns = kubernetesClient
                        .genericKubernetesResources(ANALYSIS_RUN_CONTEXT)
                        .inNamespace(rolloutNamespace)
                        .withLabel("app", rolloutName)
                        .list()
                        .getItems();
                analysisRuns.sort((a, b) -> {
                    String tsA = a.getMetadata().getCreationTimestamp();
                    String tsB = b.getMetadata().getCreationTimestamp();
                    return tsB != null && tsA != null ? tsB.compareTo(tsA) : 0;
                });
            } catch (Exception e) {
                Log.debug("Cannot fetch AnalysisRuns: " + e.getMessage());
            }

            // Show stable ReplicaSet
            if (!stableRS.isEmpty() && podsByHash.containsKey(stableRS)) {
                List<Pod> stablePods = podsByHash.get(stableRS);
                String rsName = rolloutName + "-" + stableRS;
                int runningCount = (int) stablePods.stream().filter(this::isPodRunning).count();
                boolean isCanaryRS = stableRS.equals(currentPodHash) && canaryWeight > 0 && canaryWeight < 100;
                String role = isCanaryRS ? "canary" : "stable";
                sb.append("├──⧉ ").append(rsName).append("  ReplicaSet  ✔ Healthy  ").append(role).append("\n");
                for (int i = 0; i < stablePods.size(); i++) {
                    Pod pod = stablePods.get(i);
                    String podStatus = isPodRunning(pod) ? "✔ Running" : "✖ " + getPodPhase(pod);
                    String prefix = (i == stablePods.size() - 1 && analysisRuns.isEmpty()) ? "│  └──" : "│  ├──";
                    sb.append(prefix).append("□ ").append(pod.getMetadata().getName()).append("  Pod  ").append(podStatus).append("  ready:1/1\n");
                }
            }

            // Show canary ReplicaSet if different from stable
            if (!currentPodHash.isEmpty() && !currentPodHash.equals(stableRS) && podsByHash.containsKey(currentPodHash)) {
                List<Pod> canaryPods = podsByHash.get(currentPodHash);
                String rsName = rolloutName + "-" + currentPodHash;
                sb.append("├──⧉ ").append(rsName).append("  ReplicaSet  ◑ Progressing  canary\n");
                for (int i = 0; i < canaryPods.size(); i++) {
                    Pod pod = canaryPods.get(i);
                    String podStatus = isPodRunning(pod) ? "✔ Running" : "✖ " + getPodPhase(pod);
                    String prefix = i == canaryPods.size() - 1 ? "│  └──" : "│  ├──";
                    sb.append(prefix).append("□ ").append(pod.getMetadata().getName()).append("  Pod  ").append(podStatus).append("  ready:1/1\n");
                }
            }

            // Show AnalysisRuns
            for (int i = 0; i < analysisRuns.size(); i++) {
                GenericKubernetesResource ar = analysisRuns.get(i);
                Map<String, Object> arStatus = (Map<String, Object>) ar.getAdditionalProperties().getOrDefault("status", Map.of());
                String arPhase = arStatus.getOrDefault("phase", "Unknown").toString();
                String arName = ar.getMetadata().getName();

                String arIcon;
                switch (arPhase) {
                    case "Successful": arIcon = "✔"; break;
                    case "Running": arIcon = "◑"; break;
                    case "Failed": arIcon = "✖"; break;
                    case "Error": arIcon = "✖"; break;
                    default: arIcon = "?"; break;
                }

                String prefix = i == analysisRuns.size() - 1 ? "└──" : "├──";
                sb.append(prefix).append("α ").append(arName).append("  AnalysisRun  ").append(arIcon).append(" ").append(arPhase);

                // Show metric results for failed/running analysis
                if ("Failed".equals(arPhase) || "Running".equals(arPhase)) {
                    Object metricResults = arStatus.get("metricResults");
                    if (metricResults instanceof List) {
                        List<Map<String, Object>> metrics = (List<Map<String, Object>>) metricResults;
                        for (Map<String, Object> metric : metrics) {
                            String metricName = metric.getOrDefault("name", "unknown").toString();
                            String metricPhase = metric.getOrDefault("phase", "").toString();
                            String metricIcon = "Successful".equals(metricPhase) ? "✔" : "Failed".equals(metricPhase) ? "✖" : "◑";
                            String childPrefix = i == analysisRuns.size() - 1 ? "   " : "│  ";
                            sb.append("\n").append(childPrefix).append("└──⊞ ").append(metricName).append("  Metric  ").append(metricIcon).append(" ").append(metricPhase);
                        }
                    }
                }

                sb.append("\n");
            }

        } catch (Exception e) {
            Log.debug("Cannot build tree view: " + e.getMessage());
        }

        return sb.toString();
    }

    private boolean isPodRunning(Pod pod) {
        return "Running".equals(pod.getStatus().getPhase());
    }

    private String getPodPhase(Pod pod) {
        return pod.getStatus().getPhase() != null ? pod.getStatus().getPhase() : "Unknown";
    }

    public static class TerminalOutput {
        public boolean connected;
        public String output;

        public TerminalOutput(boolean connected, String output) {
            this.connected = connected;
            this.output = output;
        }
    }
}
