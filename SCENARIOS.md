# Container-Based Test Scenarios for Argo Rollouts

This document explains the container-based approach for demonstrating AI-powered progressive delivery with Argo Rollouts.

## Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
- [The Four Scenarios](#the-four-scenarios)
  - [Scenario 1: Stable Deployment (Happy Path)](#scenario-1-stable-deployment-happy-path)
  - [Scenario 2: NullPointerException Bug (Fixable)](#scenario-2-nullpointerexception-bug-fixable)
  - [Scenario 3: Memory Leak (Non-Fixable)](#scenario-3-memory-leak-non-fixable)
  - [Scenario 4: Slow Downstream Dependency (Non-Fixable)](#scenario-4-slow-downstream-dependency-non-fixable)
- [Quick Start](#quick-start)
- [Demo Flow](#demo-flow)
- [Building Scenario Images](#building-scenario-images)
- [Troubleshooting](#troubleshooting)

---

## Overview

The container-based approach simplifies progressive delivery demonstrations by:

- **Built-in Load Generator**: Each container generates 50 requests/second automatically
- **Pre-built Scenario Images**: Four distinct container images with different behaviors
- **GitOps-Ready**: Change the image tag in `rollout.yaml`, commit, and push
- **AI-Powered Analysis**: Kubernetes agent analyzes logs and metrics to make promote/rollback decisions
- **Automated Remediation**: Agent creates GitHub PRs for code bugs and Issues for operational problems

---

## Architecture

```
+--------------------------+          +-------------------------------------------+
|   progressive-delivery   |   sync   |   OpenShift Cluster                       |
|   (GitOps repo)          +--------->|                                           |
|                          |          |   Argo Rollouts Controller                |
|  rollout.yaml:           |          |     10% canary (10s)                      |
|    image: v2.nullpointer |          |     10% canary + AI analysis (40s)        |
|                          |          |     60% canary (20s)                      |
|                          |          |     100% canary (10s)                     |
+--------------------------+          +-------------------------------------------+
                                                      |
                                              AI Analysis
                                                      |
                                      +---------------+---------------+
                                      |                               |
                                 PASS (promote)                 FAIL (rollback)
                                 (Scenario 1)                   (Scenarios 2-4)
                                                                      |
                                                        +-------------+-------------+
                                                        |                           |
                                                   Code Bug                  Operational
                                                   -> PR                     -> Issue
                                                   (Scenario 2)              (Scenarios 3, 4)
```

### Components

1. **Quarkus Application** (`src/main/java/dev/danieloh/demo/`)
   - `LoadGeneratorService.java`: Built-in load generator (50 req/sec)
   - `DemoScenarioService.java`: Simulates bugs (NPE, memory leak, slow dependency)
   - `UserResource.java`: REST endpoint with optional NPE bug

2. **Container Images** (hosted on `quay.io/danieloh30/argo-rollouts-quarkus-demo`)
   - `v1.stable` -- Healthy application
   - `v2.nullpointer` -- NullPointerException bug (20% of requests)
   - `v3.memoryleak` -- Memory leak (1MB per request)
   - `v4.slowdependency` -- Downstream service timeout (50% after 20s)

3. **Kubernetes Agent** (`quay.io/danieloh30/kubernetes-agent`)
   - Analyzes logs and metrics during canary deployment
   - Creates GitHub PRs for fixable code issues
   - Creates GitHub Issues for operational problems
   - Dynamic issue titles and labels based on root cause analysis

---

## The Four Scenarios

### Scenario 1: Stable Deployment (Happy Path)

**Image**: `quay.io/danieloh30/argo-rollouts-quarkus-demo:v1.stable`

| Metric | Value |
|--------|-------|
| Success rate | 99% |
| Latency | 10-50ms |
| Errors | None |
| Memory | Stable |

**AI Decision**: PASS -- rollout completes successfully, no GitHub activity.

---

### Scenario 2: NullPointerException Bug (Fixable)

**Image**: `quay.io/danieloh30/argo-rollouts-quarkus-demo:v2.nullpointer`

| Metric | Value |
|--------|-------|
| Success rate | ~80% |
| Error type | `NullPointerException` at `UserResource.java` |
| Failure rate | 20% of requests |
| Root cause | Missing null check on `user` before calling `user.getName()` |

**AI Decision**: FAIL -- rollout aborted, **Pull Request** created with code fix.

The agent detects the NPE in canary logs, identifies it as a code bug (clear stack trace pointing to a specific line), and creates a PR with the fix on a new branch.

---

### Scenario 3: Memory Leak (Non-Fixable)

**Image**: `quay.io/danieloh30/argo-rollouts-quarkus-demo:v3.memoryleak`

| Metric | Value |
|--------|-------|
| Success rate | Degrades over time |
| Latency | Increases 6x over 90s (10ms to 300ms) |
| Heap growth | ~1MB per request, never released |
| Log pattern | Performance degradation warnings, no stack traces |

**AI Decision**: FAIL -- rollout aborted, **GitHub Issue** created with root cause analysis.

The agent detects increasing latency and memory pressure warnings. Since there's no clear stack trace pointing to fixable code, it classifies this as an operational issue and creates an Issue instead of a PR.

---

### Scenario 4: Slow Downstream Dependency (Non-Fixable)

**Image**: `quay.io/danieloh30/argo-rollouts-quarkus-demo:v4.slowdependency`

| Phase | Timing | Behavior |
|-------|--------|----------|
| Normal | 0-10s | Fast responses (20-50ms) |
| Degradation | 10-20s | Latency climbs, warning logs about downstream service |
| Timeout | 20s+ | 50% of requests timeout with `RuntimeException: Downstream service timeout: inventory-service did not respond within 3000ms` |

| Metric | Value |
|--------|-------|
| Error type | `RuntimeException` (downstream timeout) |
| Timeout rate | 50% after 20s |
| Log keywords | `TIMEOUT`, `circuit breaker`, `inventory-service`, `unresponsive` |

**AI Decision**: FAIL -- rollout aborted, **GitHub Issue** created with root cause analysis.

The agent detects timeout errors and circuit breaker warnings in canary logs, classifies it as an operational/infrastructure issue (downstream dependency problem, not a code bug), and creates an Issue with labels like `bug`, `downstream-timeout`, `canary-analysis`.

---

## Quick Start

### Prerequisites

- OpenShift cluster with Argo Rollouts and Argo CD installed
- Kubernetes agent deployed in `openshift-gitops` namespace
- GitHub token configured for PR/Issue creation

### Deploy a Scenario

Edit the image tag in `workloads/quarkus-rollouts-demo/base/rollout.yaml`:

```yaml
containers:
- name: quarkus-demo
  image: quay.io/danieloh30/argo-rollouts-quarkus-demo:v2.nullpointer
```

Commit and push:

```bash
git add workloads/quarkus-rollouts-demo/base/rollout.yaml
git commit -m "Switch to v2.nullpointer scenario"
git push
```

### Watch the Rollout

```bash
oc argo rollouts get rollout quarkus-demo -n quarkus-demo --watch

oc get analysisrun -n quarkus-demo

oc logs -n openshift-gitops -l app=kubernetes-agent --tail=30
```

---

## Demo Flow

### Complete Demo Script (All Four Scenarios)

**Total Time**: ~8 minutes

#### Part 1: Stable (2 min)

1. Set image to `v1.stable`, commit, push
2. Watch rollout -- AI finds no issues, rollout completes
3. Show: AI correctly identifies healthy deployments

#### Part 2: NullPointerException (2 min)

1. Set image to `v2.nullpointer`, commit, push
2. Watch rollout -- AI detects NPE in canary logs, rollout aborts
3. Show: GitHub PR created with automated code fix
4. Key point: AI creates a **PR** because it can trace the bug to a specific code line

#### Part 3: Memory Leak (2 min)

1. Set image to `v3.memoryleak`, commit, push
2. Watch rollout -- AI detects memory pressure and latency degradation, rollout aborts
3. Show: GitHub Issue created with investigation steps
4. Key point: AI creates an **Issue** because there's no obvious stack trace to fix

#### Part 4: Slow Dependency (2 min)

1. Set image to `v4.slowdependency`, commit, push
2. Watch rollout -- AI detects downstream timeouts and circuit breaker warnings, rollout aborts
3. Show: GitHub Issue created with downstream timeout diagnosis
4. Key point: AI distinguishes between code bugs and infrastructure/dependency problems

### Key Demo Points

1. **No external tools needed** -- built-in load generator provides traffic
2. **Fast analysis** -- AI completes analysis within the 40-second analysis window
3. **Smart classification** -- AI distinguishes code bugs (PR) from operational issues (Issue)
4. **Dynamic labels** -- Issue titles and labels reflect the specific root cause (memory leak vs timeout)
5. **GitOps-driven** -- all scenario switches via image tag changes in git

---

## Building Scenario Images

### Locally with Maven + Podman

```bash
cd argo-rollouts-quarkus-demo

# Scenario 1: Stable
mvn clean package -DskipTests \
  -Dquarkus.container-image.build=true \
  -Dquarkus.container-image.push=true \
  -Dapp.version=v1.stable

# Scenario 2: NullPointerException
sed -i 's/enable.null.pointer.bug=false/enable.null.pointer.bug=true/' \
  src/main/resources/application.properties
mvn clean package -DskipTests \
  -Dquarkus.container-image.build=true \
  -Dquarkus.container-image.push=true \
  -Dapp.version=v2.nullpointer
git checkout src/main/resources/application.properties

# Scenario 3: Memory Leak
sed -i 's/enable.memory.leak=false/enable.memory.leak=true/' \
  src/main/resources/application.properties
mvn clean package -DskipTests \
  -Dquarkus.container-image.build=true \
  -Dquarkus.container-image.push=true \
  -Dapp.version=v3.memoryleak
git checkout src/main/resources/application.properties

# Scenario 4: Slow Dependency
sed -i 's/enable.slow.dependency=false/enable.slow.dependency=true/' \
  src/main/resources/application.properties
mvn clean package -DskipTests \
  -Dquarkus.container-image.build=true \
  -Dquarkus.container-image.push=true \
  -Dapp.version=v4.slowdependency
git checkout src/main/resources/application.properties
```

### Image Registry

Images are hosted on Quay.io: `quay.io/danieloh30/argo-rollouts-quarkus-demo`

Tags: `v1.stable`, `v2.nullpointer`, `v3.memoryleak`, `v4.slowdependency`

---

## Troubleshooting

### Rollout Not Starting

```bash
# Verify the image tag actually changed
oc get rollout quarkus-demo -n quarkus-demo -o jsonpath='{.spec.template.spec.containers[0].image}'

# Check Argo CD sync status
oc get app quarkus-rollouts-demo -n openshift-gitops
```

### AI Analysis Returning 500

```bash
# Check agent logs
oc logs -n openshift-gitops -l app=kubernetes-agent --tail=50

# Common causes:
# - Invalid API key (check secret)
# - Wrong Quarkus profile (check QUARKUS_PROFILE env var)
# - Missing @V annotation on agent interfaces (MissingArgumentException)
```

### No GitHub PR/Issue Created

```bash
# Check agent logs for GitHub errors
oc logs -n openshift-gitops -l app=kubernetes-agent | grep -i github

# Verify GitHub token has 'repo' scope
# Verify required labels exist on the repo:
gh label list -R danieloh30/argo-rollouts-quarkus-demo
```

### Slow Dependency Not Triggering Rollback

The v4.slowdependency scenario has phased timing. Timeouts start at 20 seconds after pod startup. If the analysis runs before that window, it may see healthy behavior. Ensure the rollout analysis starts late enough (configured via `startingStep` in the rollout spec).

---

## Summary

| Scenario | Image Tag | Bug Type | AI Action | GitHub Artifact |
|----------|-----------|----------|-----------|-----------------|
| Stable | `v1.stable` | None | Promote | None |
| NPE | `v2.nullpointer` | Code bug | Rollback | **PR** with fix |
| Memory Leak | `v3.memoryleak` | Operational | Rollback | **Issue** with RCA |
| Slow Dependency | `v4.slowdependency` | Operational | Rollback | **Issue** with RCA |
