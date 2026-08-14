let previousSuccessRate = null;
let previousErrorRate = null;
const startTime = Date.now();
let clusterConnected = false;
let seenNotifications = new Set();

// ── Simulated fallback data for dev mode / no cluster ──
const FALLBACK_ROLLOUT_TERMINAL =
    '<span class="t-bold">Name:            </span><span class="t-cyan">quarkus-demo</span>\n' +
    '<span class="t-bold">Namespace:       </span><span class="t-cyan">quarkus-demo</span>\n' +
    '<span class="t-bold">Status:          </span><span class="t-green">✔ Healthy</span>\n' +
    '<span class="t-bold">Strategy:        </span>Canary\n' +
    '<span class="t-bold">  Step:          </span>6/6\n' +
    '<span class="t-bold">  SetWeight:     </span>100\n' +
    '<span class="t-bold">  ActualWeight:  </span>100\n' +
    '<span class="t-bold">Images:          </span><span class="t-blue">ghcr.io/danieloh30/argo-rollouts-quarkus-demo:latest</span> (stable)\n' +
    '<span class="t-bold">Replicas:</span>\n' +
    '<span class="t-bold">  Desired:       </span>3\n' +
    '<span class="t-bold">  Current:       </span>3\n' +
    '<span class="t-bold">  Updated:       </span>3\n' +
    '<span class="t-bold">  Ready:         </span>3\n' +
    '<span class="t-bold">  Available:     </span>3\n' +
    '\n' +
    '<span class="t-dim">NAME                                       KIND        STATUS     AGE    INFO</span>\n' +
    '<span class="t-green">⟳ quarkus-demo                              Rollout     ✔ Healthy  12m</span>\n' +
    '<span class="t-green">├──# revision:3                                                           </span>\n' +
    '<span class="t-green">│  └──⧉ quarkus-demo-7f8d4b5c6             ReplicaSet  ✔ Healthy  12m    stable</span>\n' +
    '<span class="t-green">│     ├──□ quarkus-demo-7f8d4b5c6-2k9xp    Pod         ✔ Running  12m    ready:1/1</span>\n' +
    '<span class="t-green">│     ├──□ quarkus-demo-7f8d4b5c6-8m4vq    Pod         ✔ Running  12m    ready:1/1</span>\n' +
    '<span class="t-green">│     └──□ quarkus-demo-7f8d4b5c6-wn7j3    Pod         ✔ Running  12m    ready:1/1</span>\n' +
    '<span class="t-dim">├──# revision:2                                                           </span>\n' +
    '<span class="t-dim">│  └──⧉ quarkus-demo-5c9b8d2a1             ReplicaSet  • ScaledDown  45m</span>\n' +
    '<span class="t-dim">└──# revision:1                                                           </span>\n' +
    '<span class="t-dim">   └──⧉ quarkus-demo-3a7e1f9c4             ReplicaSet  • ScaledDown  2h</span>';

