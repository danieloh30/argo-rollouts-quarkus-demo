# Argo Rollouts Quarkus Demo

A Quarkus application that serves as the demo workload for AI-powered progressive delivery with Argo Rollouts on OpenShift. It provides a real-time dashboard, built-in load generation, and configurable bug scenarios for demonstrating autonomous canary analysis and automated remediation.

## Architecture

```
 Dashboard (Qute)         Terminal Panels           Load Generator
      |                        |                         |
      v                        v                         v
 DashboardResource     TerminalResource          LoadGeneratorService
                        |           |              (50 req/s to
                   oc CLI -----> K8s Client         /api/status &
                   (rollout      (fallback,         /api/user)
                    status)       in-cluster)
                                    |
                         Argo Rollouts CRDs
                        (Rollout, AnalysisRun)
```

The dashboard polls the backend for rollout status, traffic weights, version metrics, and AI analysis results. Two terminal panels stream live `oc argo rollouts get rollout` output and Digital SRE Agent pod logs, falling back to the Kubernetes Java client when the CLI is unavailable (e.g., inside the cluster). Popup notifications surface GitHub PRs and Issues created by the AI agent.

## Container Images and Scenarios

Four scenario images are available, each with a specific bug baked in via build-time properties.

| Tag | Scenario | Bug Flag | Behavior |
|-----|----------|----------|----------|
| `v1.stable` | Stable | none | Healthy -- canary promotes to 100% |
| `v2.nullpointer` | NullPointerException | `enable.null.pointer.bug` | 20% of `/api/user` calls throw NPE; AI agent rolls back, creates **PR** with fix |
| `v3.memoryleak` | Memory leak | `enable.memory.leak` | Heap grows ~1 MB/request; latency degrades 6x over 90 s; AI agent rolls back, creates **Issue** |
| `v4.slowdependency` | Slow downstream | `enable.slow.dependency` | Downstream inventory-service degrades over 60 s, then 30% timeout rate; AI agent rolls back, creates **Issue** |

Registry: `quay.io/danieloh30/argo-rollouts-quarkus-demo`

## Local Development

### Prerequisites

- Java 21+
- Maven 3.9+ (or use the included `./mvnw` wrapper)

### Run in Dev Mode

```bash
./mvnw quarkus:dev
```

The app starts on port 8080:

| Endpoint | Path |
|----------|------|
| Dashboard | `/` |
| Status API | `/api/status` |
| Health | `/q/health` |
| Prometheus metrics | `/q/metrics` |
| Quarkus Dev UI | `/q/dev-ui` |

### Configuration

Key properties in `src/main/resources/application.properties`:

| Property | Default | Description |
|----------|---------|-------------|
| `app.version` | `1.0.0` | Version string shown in responses and dashboard |
| `scenario.mode` | `success` | `success` or `failure` (affects simulated error rate) |
| `enable.null.pointer.bug` | `false` | Trigger NPE in `UserResource.getUser` |
| `enable.memory.leak` | `false` | Allocate 1 MB/request without cleanup |
| `enable.slow.dependency` | `false` | Simulate downstream service degradation and timeouts |
| `load.generator.enabled` | `true` | Built-in load generator (disable for local dev) |
| `load.generator.requests.per.second` | `50` | Request rate for the load generator |
| `rollout.name` | `quarkus-demo` | Argo Rollout CR name to watch |
| `rollout.namespace` | `quarkus-demo` | Namespace of the Rollout CR |

All properties can be overridden with environment variables using the standard Quarkus mapping (dots to underscores, uppercase). For example: `ENABLE_NULL_POINTER_BUG=true`.

## Building Scenario Images

Build a JVM container image locally:

```bash
./mvnw package -DskipTests
docker build -f src/main/docker/Dockerfile.jvm -t quay.io/danieloh30/argo-rollouts-quarkus-demo:v1.stable .
```

To bake in a bug scenario, edit `application.properties` before building (or override at deploy time via env vars):

```bash
# Example: build the NPE scenario image
sed -i '' 's/enable.null.pointer.bug=false/enable.null.pointer.bug=true/' src/main/resources/application.properties
./mvnw package -DskipTests
docker build -f src/main/docker/Dockerfile.jvm -t quay.io/danieloh30/argo-rollouts-quarkus-demo:v2.nullpointer .
git checkout src/main/resources/application.properties
```

Alternatively, use the Quarkus container image extension to build and push in one step:

```bash
./mvnw package -DskipTests -Dquarkus.container-image.build=true -Dquarkus.container-image.push=true -Dquarkus.container-image.tag=v1.stable
```

## Integration with Argo Rollouts and the AI Agent

This application is designed to run as an Argo Rollout with a canary strategy. The typical flow:

1. **Deploy the stable image** (`v1.stable`) as the initial Rollout.
2. **Update the image** to a scenario tag (e.g., `v2.nullpointer`) to trigger a canary rollout.
3. **Argo Rollouts** shifts traffic in steps (e.g., 10% -> 30% -> 60% -> 100%), pausing at each step to run an AnalysisRun.
4. **The AI metric provider plugin** collects pod logs and metrics from this app's `/q/metrics` and `/api/status` endpoints, then delegates analysis to the Digital SRE Agent.
5. **The Digital SRE Agent** (a separate Quarkus + LangChain4j application) uses an LLM to evaluate deployment health and returns a `PROCEED` or `ROLLBACK` decision.
6. On failure, the agent **creates a GitHub PR or Issue** with root cause analysis and a proposed fix. The dashboard shows a popup notification when this happens.

The dashboard visualizes the entire process in real time: traffic distribution between stable and canary, per-version success rates, analysis phase and outcome, and the terminal output from both the rollout controller and the AI agent.

## Technology Stack

- Quarkus 3.38.1 (REST, Qute, Scheduler, Kubernetes Client)
- Micrometer + Prometheus registry
- SmallRye Health
- Fabric8 Kubernetes Client (for Argo Rollouts CRD access)
- Java 21

## License

Apache License 2.0
