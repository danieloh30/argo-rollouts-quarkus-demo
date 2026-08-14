package dev.danieloh.demo;

import io.quarkus.logging.Log;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Path("/api/terminal")
public class TerminalResource {

    @ConfigProperty(name = "rollout.name", defaultValue = "quarkus-demo")
    String rolloutName;

    @ConfigProperty(name = "rollout.namespace", defaultValue = "quarkus-demo")
    String rolloutNamespace;

    @ConfigProperty(name = "agent.namespace", defaultValue = "openshift-gitops")
    String agentNamespace;

    @ConfigProperty(name = "agent.deployment", defaultValue = "kubernetes-agent")
    String agentDeployment;

    @GET
    @Path("/rollout")
    @Produces(MediaType.APPLICATION_JSON)
    public TerminalOutput getRolloutTerminal() {
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
            if (!finished) {
                process.destroyForcibly();
            }

            if (!output.isEmpty()) {
                output = output.replaceAll("\\[[;\\d]*m", "");
                return new TerminalOutput(true, output);
            }
        } catch (Exception e) {
            Log.debug("CLI cannot fetch rollout status: " + e.getMessage());
        }

        return new TerminalOutput(false, null);
    }

    @GET
    @Path("/agent-logs")
    @Produces(MediaType.APPLICATION_JSON)
    public TerminalOutput getAgentLogs() {
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

            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
            }

            if (!logs.isEmpty()) {
                return new TerminalOutput(true, logs);
            }
        } catch (Exception e) {
            Log.debug("CLI cannot fetch agent logs: " + e.getMessage());
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