const FALLBACK_AGENT_LOGS =
    '<span class="t-dim">2025-08-13T09:14:02Z</span> <span class="t-blue">INFO</span>  controller.appproject  Reconciling AppProject  <span class="t-dim">{"namespace": "openshift-gitops"}</span>\n' +
    '<span class="t-dim">2025-08-13T09:14:03Z</span> <span class="t-blue">INFO</span>  controller.application  Refreshing app status  <span class="t-dim">{"application": "quarkus-demo"}</span>\n' +
    '<span class="t-dim">2025-08-13T09:14:03Z</span> <span class="t-blue">INFO</span>  controller.application  Comparing app state  <span class="t-dim">{"application": "quarkus-demo", "revisionChanged": false}</span>\n' +
    '<span class="t-dim">2025-08-13T09:14:04Z</span> <span class="t-green">INFO</span>  controller.application  App health check  <span class="t-dim">{"application": "quarkus-demo", "status": "Healthy"}</span>\n' +
    '<span class="t-dim">2025-08-13T09:14:05Z</span> <span class="t-blue">INFO</span>  controller.cache  Cluster cache synced  <span class="t-dim">{"server": "https://kubernetes.default.svc", "resources": 847}</span>\n' +
    '<span class="t-dim">2025-08-13T09:14:10Z</span> <span class="t-blue">INFO</span>  controller.appproject  Reconciling AppProject  <span class="t-dim">{"namespace": "openshift-gitops"}</span>\n' +
    '<span class="t-dim">2025-08-13T09:14:12Z</span> <span class="t-blue">INFO</span>  controller.application  Sync operation completed  <span class="t-dim">{"application": "quarkus-demo", "phase": "Succeeded", "message": "successfully synced (all tasks run)"}</span>\n' +
    '<span class="t-dim">2025-08-13T09:14:15Z</span> <span class="t-blue">INFO</span>  controller.application  Refreshing app status  <span class="t-dim">{"application": "quarkus-demo"}</span>\n' +
    '<span class="t-dim">2025-08-13T09:14:15Z</span> <span class="t-green">INFO</span>  controller.application  App sync status  <span class="t-dim">{"application": "quarkus-demo", "syncStatus": "Synced", "healthStatus": "Healthy"}</span>\n' +
    '<span class="t-dim">2025-08-13T09:14:20Z</span> <span class="t-blue">INFO</span>  controller.cache  Updated resource cache  <span class="t-dim">{"server": "https://kubernetes.default.svc", "namespace": "quarkus-demo", "kind": "Rollout"}</span>\n' +
    '<span class="t-dim">2025-08-13T09:14:25Z</span> <span class="t-blue">INFO</span>  controller.application  Comparing app state  <span class="t-dim">{"application": "quarkus-demo", "revisionChanged": false}</span>\n' +
    '<span class="t-dim">2025-08-13T09:14:30Z</span> <span class="t-green">INFO</span>  controller.application  App health check  <span class="t-dim">{"application": "quarkus-demo", "status": "Healthy"}</span>\n' +
    '<span class="t-dim">2025-08-13T09:14:35Z</span> <span class="t-blue">INFO</span>  controller.appproject  Reconciling AppProject  <span class="t-dim">{"namespace": "openshift-gitops"}</span>\n' +
    '<span class="t-dim">2025-08-13T09:14:40Z</span> <span class="t-blue">INFO</span>  controller.application  Refreshing app status  <span class="t-dim">{"application": "quarkus-demo"}</span>\n' +
    '<span class="t-dim">2025-08-13T09:14:42Z</span> <span class="t-yellow">WARN</span>  controller.cache  Retrying resource watch  <span class="t-dim">{"server": "https://kubernetes.default.svc", "reason": "stream timeout"}</span>\n' +
    '<span class="t-dim">2025-08-13T09:14:43Z</span> <span class="t-blue">INFO</span>  controller.cache  Resource watch re-established  <span class="t-dim">{"server": "https://kubernetes.default.svc"}</span>\n' +
    '<span class="t-dim">2025-08-13T09:14:45Z</span> <span class="t-green">INFO</span>  controller.application  App sync status  <span class="t-dim">{"application": "quarkus-demo", "syncStatus": "Synced", "healthStatus": "Healthy"}</span>\n' +
    '<span class="t-dim">2025-08-13T09:14:50Z</span> <span class="t-blue">INFO</span>  controller.application  Comparing app state  <span class="t-dim">{"application": "quarkus-demo", "revisionChanged": false}</span>';

// ── Clock & uptime ──
function updateClock() {
    const el = document.getElementById('liveClock');
    if (el) {
        const now = new Date();
        el.textContent = now.toLocaleTimeString('en-US', { hour12: false });
    }
}

function updateUptime() {
    const el = document.getElementById('sysUptime');
    if (!el) return;
    const elapsed = Math.floor((Date.now() - startTime) / 1000);
    const h = Math.floor(elapsed / 3600);
    const m = Math.floor((elapsed % 3600) / 60);
    const s = elapsed % 60;
    if (h > 0) {
        el.textContent = h + 'h ' + m + 'm';
    } else if (m > 0) {
        el.textContent = m + 'm ' + s + 's';
    } else {
        el.textContent = s + 's';
    }
}

function formatNumber(n) {
    if (n >= 1000000) return (n / 1000000).toFixed(1) + 'M';
    if (n >= 1000) return (n / 1000).toFixed(1) + 'K';
    return n.toString();
}

