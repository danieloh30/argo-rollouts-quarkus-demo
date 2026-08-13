package dev.kevindubois.demo;

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
import java.util.List;
import java.util.Map;

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
        try {
            List<Pod> pods = kubernetesClient.pods()
                    .inNamespace(agentNamespace)
                    .withLabel("app.kubernetes.io/name", agentDeployment)
                    .list()
                    .getItems();

            if (pods.isEmpty()) {
                pods = kubernetesClient.pods()
                        .inNamespace(agentNamespace)
                        .withLabel("app", agentDeployment)
                        .list()
                        .getItems();
            }

            if (pods.isEmpty()) {
                return new TerminalOutput(false, "No pods found for deployment '" + agentDeployment + "' in namespace '" + agentNamespace + "'");
            }

            Pod pod = pods.get(0);
            String podName = pod.getMetadata().getName();

            String logs = kubernetesClient.pods()
                    .inNamespace(agentNamespace)
                    .withName(podName)
                    .tailingLines(50)
                    .getLog();

            return new TerminalOutput(true, logs);

        } catch (Exception e) {
            Log.debug("Cannot fetch agent logs: " + e.getMessage());
            return new TerminalOutput(false, null);
        }
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

        String stableRS = status.containsKey("stableRS") ? status.get("stableRS").toString() : "unknown";
        String currentRS = status.containsKey("currentPodHash") ? status.get("currentPodHash").toString() : stableRS;

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

        // Get container image
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

        return sb.toString();
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
