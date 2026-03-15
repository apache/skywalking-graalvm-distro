#!/usr/bin/env bash
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

# Benchmark case: Resource usage comparison (CPU & memory).
#
# Collects CPU and memory usage from OAP pods at regular intervals
# under sustained traffic. Designed to compare JVM vs GraalVM OAP.
#
# Usage:
#   ./run.sh <env-context-file>

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

if [ $# -lt 1 ] || [ ! -f "$1" ]; then
    echo "Usage: $0 <env-context-file>"
    exit 1
fi

source "$1"

# Configurable via env vars
SAMPLE_COUNT="${SAMPLE_COUNT:-30}"
SAMPLE_INTERVAL="${SAMPLE_INTERVAL:-10}"
WARMUP_SECONDS="${WARMUP_SECONDS:-60}"

log() { echo "[$(date +%H:%M:%S)] $*"; }

cleanup_pids() {
    for pid in "${BG_PIDS[@]:-}"; do
        kill "$pid" 2>/dev/null || true
    done
}
trap cleanup_pids EXIT
BG_PIDS=()

log "=== Resource Usage Benchmark ==="
log "Environment: $ENV_NAME"
log "OAP variant: ${OAP_VARIANT:-unknown}"
log "OAP: ${OAP_HOST}:${OAP_PORT}"
log "Namespace: $NAMESPACE"
log "Config: $SAMPLE_COUNT samples, ${SAMPLE_INTERVAL}s apart, ${WARMUP_SECONDS}s warmup"
log "Report dir: $REPORT_DIR"

#############################################################################
# Install metrics-server in Kind (for kubectl top)
#############################################################################
log "--- Ensuring metrics-server is available ---"

if ! kubectl top pods -n "$NAMESPACE" &>/dev/null 2>&1; then
    log "Installing metrics-server..."
    kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml 2>/dev/null || true
    # Patch for Kind (no TLS verification needed for kubelet)
    kubectl -n kube-system patch deployment metrics-server \
        --type=json \
        -p='[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]' 2>/dev/null || true
    log "Waiting for metrics-server to be ready..."
    kubectl -n kube-system wait --for=condition=ready pod -l k8s-app=metrics-server --timeout=120s 2>/dev/null || true
    # Give metrics-server time to collect initial data
    log "Waiting 60s for metrics-server to collect data..."
    sleep 60
fi

#############################################################################
# Metrics monitor (background) — queries all services, multiple metrics
#############################################################################
log "--- Starting metrics monitor (every 30s, all services) ---"

OAP_BASE_URL="http://${OAP_HOST}:${OAP_PORT}/graphql"

# Metrics to collect per service
SWCTL_METRICS=(service_cpm service_resp_time service_sla service_apdex service_percentile)

if command -v swctl &>/dev/null; then
    metrics_monitor() {
        local round=0
        while true; do
            round=$((round + 1))
            local out="$REPORT_DIR/metrics-round-${round}.yaml"
            {
                echo "--- round: $round  time: $(date -u +%Y-%m-%dT%H:%M:%SZ) ---"
                echo ""

                # Discover all services
                echo "# services"
                local svc_yaml
                svc_yaml=$(swctl --display yaml --base-url="$OAP_BASE_URL" service ls 2>/dev/null || echo "ERROR")
                echo "$svc_yaml"
                echo ""

                # Extract all service names
                local svc_names
                svc_names=$(echo "$svc_yaml" | grep '  name:' | sed 's/.*name: //')

                # Query each metric for each service
                while IFS= read -r svc; do
                    [ -z "$svc" ] && continue
                    for metric in "${SWCTL_METRICS[@]}"; do
                        echo "# ${metric} (${svc})"
                        swctl --display yaml --base-url="$OAP_BASE_URL" \
                            metrics exec --expression="$metric" --service-name="$svc" 2>/dev/null || echo "ERROR"
                        echo ""
                    done
                done <<< "$svc_names"
            } > "$out" 2>&1
            sleep 30
        done
    }
    metrics_monitor &
    BG_PIDS+=($!)
else
    log "WARNING: swctl not found, skipping service metrics collection."
fi

#############################################################################
# Warmup
#############################################################################
log "--- Warming up for ${WARMUP_SECONDS}s ---"
sleep "$WARMUP_SECONDS"

#############################################################################
# Resource usage collection
#############################################################################
log "--- Collecting $SAMPLE_COUNT resource samples (${SAMPLE_INTERVAL}s apart) ---"

OAP_PODS=($(kubectl -n "$NAMESPACE" get pods -l "$OAP_SELECTOR" -o jsonpath='{.items[*].metadata.name}'))
log "OAP pods: ${OAP_PODS[*]}"

RESOURCE_FILE="$REPORT_DIR/resource-usage.csv"
echo "timestamp,pod,cpu_millicores,memory_mib" > "$RESOURCE_FILE"

for i in $(seq 1 "$SAMPLE_COUNT"); do
    TS=$(date -u +%Y-%m-%dT%H:%M:%SZ)

    # kubectl top pods
    TOP_OUTPUT=$(kubectl -n "$NAMESPACE" top pods -l "$OAP_SELECTOR" --no-headers 2>/dev/null || true)

    if [ -n "$TOP_OUTPUT" ]; then
        while IFS= read -r line; do
            pod=$(echo "$line" | awk '{print $1}')
            cpu_raw=$(echo "$line" | awk '{print $2}')
            mem_raw=$(echo "$line" | awk '{print $3}')

            # Parse CPU: "123m" → 123, "1" → 1000
            if echo "$cpu_raw" | grep -q 'm$'; then
                cpu_m=$(echo "$cpu_raw" | sed 's/m$//')
            else
                cpu_m=$((cpu_raw * 1000))
            fi

            # Parse memory: "123Mi" → 123, "1Gi" → 1024
            if echo "$mem_raw" | grep -q 'Gi$'; then
                mem_mi=$(echo "$mem_raw" | sed 's/Gi$//' | awk '{printf "%d", $1 * 1024}')
            elif echo "$mem_raw" | grep -q 'Mi$'; then
                mem_mi=$(echo "$mem_raw" | sed 's/Mi$//')
            else
                mem_mi="$mem_raw"
            fi

            echo "$TS,$pod,$cpu_m,$mem_mi" >> "$RESOURCE_FILE"
        done <<< "$TOP_OUTPUT"
        log "  sample $i/$SAMPLE_COUNT: collected"
    else
        log "  sample $i/$SAMPLE_COUNT: kubectl top failed (metrics may not be ready)"
    fi

    if [ "$i" -lt "$SAMPLE_COUNT" ]; then
        sleep "$SAMPLE_INTERVAL"
    fi
done

#############################################################################
# CPM validation — entry service CPM should be close to RPS×60 (=1200)
#############################################################################
log "--- Validating entry service CPM ---"

EXPECTED_CPM=720
CPM_TOLERANCE=0.3  # 30% tolerance
ENTRY_SERVICE="productpage.default"

if command -v swctl &>/dev/null; then
    # Find the entry service (productpage) — the one receiving external traffic
    ENTRY_SVC=""
    ALL_SVCS=$(swctl --display yaml --base-url="$OAP_BASE_URL" service ls 2>/dev/null \
        | grep '  name:' | sed 's/.*name: //' || echo "")
    while IFS= read -r svc; do
        if echo "$svc" | grep -q "^${ENTRY_SERVICE}"; then
            ENTRY_SVC="$svc"
            break
        fi
    done <<< "$ALL_SVCS"

    if [ -z "$ENTRY_SVC" ]; then
        log "  Entry service '$ENTRY_SERVICE' not found. Available: $(echo "$ALL_SVCS" | tr '\n' ', ')"
        ACTUAL_CPM=0
        CPM_STATUS="SKIPPED"
    else
        CPM_OUTPUT=$(swctl --display yaml --base-url="$OAP_BASE_URL" metrics exec \
            --expression=service_cpm --service-name="$ENTRY_SVC" 2>/dev/null || echo "")

        # Extract all non-null CPM values (minute-level time series).
        # Skip the last 2 values — the most recent minutes may be
        # incomplete (metrics not yet flushed/persisted by OAP).
        # Average the remaining stable values for validation.
        CPM_VALUES=$(echo "$CPM_OUTPUT" | grep 'value:' | grep -v 'null' \
            | grep -oE '[0-9]+' || true)
        CPM_COUNT=$(echo "$CPM_VALUES" | grep -c . || echo "0")

        if [ "$CPM_COUNT" -gt 2 ]; then
            # Drop last 2 (potentially incomplete), average the rest
            STABLE_VALUES=$(echo "$CPM_VALUES" | head -n $((CPM_COUNT - 2)))
            ACTUAL_CPM=$(echo "$STABLE_VALUES" | awk '{s+=$1; n++} END {printf "%d", s/n}')
            CPM_MIN=$(echo "$STABLE_VALUES" | sort -n | head -1)
            CPM_MAX=$(echo "$STABLE_VALUES" | sort -n | tail -1)
            STABLE_COUNT=$(echo "$STABLE_VALUES" | wc -l | tr -d ' ')

            CPM_LOW=$(awk "BEGIN {printf \"%d\", $EXPECTED_CPM * (1 - $CPM_TOLERANCE)}")
            CPM_HIGH=$(awk "BEGIN {printf \"%d\", $EXPECTED_CPM * (1 + $CPM_TOLERANCE)}")
            if [ "$ACTUAL_CPM" -ge "$CPM_LOW" ] && [ "$ACTUAL_CPM" -le "$CPM_HIGH" ]; then
                log "  CPM PASSED: $ENTRY_SVC avg=$ACTUAL_CPM min=$CPM_MIN max=$CPM_MAX (${STABLE_COUNT} stable minutes, expected ~$EXPECTED_CPM, range $CPM_LOW-$CPM_HIGH)"
                CPM_STATUS="PASS"
            else
                log "  CPM WARNING: $ENTRY_SVC avg=$ACTUAL_CPM min=$CPM_MIN max=$CPM_MAX (${STABLE_COUNT} stable minutes, expected ~$EXPECTED_CPM, range $CPM_LOW-$CPM_HIGH)"
                CPM_STATUS="WARNING"
            fi
        elif [ "$CPM_COUNT" -gt 0 ]; then
            ACTUAL_CPM=$(echo "$CPM_VALUES" | awk '{s+=$1; n++} END {printf "%d", s/n}')
            log "  CPM WARNING: only $CPM_COUNT data points for $ENTRY_SVC, avg=$ACTUAL_CPM (too few for reliable validation)"
            CPM_STATUS="WARNING"
        else
            log "  CPM SKIPPED: no CPM data for $ENTRY_SVC"
            ACTUAL_CPM=0
            CPM_STATUS="SKIPPED"
        fi
    fi

    # Also collect final snapshot of all service metrics for the report
    log "--- Collecting final metrics snapshot (all services) ---"
    METRICS_SNAPSHOT="$REPORT_DIR/metrics-final.yaml"
    {
        echo "--- Final metrics snapshot: $(date -u +%Y-%m-%dT%H:%M:%SZ) ---"
        echo ""
        while IFS= read -r svc; do
            [ -z "$svc" ] && continue
            for metric in "${SWCTL_METRICS[@]}"; do
                echo "# ${metric} (${svc})"
                swctl --display yaml --base-url="$OAP_BASE_URL" \
                    metrics exec --expression="$metric" --service-name="$svc" 2>/dev/null || echo "ERROR"
                echo ""
            done
        done <<< "$ALL_SVCS"
    } > "$METRICS_SNAPSHOT" 2>&1
else
    log "  CPM validation SKIPPED: swctl not available"
    ACTUAL_CPM=0
    CPM_STATUS="SKIPPED"
fi

#############################################################################
# Analysis
#############################################################################
log "--- Generating resource usage report ---"

ANALYSIS_FILE="$REPORT_DIR/resource-analysis.txt"
{
    echo "================================================================"
    echo "  OAP Resource Usage Report"
    echo "  Generated: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "  OAP variant: ${OAP_VARIANT:-unknown}"
    echo "  Samples: $SAMPLE_COUNT x ${SAMPLE_INTERVAL}s apart"
    echo "  Warmup: ${WARMUP_SECONDS}s"
    echo "  Traffic rate: ~12 RPS"
    echo "  OAP pods: ${OAP_PODS[*]}"
    echo "================================================================"
    echo ""

    DATA_LINES=$(tail -n +2 "$RESOURCE_FILE" | wc -l | tr -d ' ')
    if [ "$DATA_LINES" -eq 0 ]; then
        echo "No resource data collected. Ensure metrics-server is running."
    else
        echo "--- Per-Pod Summary ---"
        echo ""
        for pod in "${OAP_PODS[@]}"; do
            echo "Pod: $pod"
            pod_data=$(grep ",$pod," "$RESOURCE_FILE" || true)
            if [ -z "$pod_data" ]; then
                echo "  No data collected."
                echo ""
                continue
            fi

            cpu_values=$(echo "$pod_data" | awk -F',' '{print $3}')
            mem_values=$(echo "$pod_data" | awk -F',' '{print $4}')

            cpu_min=$(echo "$cpu_values" | sort -n | head -1)
            cpu_max=$(echo "$cpu_values" | sort -n | tail -1)
            cpu_avg=$(echo "$cpu_values" | awk '{s+=$1; n++} END {printf "%d", s/n}')
            cpu_median=$(echo "$cpu_values" | sort -n | awk '{a[NR]=$1} END{print a[int((NR+1)/2)]}')

            mem_min=$(echo "$mem_values" | sort -n | head -1)
            mem_max=$(echo "$mem_values" | sort -n | tail -1)
            mem_avg=$(echo "$mem_values" | awk '{s+=$1; n++} END {printf "%d", s/n}')
            mem_median=$(echo "$mem_values" | sort -n | awk '{a[NR]=$1} END{print a[int((NR+1)/2)]}')

            printf "  CPU (millicores):  min=%-6s max=%-6s avg=%-6s median=%-6s\n" "$cpu_min" "$cpu_max" "$cpu_avg" "$cpu_median"
            printf "  Memory (MiB):      min=%-6s max=%-6s avg=%-6s median=%-6s\n" "$mem_min" "$mem_max" "$mem_avg" "$mem_median"
            echo ""
        done

        echo "--- Aggregate (all OAP pods) ---"
        echo ""
        all_cpu=$(tail -n +2 "$RESOURCE_FILE" | awk -F',' '{print $3}')
        all_mem=$(tail -n +2 "$RESOURCE_FILE" | awk -F',' '{print $4}')

        agg_cpu_avg=$(echo "$all_cpu" | awk '{s+=$1; n++} END {printf "%d", s/n}')
        agg_cpu_median=$(echo "$all_cpu" | sort -n | awk '{a[NR]=$1} END{print a[int((NR+1)/2)]}')
        agg_mem_avg=$(echo "$all_mem" | awk '{s+=$1; n++} END {printf "%d", s/n}')
        agg_mem_median=$(echo "$all_mem" | sort -n | awk '{a[NR]=$1} END{print a[int((NR+1)/2)]}')

        printf "  CPU (millicores):  avg=%-6s median=%-6s\n" "$agg_cpu_avg" "$agg_cpu_median"
        printf "  Memory (MiB):      avg=%-6s median=%-6s\n" "$agg_mem_avg" "$agg_mem_median"
        echo ""
    fi

    echo "--- CPM Validation (entry service: ${ENTRY_SERVICE}) ---"
    echo ""
    echo "  Traffic rate:      ~12 RPS"
    echo "  Expected CPM:      ~$EXPECTED_CPM (12 × 60)"
    echo "  Entry service:     ${ENTRY_SVC:-not found}"
    echo "  Stable minutes:    ${STABLE_COUNT:-N/A} (last 2 dropped as potentially incomplete)"
    echo "  CPM avg:           ${ACTUAL_CPM:-N/A}"
    echo "  CPM range:         ${CPM_MIN:-N/A} – ${CPM_MAX:-N/A}"
    echo "  Status:            ${CPM_STATUS:-SKIPPED}"
    echo ""
    if [ "${CPM_STATUS:-SKIPPED}" = "WARNING" ]; then
        echo "  NOTE: CPM mismatch may indicate dropped requests or processing delays."
    fi
    echo ""
    echo "--- Metrics Collected ---"
    echo ""
    echo "  Monitor frequency: every 30s (background)"
    echo "  Metrics per service: ${SWCTL_METRICS[*]}"
    echo "  Final snapshot:    metrics-final.yaml"
    echo ""
} > "$ANALYSIS_FILE"

#############################################################################
# Environment summary
#############################################################################
ENV_REPORT="$REPORT_DIR/environment.txt"
{
    echo "================================================================"
    echo "  Benchmark Report: Resource Usage"
    echo "  Environment: $ENV_NAME"
    echo "  OAP variant: ${OAP_VARIANT:-unknown}"
    echo "  Generated: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "================================================================"
    echo ""
    echo "--- Host ---"
    echo "  OS:       $(uname -s) $(uname -r)"
    echo "  Arch:     $(uname -m)"
    echo ""
    echo "--- Docker ---"
    echo "  Server:   $DOCKER_SERVER_VERSION"
    echo "  OS:       $DOCKER_OS"
    echo "  Driver:   $DOCKER_STORAGE_DRIVER"
    echo "  CPUs:     $DOCKER_CPUS"
    echo "  Memory:   ${DOCKER_MEM_GB} GB"
    echo ""
    echo "--- Benchmark Config ---"
    echo "  OAP variant:      ${OAP_VARIANT:-unknown}"
    echo "  OAP replicas:     ${#OAP_PODS[@]}"
    echo "  Storage:          BanyanDB (standalone)"
    echo "  Istio:            ${ISTIO_VERSION:-N/A}"
    echo "  ALS analyzer:     ${ALS_ANALYZER:-N/A}"
    echo "  Traffic rate:     ~12 RPS"
    echo "  Samples:          $SAMPLE_COUNT x ${SAMPLE_INTERVAL}s"
    echo "  Warmup:           ${WARMUP_SECONDS}s"
    echo ""
    echo "--- Pod Status (at completion) ---"
    kubectl -n "$NAMESPACE" get pods -o wide 2>/dev/null || echo "  (could not query)"
    echo ""
    echo "--- K8s Node Resources ---"
    if [ -f "$REPORT_DIR/node-resources.txt" ]; then
        cat "$REPORT_DIR/node-resources.txt"
    else
        echo "  (not captured)"
    fi
    echo ""
} > "$ENV_REPORT"

cleanup_pids
BG_PIDS=()

log "=== Resource usage benchmark complete ==="
log "Reports in: $REPORT_DIR"
log "  resource-usage.csv     - Raw CSV (timestamp, pod, cpu_m, mem_mib)"
log "  resource-analysis.txt  - Summary statistics"
log "  environment.txt        - Environment details"
log "  metrics-round-*.yaml   - Periodic swctl metrics (all services, every 30s)"
log "  metrics-final.yaml     - Final snapshot (all services × all metrics)"
log "Done. Environment is still running."