function rateColorClass(rate) {
    if (rate >= 95) return 'success';
    if (rate >= 80) return 'warning';
    return 'danger';
}

// ── Colorize live terminal output ──
function colorizeRolloutOutput(text) {
    return text
        .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
        .replace(/^(Name:|Namespace:|Status:|Message:|Strategy:|Images:|Replicas:)/gm, '<span class="t-bold">$1</span>')
        .replace(/(✔\s*Healthy)/g, '<span class="t-green">$1</span>')
        .replace(/(✔\s*Running)/g, '<span class="t-green">$1</span>')
        .replace(/(✔\s*Successful)/g, '<span class="t-green">$1</span>')
        .replace(/(◑\s*Progressing)/g, '<span class="t-blue">$1</span>')
        .replace(/(◑\s*Running)/g, '<span class="t-blue">$1</span>')
        .replace(/(॥\s*Paused)/g, '<span class="t-yellow">$1</span>')
        .replace(/(✖\s*Degraded)/g, '<span class="t-red">$1</span>')
        .replace(/(✖\s*Failed)/g, '<span class="t-red">$1</span>')
        .replace(/(✖\s*Error)/g, '<span class="t-red">$1</span>')
        .replace(/(\(stable\))/g, '<span class="t-green">$1</span>')
        .replace(/(\(canary\))/g, '<span class="t-cyan">$1</span>')
        .replace(/(AnalysisRun)/g, '<span class="t-cyan">$1</span>')
        .replace(/(ReplicaSet)/g, '<span class="t-blue">$1</span>')
        .replace(/(Pod\s)/g, '<span class="t-dim">Pod </span>')
        .replace(/(Metric\s)/g, '<span class="t-cyan">Metric </span>')
        .replace(/(ready:\d+\/\d+)/g, '<span class="t-dim">$1</span>');
}

function colorizeAgentLogs(text) {
    return text
        .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
        .replace(/^(\d{4}-\d{2}-\d{2}T[\d:.]+Z?)/gm, '<span class="t-dim">$1</span>')
        .replace(/\bINFO\b/g, '<span class="t-blue">INFO</span>')
        .replace(/\bWARN\b/g, '<span class="t-yellow">WARN</span>')
        .replace(/\bERROR\b/g, '<span class="t-red">ERROR</span>')
        .replace(/("Healthy"|"Succeeded"|"Synced")/g, '<span class="t-green">$1</span>')
        .replace(/("Failed"|"Degraded"|"Error")/g, '<span class="t-red">$1</span>');
}

// ── Dashboard update ──
function updateDashboard() {
    Promise.all([
        fetch('/api/status').then(r => r.json()),
        fetch('/api/rollout/status').then(r => r.json()),
        fetch('/api/rollout/metrics').then(r => r.json())
    ])
    .then(([metricsData, rolloutData, versionMetrics]) => {
        clusterConnected = rolloutData.phase !== 'Error' && rolloutData.phase !== 'Unknown';
        updateRolloutProgress(rolloutData);
        updateTrafficDistribution(rolloutData);
        updateAIAnalysis(rolloutData, metricsData, versionMetrics);
        visualizeRealRequests(versionMetrics);
        updateSystemBar(rolloutData, versionMetrics);
        updateConnectionStatus(true);
        clearError();
    })
    .catch(error => {
        console.error('Error updating dashboard:', error);
        clusterConnected = false;
        const isConnectionError = error.message.includes('Failed to fetch') ||
                                 error.message.includes('NetworkError') ||
                                 error.message.includes('fetch');
        if (isConnectionError) {
            showError('Not connected to cluster. Showing simulated data.');
            showDemoMode();
            updateConnectionStatus(false);
        } else {
            showError('Failed to fetch dashboard data: ' + error.message);
        }
    });
}

// ── Terminal updates ──
let terminalFetchInFlight = false;

