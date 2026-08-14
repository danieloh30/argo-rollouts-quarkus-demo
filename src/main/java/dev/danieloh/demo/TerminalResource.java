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
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
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

    @GET
    @Path("/rollout")
    @Produces(MediaType.APPLICATION_JSON)
    public TerminalOutput getRolloutTerminal() {
        // Try CLI first (works locally with oc plugin)
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "oc", "argo", "rollouts", "get", "rollout", rolloutName,
                    "-n", rolloutNamespace);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) process.destroyForcibly();

            if (!output.isEmpty() && !output.contains("not found")) {
                output = output.replaceAll("\\[[;\\d]*m", "");
                return new TerminalOutput(true, output);
            }
        } catch (Exception e) {
            Log.debug("CLI unavailable, falling back to Kubernetes client: " + e.getMessage());
        }

        // Fallback: Kubernetes Java client
        return getRolloutViaClient();
    }

    @GET
    @Path("/agent-logs")
    @Produces(MediaType.APPLICATION_JSON)
    public TerminalOutput getAgentLogs() {
        // Try CLI first
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "oc", "logs", "deployment/" + agentDeployment,
                    "-n", agentNamespace, "--tail=500");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String logs;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                logs = reader.lines().collect(Collectors.joining("\n"));
            }

            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) process.destroyForcibly();

            if (!logs.isEmpty() && !logs.startsWith("Error") && !logs.startsWith("error")) {
                return new TerminalOutput(true, logs);
            }
        } catch (Exception e) {
            Log.debug("CLI unavailable for agent logs: " + e.getMessage());
        }

        // Fallback: Kubernetes Java client
        return getAgentLogsViaClient();
    }

    @SuppressWarnings("unchecked")
    private TerminalOutput getRolloutViaClient() {
        try {
            GenericKubernetesResource rollout = kubernetesClient
                    .genericKubernetesResources(ROLLOUT_CONTEXT)
                    .inNamespace(rolloutNamespace)
                    .withName(rolloutName)
                    .get();

            if (rollout == null) {
                return new TerminalOutput(false, null);
            }

            Map<String, Object> status = (Map<String, Object>) rollout.getAdditionalProperties().getOrDefault("status", Map.of());
            Map<String, Object> spec = (Map<String, Object>) rollout.getAdditionalProperties().getOrDefault("spec", Map.of());

            String phase = status.getOrDefault("phase", "Unknown").toString();
            String message = status.containsKey("message") ? status.get("message").toString() : "";
            String stableRS = status.containsKey("stableRS") ? status.get("stableRS").toString() : "";
            String currentPodHash = status.containsKey("currentPodHash") ? status.get("currentPodHash").toString() : "";

            int currentStep = 0;
            Object csi = status.get("currentStepIndex");
            if (csi instanceof Number) currentStep = ((Number) csi).intValue();

            Map<String, Object> strategy = (Map<String, Object>) spec.getOrDefault("strategy", Map.of());
            Map<String, Object> canary = (Map<String, Object>) strategy.getOrDefault("canary", Map.of());
            List<Object> steps = (List<Object>) canary.getOrDefault("steps", List.of());
            int totalSteps = steps.size();

            int replicas = spec.get("replicas") instanceof Number ? ((Number) spec.get("replicas")).intValue() : 1;
            int ready = status.get("readyReplicas") instanceof Number ? ((Number) status.get("readyReplicas")).intValue() : replicas;
            int available = status.get("availableReplicas") instanceof Number ? ((Number) status.get("availableReplicas")).intValue() : ready;
            int updated = status.get("updatedReplicas") instanceof Number ? ((Number) status.get("updatedReplicas")).intValue() : replicas;

            int canaryWeight = 0;
            Object canaryStatus = status.get("canary");
            if (canaryStatus instanceof Map) {
                Object weights = ((Map<String, Object>) canaryStatus).get("weights");
                if (weights instanceof Map) {
                    Object cw = ((Map<String, Object>) weights).get("canary");
                    if (cw instanceof Number) canaryWeight = ((Number) cw).intValue();
                }
            }

            String phaseIcon = switch (phase) {
                case "Healthy" -> "✔";
                case "Progressing" -> "◑";
                case "Paused" -> "॥";
                case "Degraded" -> "✖";
                default -> "?";
            };

            String image = "N/A";
            try {
                Map<String, Object> template = (Map<String, Object>) spec.get("template");
                Map<String, Object> templateSpec = (Map<String, Object>) template.get("spec");
                List<Map<String, Object>> containers = (List<Map<String, Object>>) templateSpec.get("containers");
                image = containers.get(0).getOrDefault("image", "N/A").toString();
            } catch (Exception ignored) {}

            StringBuilder sb = new StringBuilder();
            sb.append("Name:            ").append(rolloutName).append("\n");
            sb.append("Namespace:       ").append(rolloutNamespace).append("\n");
            sb.append("Status:          ").append(phaseIcon).append(" ").append(phase).append("\n");
            if (!message.isEmpty()) sb.append("Message:         ").append(message).append("\n");
            sb.append("Strategy:        Canary\n");
            sb.append("  Step:          ").append(currentStep).append("/").append(totalSteps).append("\n");
            sb.append("  SetWeight:     ").append(canaryWeight).append("\n");
            sb.append("  ActualWeight:  ").append(canaryWeight).append("\n");
            sb.append("Images:          ").append(image);
            sb.append(canaryWeight > 0 && canaryWeight < 100 ? " (canary)" : " (stable)").append("\n");
            sb.append("Replicas:\n");
            sb.append("  Desired:       ").append(replicas).append("\n");
            sb.append("  Current:       ").append(ready).append("\n");
            sb.append("  Updated:       ").append(updated).append("\n");
            sb.append("  Ready:         ").append(ready).append("\n");
            sb.append("  Available:     ").append(available).append("\n\n");

            // Tree view
            sb.append("NAME                                       KIND         STATUS     AGE    INFO\n");
            sb.append(phaseIcon).append(" ").append(rolloutName);
            sb.append("                              Rollout      ").append(phaseIcon).append(" ").append(phase).append("\n");

            // Pods grouped by hash
            List<Pod> allPods = kubernetesClient.pods()
                    .inNamespace(rolloutNamespace)
                    .withLabel("app", rolloutName)
                    .list().getItems();

            Map<String, List<Pod>> podsByHash = allPods.stream()
                    .filter(p -> p.getMetadata().getLabels() != null && p.getMetadata().getLabels().containsKey("rollouts-pod-template-hash"))
                    .collect(Collectors.groupingBy(p -> p.getMetadata().getLabels().get("rollouts-pod-template-hash")));

            // AnalysisRuns
            List<GenericKubernetesResource> analysisRuns = new ArrayList<>();
            try {
                analysisRuns = kubernetesClient
                        .genericKubernetesResources(ANALYSIS_RUN_CONTEXT)
                        .inNamespace(rolloutNamespace)
                        .withLabel("app", rolloutName)
                        .list().getItems();
                analysisRuns.sort((a, b) -> {
                    String tsA = a.getMetadata().getCreationTimestamp();
                    String tsB = b.getMetadata().getCreationTimestamp();
                    return tsB != null && tsA != null ? tsB.compareTo(tsA) : 0;
                });
            } catch (Exception ignored) {}

            // Canary RS
            if (!currentPodHash.isEmpty() && !currentPodHash.equals(stableRS)) {
                appendReplicaSet(sb, currentPodHash, "canary", podsByHash, analysisRuns);
            }

            // Stable RS
            if (!stableRS.isEmpty() && podsByHash.containsKey(stableRS)) {
                appendReplicaSet(sb, stableRS, "stable", podsByHash, analysisRuns);
            }

            // Remaining AnalysisRuns
            for (GenericKubernetesResource ar : analysisRuns) {
                Map<String, Object> arStatus = (Map<String, Object>) ar.getAdditionalProperties().getOrDefault("status", Map.of());
                String arPhase = arStatus.getOrDefault("phase", "Unknown").toString();
                String arIcon = switch (arPhase) { case "Successful" -> "✔"; case "Running" -> "◑"; case "Failed", "Error" -> "✖"; default -> "?"; };
                sb.append("└──α ").append(ar.getMetadata().getName()).append("  AnalysisRun  ").append(arIcon).append(" ").append(arPhase).append("\n");
            }

            return new TerminalOutput(true, sb.toString());
        } catch (Exception e) {
            Log.debug("Kubernetes client cannot fetch rollout: " + e.getMessage());
            return new TerminalOutput(false, null);
        }
    }

    @SuppressWarnings("unchecked")
    private void appendReplicaSet(StringBuilder sb, String hash, String role,
                                   Map<String, List<Pod>> podsByHash, List<GenericKubernetesResource> analysisRuns) {
        List<Pod> pods = podsByHash.getOrDefault(hash, List.of());
        String rsName = rolloutName + "-" + hash;
        String rsStatus = pods.stream().allMatch(p -> "Running".equals(p.getStatus().getPhase())) ? "✔ Healthy" : "◑ Progressing";
        sb.append("├──⧉ ").append(rsName).append("  ReplicaSet  ").append(rsStatus).append("  ").append(role).append("\n");
        for (int i = 0; i < pods.size(); i++) {
            Pod pod = pods.get(i);
            String podPhase = "Running".equals(pod.getStatus().getPhase()) ? "✔ Running" : "✖ " + pod.getStatus().getPhase();
            String prefix = i == pods.size() - 1 ? "│  └──" : "│  ├──";
            sb.append(prefix).append("□ ").append(pod.getMetadata().getName()).append("  Pod  ").append(podPhase).append("  ready:1/1\n");
        }

        // Show AnalysisRuns for this hash
        List<GenericKubernetesResource> matchingARs = analysisRuns.stream()
                .filter(ar -> ar.getMetadata().getLabels() != null &&
                        hash.equals(ar.getMetadata().getLabels().get("rollouts-pod-template-hash")))
                .collect(Collectors.toList());
        for (GenericKubernetesResource ar : matchingARs) {
            Map<String, Object> arStatus = (Map<String, Object>) ar.getAdditionalProperties().getOrDefault("status", Map.of());
            String arPhase = arStatus.getOrDefault("phase", "Unknown").toString();
            String arIcon = switch (arPhase) { case "Successful" -> "✔"; case "Running" -> "◑"; case "Failed", "Error" -> "✖"; default -> "?"; };
            sb.append("│  └──α ").append(ar.getMetadata().getName()).append("  AnalysisRun  ").append(arIcon).append(" ").append(arPhase);

            if ("Failed".equals(arPhase) || "Running".equals(arPhase)) {
                Object metricResults = arStatus.get("metricResults");
                if (metricResults instanceof List) {
                    for (Map<String, Object> metric : (List<Map<String, Object>>) metricResults) {
                        String mName = metric.getOrDefault("name", "unknown").toString();
                        String mPhase = metric.getOrDefault("phase", "").toString();
                        String mIcon = "Successful".equals(mPhase) ? "✔" : "Failed".equals(mPhase) ? "✖" : "◑";
                        sb.append("\n│     └──⊞ ").append(mName).append("  Metric  ").append(mIcon).append(" ").append(mPhase);
                    }
                }
            }
            sb.append("\n");
        }
        analysisRuns.removeAll(matchingARs);
    }

    private TerminalOutput getAgentLogsViaClient() {
        try {
            List<Pod> pods = kubernetesClient.pods()
                    .inNamespace(agentNamespace)
                    .withLabel("app", agentDeployment)
                    .list().getItems();

            if (pods.isEmpty()) {
                pods = kubernetesClient.pods()
                        .inNamespace(agentNamespace)
                        .withLabel("app.kubernetes.io/name", agentDeployment)
                        .list().getItems();
            }

            if (!pods.isEmpty()) {
                String logs = kubernetesClient.pods()
                        .inNamespace(agentNamespace)
                        .withName(pods.get(0).getMetadata().getName())
                        .tailingLines(500)
                        .getLog();
                if (logs != null && !logs.isEmpty()) {
                    return new TerminalOutput(true, logs);
                }
            }
        } catch (Exception e) {
            Log.debug("Kubernetes client cannot fetch agent logs: " + e.getMessage());
        }
        return new TerminalOutput(false, null);
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