function updateTerminals() {
    if (terminalFetchInFlight) return;
    terminalFetchInFlight = true;

    Promise.all([
        fetch('/api/terminal/rollout').then(r => r.json()).catch(() => null),
        fetch('/api/terminal/agent-logs').then(r => r.json()).catch(() => null)
    ]).then(([rolloutData, agentData]) => {
        const rolloutBody = document.getElementById('terminalRolloutBody');
        if (rolloutBody) {
            const wasAtBottom = rolloutBody.scrollHeight - rolloutBody.scrollTop - rolloutBody.clientHeight < 30;
            if (rolloutData && rolloutData.connected && rolloutData.output) {
                rolloutBody.innerHTML = colorizeRolloutOutput(rolloutData.output);
            } else {
                rolloutBody.innerHTML = FALLBACK_ROLLOUT_TERMINAL;
            }
            if (wasAtBottom) autoScroll(rolloutBody);
        }

        const agentBody = document.getElementById('terminalAgentLogsBody');
        if (agentBody) {
            const wasAtBottom = agentBody.scrollHeight - agentBody.scrollTop - agentBody.clientHeight < 30;
            if (agentData && agentData.connected && agentData.output) {
                agentBody.innerHTML = colorizeAgentLogs(agentData.output);
                checkForNotifications(agentData.output);
            } else {
                agentBody.innerHTML = FALLBACK_AGENT_LOGS;
            }
            if (wasAtBottom) autoScroll(agentBody);
        }
    }).finally(() => {
        terminalFetchInFlight = false;
    });
}

function autoScroll(el) {
    el.scrollTop = el.scrollHeight;
}

function refreshTerminal(type) {
    const btn = event.currentTarget;
    btn.classList.add('spinning');
    setTimeout(() => btn.classList.remove('spinning'), 600);

    const endpoint = type === 'rollout' ? '/api/terminal/rollout' : '/api/terminal/agent-logs';
    const bodyId = type === 'rollout' ? 'terminalRolloutBody' : 'terminalAgentLogsBody';
    const colorizer = type === 'rollout' ? colorizeRolloutOutput : colorizeAgentLogs;
    const fallback = type === 'rollout' ? FALLBACK_ROLLOUT_TERMINAL : FALLBACK_AGENT_LOGS;

    fetch(endpoint)
        .then(r => r.json())
        .then(data => {
            const body = document.getElementById(bodyId);
            if (!body) return;
            if (data && data.connected && data.output) {
                body.innerHTML = colorizer(data.output);
                if (type === 'agent-logs') checkForNotifications(data.output);
            } else {
                body.innerHTML = fallback;
            }
            autoScroll(body);
        })
        .catch(() => {
            const body = document.getElementById(bodyId);
            if (body) body.innerHTML = fallback;
        });
}
window.refreshTerminal = refreshTerminal;

function updateConnectionStatus(connected) {
    const el = document.getElementById('connectionStatus');
    if (!el) return;
    if (connected) {
        el.className = 'connection-status';
        el.querySelector('span:last-child').textContent = 'Connected';
    } else {
        el.className = 'connection-status disconnected';
        el.querySelector('span:last-child').textContent = 'Disconnected';
    }
}

function updateSystemBar(rolloutData, versionMetrics) {
    const stableRate = Math.round(versionMetrics.stableSuccessRate);
    const canaryRate = Math.round(versionMetrics.canarySuccessRate);
    const totalReqs = versionMetrics.stableRequestCount + versionMetrics.canaryRequestCount;
    const canaryWeight = rolloutData.canaryWeight || 0;
    const phase = rolloutData.phase || 'Unknown';

    const sysStable = document.getElementById('sysStableRate');
    const sysCanary = document.getElementById('sysCanaryRate');
    const sysTotal = document.getElementById('sysTotalReqs');
    const sysPhase = document.getElementById('sysPhase');
    const sysTraffic = document.getElementById('sysTraffic');

    if (sysStable) {
        sysStable.textContent = stableRate + '%';
        sysStable.className = 'system-stat-value ' + rateColorClass(stableRate);
    }
    if (sysCanary) {
        sysCanary.textContent = canaryRate + '%';
        sysCanary.className = 'system-stat-value ' + rateColorClass(canaryRate);
    }
    if (sysTotal) sysTotal.textContent = formatNumber(totalReqs);
    if (sysPhase) sysPhase.textContent = phase;
    if (sysTraffic) sysTraffic.textContent = canaryWeight + '%';

    const footer = document.getElementById('footerTimestamp');
    if (footer) {
        footer.textContent = 'Last updated: ' + new Date().toLocaleTimeString('en-US', { hour12: false });
    }
}

function showDemoMode() {
    document.getElementById('rolloutStatusBadge').innerHTML =
        '<div class="status-badge progressing"><div class="status-dot"></div>Demo Mode</div>';

    const deploymentStatusValue = document.getElementById('deploymentStatusValue');
    if (deploymentStatusValue) deploymentStatusValue.textContent = 'Demo Mode';

    const rolloutMessage = document.getElementById('rolloutMessage');
    if (rolloutMessage) rolloutMessage.textContent = 'Connect to a Kubernetes cluster to see live data';
}

function updateRolloutProgress(rolloutData) {
    const phase = rolloutData.phase || 'Unknown';
    const canaryWeight = rolloutData.canaryWeight || 0;
    const currentStepIndex = rolloutData.currentStepIndex;

    const badge = document.getElementById('rolloutStatusBadge');
    const statusClass = phase.toLowerCase().replace(/\s+/g, '-');
    badge.innerHTML = '<div class="status-badge ' + statusClass + '">' +
        '<div class="status-dot"></div>' +
        phase +
        '</div>';

    const deploymentStatusValue = document.getElementById('deploymentStatusValue');
    if (deploymentStatusValue) deploymentStatusValue.textContent = phase;

    const rolloutMessage = document.getElementById('rolloutMessage');
    if (rolloutMessage) {
        let message = rolloutData.message || '';
        if (!message) {
            if (phase === 'Healthy' && canaryWeight === 100) message = 'Rollout completed successfully';
            else if (phase === 'Progressing') message = 'Rollout in progress';
            else if (phase === 'Paused') message = 'Rollout paused for analysis';
            else if (phase === 'Degraded') message = 'Rollout degraded — issues detected';
            else message = 'Monitoring deployment';
        }
        rolloutMessage.textContent = message;
    }

    const progress = document.getElementById('timelineProgress');
    progress.style.width = canaryWeight + '%';

    const stages = document.querySelectorAll('.stage');
    stages.forEach(stage => {
        const weight = parseInt(stage.dataset.weight);
        const stepAttr = stage.dataset.step;
        stage.classList.remove('active', 'completed', 'paused');

        let isCurrentStage = false;
        let isPastStage = false;
        if (currentStepIndex !== null && currentStepIndex !== undefined && stepAttr) {
            const steps = stepAttr.split(',').map(s => parseInt(s.trim()));
            isCurrentStage = steps.includes(currentStepIndex);
            isPastStage = steps.every(s => s < currentStepIndex);
        }

        if (isPastStage || weight < canaryWeight) {
            stage.classList.add('completed');
        } else if (isCurrentStage || (weight === canaryWeight && (currentStepIndex === null || currentStepIndex === undefined))) {
            stage.classList.add(phase === 'Paused' ? 'paused' : 'active');
        }
    });
}

function updateTrafficDistribution(rolloutData) {
    const stableWeight = rolloutData.stableWeight || 100;
    const canaryWeight = rolloutData.canaryWeight || 0;

    const stableSegment = document.getElementById('stableSegment');
    const canarySegment = document.getElementById('canarySegment');
    const stablePercentage = document.getElementById('stablePercentage');
    const canaryPercentage = document.getElementById('canaryPercentage');

    stableSegment.style.width = stableWeight + '%';
    canarySegment.style.width = canaryWeight + '%';
    stablePercentage.textContent = stableWeight + '%';
    canaryPercentage.textContent = canaryWeight + '%';

    stablePercentage.style.display = stableWeight < 15 ? 'none' : 'flex';
    canaryPercentage.style.display = canaryWeight < 15 ? 'none' : 'flex';
}

function updateAIAnalysis(rolloutData, metricsData, versionMetrics) {
    try {
        const aiIcon = document.getElementById('aiIcon');
        const aiStatusTitle = document.getElementById('aiStatusTitle');
        const aiStatusSubtitle = document.getElementById('aiStatusSubtitle');
        const aiDecision = document.getElementById('aiDecision');
        const aiDecisionTitle = document.getElementById('aiDecisionTitle');
        const aiDecisionMessage = document.getElementById('aiDecisionMessage');
        const errorLogContainer = document.getElementById('errorLogContainer');

        if (!aiIcon || !aiStatusTitle || !aiStatusSubtitle || !aiDecision || !aiDecisionTitle || !aiDecisionMessage || !errorLogContainer) return;

        const stableSuccessRate = Math.round(versionMetrics.stableSuccessRate);
        const canarySuccessRate = Math.round(versionMetrics.canarySuccessRate);
        const totalRequests = versionMetrics.stableRequestCount + versionMetrics.canaryRequestCount;

        const stableElement = document.getElementById('stableSuccessRate');
        const canaryElement = document.getElementById('canarySuccessRate');
        const requestsElement = document.getElementById('aiRequests');

        if (stableElement) stableElement.textContent = stableSuccessRate + '%';
        if (canaryElement) canaryElement.textContent = canarySuccessRate + '%';
        if (requestsElement) requestsElement.textContent = formatNumber(totalRequests);

        const analysis = rolloutData.analysis;

        if (analysis && analysis.phase && analysis.phase !== 'Pending' && analysis.phase !== 'NotStarted') {
            aiIcon.classList.remove('analyzing');
            aiDecision.classList.remove('success', 'failed');

            if (['Running', 'Progressing', 'InProgress'].includes(analysis.phase)) {
                aiIcon.classList.add('analyzing');
                aiIcon.textContent = '⏳';
                aiStatusTitle.textContent = 'Analysis Running';
                aiStatusSubtitle.textContent = 'AI is evaluating deployment metrics';
                aiDecisionTitle.textContent = 'Analyzing...';
                aiDecisionMessage.textContent = analysis.message || 'The AI agent is currently analyzing canary metrics to determine if the rollout should continue.';
                errorLogContainer.style.display = 'none';
            } else if (analysis.phase === 'Successful' || analysis.successful === true) {
                aiIcon.textContent = '✓';
                aiStatusTitle.textContent = 'Analysis Passed';
                aiStatusSubtitle.textContent = 'Metrics within acceptable thresholds';
                aiDecision.classList.add('success');
                aiDecisionTitle.textContent = 'Rollout Approved';
                aiDecisionMessage.textContent = analysis.message || 'AI analysis completed successfully. All metrics are healthy.';
                errorLogContainer.style.display = 'none';
                document.getElementById('canarySegment').classList.remove('degraded');
            } else if (['Failed', 'Degraded'].includes(analysis.phase) || analysis.successful === false) {
                aiIcon.textContent = '✗';
                aiStatusTitle.textContent = 'Analysis Failed';
                aiStatusSubtitle.textContent = 'Issues detected in deployment';
                aiDecision.classList.add('failed');
                aiDecisionTitle.textContent = 'Rollback Recommended';
                aiDecisionMessage.textContent = analysis.message || 'AI analysis detected issues. Rollback is recommended.';
                if (analysis.errorLog) {
                    errorLogContainer.style.display = 'block';
                    document.getElementById('errorLogText').textContent = analysis.errorLog;
                } else {
                    errorLogContainer.style.display = 'none';
                }
                document.getElementById('canarySegment').classList.add('degraded');
            } else if (analysis.phase === 'Error') {
                aiIcon.textContent = '⚠';
                aiStatusTitle.textContent = 'Analysis Error';
                aiStatusSubtitle.textContent = 'Error during analysis';
                aiDecision.classList.add('failed');
                aiDecisionTitle.textContent = 'Error';
                aiDecisionMessage.textContent = analysis.message || 'An error occurred during analysis.';
                if (analysis.errorLog) {
                    errorLogContainer.style.display = 'block';
                    document.getElementById('errorLogText').textContent = analysis.errorLog;
                } else {
                    errorLogContainer.style.display = 'none';
                }
            } else {
                aiIcon.textContent = '⊙';
                aiStatusTitle.textContent = 'Analysis: ' + analysis.phase;
                aiStatusSubtitle.textContent = 'Current status';
                aiDecisionTitle.textContent = analysis.phase;
                aiDecisionMessage.textContent = analysis.message || 'Analysis status: ' + analysis.phase;
                errorLogContainer.style.display = 'none';
            }
        } else {
            aiIcon.classList.remove('analyzing');
            aiIcon.textContent = '⊙';
            aiStatusTitle.textContent = 'AI Monitoring';
            aiStatusSubtitle.textContent = 'Waiting for analysis to start';
            aiDecision.classList.remove('success', 'failed');
            aiDecisionTitle.textContent = 'Standby';
            aiDecisionMessage.textContent = analysis && analysis.message ? analysis.message : 'The AI agent will analyze metrics once the rollout progresses and sufficient data is collected.';
            errorLogContainer.style.display = 'none';
        }

        const graphSuccessRate = canarySuccessRate > 0 ? canarySuccessRate : stableSuccessRate;
        updateSuccessRateGraph(graphSuccessRate);
    } catch (error) {
        console.error('Error in updateAIAnalysis:', error);
    }
}

function toggleErrorLog() {
    const content = document.getElementById('errorLogContent');
    const toggle = document.getElementById('errorLogToggle');
    content.classList.toggle('expanded');
    toggle.classList.toggle('expanded');
}

window.toggleErrorLog = toggleErrorLog;

const successRateHistory = [];
const maxHistoryPoints = 30;

function updateSuccessRateGraph(currentSuccessRate) {
    successRateHistory.push(currentSuccessRate);
    if (successRateHistory.length > maxHistoryPoints) successRateHistory.shift();

    const canvas = document.getElementById('successRateGraph');
    if (!canvas) return;

    const ctx = canvas.getContext('2d');
    const width = canvas.width;
    const height = canvas.height;

    ctx.clearRect(0, 0, width, height);

    if (successRateHistory.length < 2) return;

    // Grid lines
    ctx.strokeStyle = '#e2e8f0';
    ctx.lineWidth = 1;
    for (let i = 0; i <= 4; i++) {
        const y = (height / 4) * i;
        ctx.beginPath();
        ctx.moveTo(0, y);
        ctx.lineTo(width, y);
        ctx.stroke();
    }

    const pointSpacing = width / (maxHistoryPoints - 1);
    const lineColor = currentSuccessRate >= 95 ? '#16a34a' : currentSuccessRate >= 80 ? '#d97706' : '#dc2626';
    const fillColor = currentSuccessRate >= 95 ? 'rgba(22,163,74,0.1)' : currentSuccessRate >= 80 ? 'rgba(217,119,6,0.1)' : 'rgba(220,38,38,0.1)';

    // Fill area
    ctx.beginPath();
    successRateHistory.forEach((rate, index) => {
        const x = index * pointSpacing;
        const y = height - (rate / 100) * height;
        if (index === 0) ctx.moveTo(x, y);
        else ctx.lineTo(x, y);
    });
    ctx.lineTo((successRateHistory.length - 1) * pointSpacing, height);
    ctx.lineTo(0, height);
    ctx.closePath();
    ctx.fillStyle = fillColor;
    ctx.fill();

    // Line
    ctx.strokeStyle = lineColor;
    ctx.lineWidth = 2;
    ctx.beginPath();
    successRateHistory.forEach((rate, index) => {
        const x = index * pointSpacing;
        const y = height - (rate / 100) * height;
        if (index === 0) ctx.moveTo(x, y);
        else ctx.lineTo(x, y);
    });
    ctx.stroke();

    // Threshold
    ctx.strokeStyle = '#cbd5e1';
    ctx.lineWidth = 1;
    ctx.setLineDash([4, 4]);
    const thresholdY = height - (95 / 100) * height;
    ctx.beginPath();
    ctx.moveTo(0, thresholdY);
    ctx.lineTo(width, thresholdY);
    ctx.stroke();
    ctx.setLineDash([]);
}

let previousStableRequests = 0;
let previousCanaryRequests = 0;

function animateRequest(type) {
    const container = document.getElementById('requestVisualization');
    if (!container) return;

    const dot = document.createElement('div');
    dot.className = 'request-dot ' + type;
    dot.style.left = Math.random() * (container.offsetWidth - 10) + 'px';
    dot.style.bottom = '0px';

    container.appendChild(dot);
    setTimeout(() => { if (dot.parentNode) dot.parentNode.removeChild(dot); }, 5000);
}

function visualizeRealRequests(versionMetrics) {
    const stableRequests = versionMetrics.stableRequestCount || 0;
    const canaryRequests = versionMetrics.canaryRequestCount || 0;
    const canarySuccessRate = versionMetrics.canarySuccessRate || 100;

    const stableDelta = Math.max(0, stableRequests - previousStableRequests);
    const canaryDelta = Math.max(0, canaryRequests - previousCanaryRequests);
    const canarySuccessDelta = Math.round((canarySuccessRate / 100) * canaryDelta);
    const canaryErrorDelta = canaryDelta - canarySuccessDelta;

    for (let i = 0; i < Math.min(stableDelta, 20); i++)
        setTimeout(() => animateRequest('stable'), i * 50);
    for (let i = 0; i < Math.min(canarySuccessDelta, 20); i++)
        setTimeout(() => animateRequest('canary-success'), i * 50 + 10);
    for (let i = 0; i < Math.min(canaryErrorDelta, 20); i++)
        setTimeout(() => animateRequest('canary-error'), i * 50 + 20);

    previousStableRequests = stableRequests;
    previousCanaryRequests = canaryRequests;
}

function showError(message) {
    const errorContainer = document.getElementById('errorContainer');
    errorContainer.innerHTML = '<div class="error-message">' +
        '<div class="error-title">Connection Error</div>' +
        '<div>' + message + '</div>' +
        '</div>';
}

function clearError() {
    document.getElementById('errorContainer').innerHTML = '';
}

// ── Notifications ──
function checkForNotifications(logText) {
    const prPattern = /GitHub artifact created:\s*(https:\/\/github\.com\/[^\s]+\/pull\/\d+)/g;
    const issuePattern = /GitHub issue created:\s*(https:\/\/github\.com\/[^\s]+\/issues\/\d+)/g;
    const prPattern2 = /"prLink"\s*:\s*"(https:\/\/github\.com\/[^\s"]+\/pull\/\d+)"/g;

    let match;
    while ((match = prPattern.exec(logText)) !== null) {
        if (!seenNotifications.has(match[1])) {
            seenNotifications.add(match[1]);
            showNotification('Pull Request Created', 'AI Agent auto-generated a fix', match[1], 'pr');
        }
    }
    while ((match = issuePattern.exec(logText)) !== null) {
        if (!seenNotifications.has(match[1])) {
            seenNotifications.add(match[1]);
            showNotification('Issue Created', 'AI Agent reported a production issue', match[1], 'issue');
        }
    }
    while ((match = prPattern2.exec(logText)) !== null) {
        if (!seenNotifications.has(match[1])) {
            seenNotifications.add(match[1]);
            showNotification('Pull Request Created', 'AI Agent auto-generated a fix', match[1], 'pr');
        }
    }
}

function showNotification(title, message, url, type) {
    let container = document.getElementById('notificationContainer');
    if (!container) {
        container = document.createElement('div');
        container.id = 'notificationContainer';
        container.className = 'notification-container';
        document.body.appendChild(container);
    }

    const toast = document.createElement('div');
    toast.className = 'notification-toast ' + type;
    const icon = type === 'pr' ? '🔀' : '🐛';
    const label = url.match(/\/(pull|issues)\/(\d+)/);
    const linkText = label ? (type === 'pr' ? 'PR #' + label[2] : 'Issue #' + label[2]) : 'View';

    toast.innerHTML =
        '<div class="notification-icon">' + icon + '</div>' +
        '<div class="notification-content">' +
            '<div class="notification-title">' + title + '</div>' +
            '<div class="notification-message">' + message + '</div>' +
            '<a href="' + url + '" target="_blank" class="notification-link">' + linkText + ' →</a>' +
        '</div>' +
        '<button class="notification-close" onclick="this.parentElement.remove()">✕</button>';

    container.appendChild(toast);
    setTimeout(() => toast.classList.add('visible'), 10);
    setTimeout(() => {
        toast.classList.remove('visible');
        setTimeout(() => toast.remove(), 300);
    }, 15000);
}

// ── Init ──
function init() {
    updateClock();
    setInterval(updateClock, 1000);
    setInterval(updateUptime, 1000);

    updateDashboard();
    setInterval(updateDashboard, 2000);

    updateTerminals();
    setInterval(updateTerminals, 500);
}

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
} else {
    init();
}
