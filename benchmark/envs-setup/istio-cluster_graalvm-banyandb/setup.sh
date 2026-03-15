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

# Environment setup: OAP (GraalVM native) + BanyanDB + Istio ALS on Kind.
#
# Same as the JVM variant but uses the GraalVM native image distro.
# Runs a single OAP replica (standalone mode).
#
# The context file is written to: $REPORT_DIR/env-context.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

NAMESPACE="istio-system"
CLUSTER_NAME="benchmark-cluster"

# Load benchmark environment configuration (image repos, versions)
BENCHMARKS_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
source "$BENCHMARKS_DIR/env"

OAP_IMAGE_REPO="$SW_GRAALVM_IMAGE_REPO"
OAP_IMAGE_TAG="$SW_GRAALVM_IMAGE_TAG"

# Kind / K8s compatibility
KIND_MIN_VERSION="0.25.0"
HELM_MIN_VERSION="3.12.0"
K8S_NODE_MINOR="1.34"

log() { echo "[$(date +%H:%M:%S)] $*"; }

version_gte() {
    local IFS=.
    local i a=($1) b=($2)
    for ((i = 0; i < ${#b[@]}; i++)); do
        local va=${a[i]:-0}
        local vb=${b[i]:-0}
        if ((va > vb)); then return 0; fi
        if ((va < vb)); then return 1; fi
    done
    return 0
}

min_kind_for_k8s() {
    case "$1" in
        1.28) echo "0.25.0" ;;
        1.29) echo "0.25.0" ;;
        1.30) echo "0.27.0" ;;
        1.31) echo "0.25.0" ;;
        1.32) echo "0.26.0" ;;
        1.33) echo "0.27.0" ;;
        1.34) echo "0.30.0" ;;
        1.35) echo "0.31.0" ;;
        *)    echo "unknown" ;;
    esac
}

REPORT_DIR="${REPORT_DIR:?ERROR: REPORT_DIR must be set by the caller}"
mkdir -p "$REPORT_DIR"

#############################################################################
# Pre-checks
#############################################################################
log "=== Pre-checks ==="

for cmd in kind kubectl helm docker; do
    if ! command -v "$cmd" &>/dev/null; then
        echo "ERROR: $cmd not found. Please install it first."
        exit 1
    fi
done

KIND_VERSION=$(kind version | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
log "kind version: $KIND_VERSION (minimum: $KIND_MIN_VERSION)"
if ! version_gte "$KIND_VERSION" "$KIND_MIN_VERSION"; then
    echo "ERROR: kind >= $KIND_MIN_VERSION is required, found $KIND_VERSION"
    exit 1
fi

REQUIRED_KIND_FOR_NODE=$(min_kind_for_k8s "$K8S_NODE_MINOR")
if [ "$REQUIRED_KIND_FOR_NODE" = "unknown" ]; then
    echo "WARNING: No known kind compatibility data for K8s $K8S_NODE_MINOR. Proceeding anyway."
elif ! version_gte "$KIND_VERSION" "$REQUIRED_KIND_FOR_NODE"; then
    echo "ERROR: K8s $K8S_NODE_MINOR node image requires kind >= $REQUIRED_KIND_FOR_NODE, found $KIND_VERSION"
    exit 1
fi
log "kind $KIND_VERSION is compatible with K8s $K8S_NODE_MINOR node image."

KUBECTL_CLIENT_VERSION=$(kubectl version --client -o json 2>/dev/null \
    | grep -oE '"gitVersion":\s*"v([0-9]+\.[0-9]+)' | head -1 | grep -oE '[0-9]+\.[0-9]+')
if [ -n "$KUBECTL_CLIENT_VERSION" ]; then
    KUBECTL_MINOR=$(echo "$KUBECTL_CLIENT_VERSION" | cut -d. -f2)
    NODE_MINOR=$(echo "$K8S_NODE_MINOR" | cut -d. -f2)
    SKEW=$((KUBECTL_MINOR - NODE_MINOR))
    if [ "$SKEW" -lt 0 ]; then SKEW=$((-SKEW)); fi
    log "kubectl client: $KUBECTL_CLIENT_VERSION, K8s node: $K8S_NODE_MINOR (skew: $SKEW)"
    if [ "$SKEW" -gt 1 ]; then
        echo "ERROR: kubectl version $KUBECTL_CLIENT_VERSION is too far from K8s $K8S_NODE_MINOR (max ±1 minor)."
        exit 1
    fi
else
    echo "WARNING: Could not determine kubectl client version, skipping skew check."
fi

HELM_VERSION=$(helm version --short 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
log "Helm version: $HELM_VERSION (minimum: $HELM_MIN_VERSION)"
if ! version_gte "$HELM_VERSION" "$HELM_MIN_VERSION"; then
    echo "ERROR: Helm >= $HELM_MIN_VERSION is required, found $HELM_VERSION"
    exit 1
fi

ISTIOCTL_LOCAL_VERSION=$(istioctl version --remote=false 2>/dev/null | head -1 || echo "none")
if [ "$ISTIOCTL_LOCAL_VERSION" != "$ISTIO_VERSION" ]; then
    log "istioctl version mismatch: have $ISTIOCTL_LOCAL_VERSION, need $ISTIO_VERSION. Downloading..."
    ISTIO_DOWNLOAD_DIR="$BENCHMARKS_DIR/.istio"
    mkdir -p "$ISTIO_DOWNLOAD_DIR"
    if [ ! -f "$ISTIO_DOWNLOAD_DIR/istio-${ISTIO_VERSION}/bin/istioctl" ]; then
        (cd "$ISTIO_DOWNLOAD_DIR" && export ISTIO_VERSION && curl -sL https://istio.io/downloadIstio | sh -)
    fi
    if [ ! -f "$ISTIO_DOWNLOAD_DIR/istio-${ISTIO_VERSION}/bin/istioctl" ]; then
        echo "ERROR: Failed to download istioctl $ISTIO_VERSION"
        exit 1
    fi
    export PATH="$ISTIO_DOWNLOAD_DIR/istio-${ISTIO_VERSION}/bin:$PATH"
    ISTIOCTL_VERSION=$(istioctl version --remote=false 2>/dev/null | head -1 || echo "unknown")
    log "Using downloaded istioctl: $ISTIOCTL_VERSION"
else
    ISTIOCTL_VERSION="$ISTIOCTL_LOCAL_VERSION"
    log "istioctl version: $ISTIOCTL_VERSION"
fi

log "All version checks passed."

DOCKER_CPUS=$(docker info --format '{{.NCPU}}' 2>/dev/null || echo "unknown")
DOCKER_MEM_BYTES=$(docker info --format '{{.MemTotal}}' 2>/dev/null || echo "0")
if [ "$DOCKER_MEM_BYTES" -gt 0 ] 2>/dev/null; then
    DOCKER_MEM_GB=$(awk "BEGIN {printf \"%.1f\", $DOCKER_MEM_BYTES / 1073741824}")
else
    DOCKER_MEM_GB="unknown"
fi
DOCKER_SERVER_VERSION=$(docker info --format '{{.ServerVersion}}' 2>/dev/null || echo "unknown")
DOCKER_OS=$(docker info --format '{{.OperatingSystem}}' 2>/dev/null || echo "unknown")
DOCKER_STORAGE_DRIVER=$(docker info --format '{{.Driver}}' 2>/dev/null || echo "unknown")
log "Docker: ${DOCKER_CPUS} CPUs, ${DOCKER_MEM_GB} GB memory (server: ${DOCKER_SERVER_VERSION}, ${DOCKER_OS})"

if [ "$DOCKER_MEM_BYTES" -gt 0 ] 2>/dev/null; then
    MIN_MEM_BYTES=$((4 * 1073741824))
    if [ "$DOCKER_MEM_BYTES" -lt "$MIN_MEM_BYTES" ]; then
        echo "WARNING: Docker has only ${DOCKER_MEM_GB} GB memory. Recommend >= 4 GB for this benchmark."
    fi
fi

#############################################################################
# Boot Kind cluster
#############################################################################
log "=== Booting Kind cluster ==="

if kind get clusters 2>/dev/null | grep -q "^${CLUSTER_NAME}$"; then
    log "Kind cluster '$CLUSTER_NAME' already exists, reusing."
else
    log "Creating Kind cluster '$CLUSTER_NAME'..."
    kind create cluster --name "$CLUSTER_NAME" --config "$SCRIPT_DIR/kind.yaml"
fi

BANYANDB_IMAGE="${SW_BANYANDB_IMAGE_REPO}:${SW_BANYANDB_IMAGE_TAG}"
OAP_IMAGE="${OAP_IMAGE_REPO}:${OAP_IMAGE_TAG}"
UI_IMAGE="${SW_UI_IMAGE_REPO}:${SW_UI_IMAGE_TAG}"
IMAGES=(
    "$OAP_IMAGE"
    "$UI_IMAGE"
    "$BANYANDB_IMAGE"
    "docker.io/istio/pilot:${ISTIO_VERSION}"
    "docker.io/istio/proxyv2:${ISTIO_VERSION}"
    "docker.io/istio/examples-bookinfo-productpage-v1:1.20.2"
    "docker.io/istio/examples-bookinfo-details-v1:1.20.2"
    "docker.io/istio/examples-bookinfo-reviews-v1:1.20.2"
    "docker.io/istio/examples-bookinfo-reviews-v2:1.20.2"
    "docker.io/istio/examples-bookinfo-reviews-v3:1.20.2"
    "docker.io/istio/examples-bookinfo-ratings-v1:1.20.2"
    "docker.io/istio/examples-bookinfo-ratings-v2:1.20.2"
    "docker.io/istio/examples-bookinfo-mongodb:1.20.2"
    "curlimages/curl:latest"
    "registry.k8s.io/metrics-server/metrics-server:v0.8.1"
)
log "Pulling images on host (if not cached)..."
for img in "${IMAGES[@]}"; do
    pulled=false
    for attempt in 1 2 3; do
        if docker pull "$img" -q 2>/dev/null; then
            pulled=true
            break
        fi
        [ "$attempt" -lt 3 ] && sleep 5
    done
    if [ "$pulled" = false ]; then
        log "  WARNING: failed to pull $img after 3 attempts"
    fi
done

log "Loading images into Kind..."
for img in "${IMAGES[@]}"; do
    kind load docker-image "$img" --name "$CLUSTER_NAME" 2>/dev/null || log "  WARNING: failed to load $img"
done

#############################################################################
# Install Istio
#############################################################################
log "=== Installing Istio $ISTIO_VERSION ==="

istioctl install -y --set profile=demo \
    --set meshConfig.defaultConfig.envoyAccessLogService.address=skywalking-oap.${NAMESPACE}:11800 \
    --set meshConfig.enableEnvoyAccessLogService=true

kubectl label namespace default istio-injection=enabled --overwrite

#############################################################################
# Deploy SkyWalking via Helm (GraalVM: single replica, standalone)
#############################################################################
log "=== Deploying SkyWalking (GraalVM OAP x2 + BanyanDB + UI) via Helm ==="

helm -n "$NAMESPACE" upgrade --install skywalking \
    "$SW_HELM_CHART" \
    --version "0.0.0-${SW_KUBERNETES_COMMIT_SHA}" \
    --set fullnameOverride=skywalking \
    --set oap.replicas=2 \
    --set oap.image.repository="$OAP_IMAGE_REPO" \
    --set oap.image.tag="$OAP_IMAGE_TAG" \
    --set oap.storageType=banyandb \
    --set oap.env.SW_ENVOY_METRIC_ALS_HTTP_ANALYSIS="$ALS_ANALYZER" \
    --set oap.env.SW_ENVOY_METRIC_ALS_TCP_ANALYSIS="$ALS_ANALYZER" \
    --set oap.env.SW_HEALTH_CHECKER=default \
    --set oap.env.SW_TELEMETRY=prometheus \
    --set oap.envoy.als.enabled=true \
    --set ui.image.repository="$SW_UI_IMAGE_REPO" \
    --set ui.image.tag="$SW_UI_IMAGE_TAG" \
    --set elasticsearch.enabled=false \
    --set banyandb.enabled=true \
    --set banyandb.image.repository="$SW_BANYANDB_IMAGE_REPO" \
    --set banyandb.image.tag="$SW_BANYANDB_IMAGE_TAG" \
    --set banyandb.standalone.enabled=true \
    --timeout 1200s \
    -f "$SCRIPT_DIR/values.yaml"

log "Waiting for BanyanDB to be ready..."
kubectl -n "$NAMESPACE" wait --for=condition=ready pod -l app.kubernetes.io/name=banyandb --timeout=300s

log "Waiting for OAP init job to complete..."
for i in $(seq 1 60); do
    if kubectl -n "$NAMESPACE" get jobs -l component=skywalking-job -o jsonpath='{.items[0].status.succeeded}' 2>/dev/null | grep -q '1'; then
        log "OAP init job succeeded."
        break
    fi
    if [ "$i" -eq 60 ]; then
        echo "ERROR: OAP init job did not complete within 300s."
        kubectl -n "$NAMESPACE" get pods -l component=skywalking-job 2>/dev/null
        exit 1
    fi
    sleep 5
done

log "Waiting for OAP pods to be ready..."
kubectl -n "$NAMESPACE" wait --for=condition=ready pod -l app=skywalking,component=oap --timeout=300s

#############################################################################
# Capture K8s node resources
#############################################################################
log "Capturing node resource info..."
kubectl get nodes -o json | awk '
    BEGIN { print "--- K8s Node Resources ---" }
    /"capacity":/ { cap=1 } /"allocatable":/ { alloc=1 }
    cap && /"cpu":/ { gsub(/[",]/, ""); printf "  capacity.cpu:         %s\n", $2; cap=0 }
    cap && /"memory":/ { gsub(/[",]/, ""); printf "  capacity.memory:      %s\n", $2; cap=0 }
    cap && /"ephemeral-storage":/ { gsub(/[",]/, ""); printf "  capacity.storage:     %s\n", $2; cap=0 }
    cap && /"pods":/ { gsub(/[",]/, ""); printf "  capacity.pods:        %s\n", $2; cap=0 }
    alloc && /"cpu":/ { gsub(/[",]/, ""); printf "  allocatable.cpu:      %s\n", $2; alloc=0 }
    alloc && /"memory":/ { gsub(/[",]/, ""); printf "  allocatable.memory:   %s\n", $2; alloc=0 }
' > "$REPORT_DIR/node-resources.txt"
kubectl describe node | sed -n '/Allocated resources/,/Events/p' \
    >> "$REPORT_DIR/node-resources.txt" 2>/dev/null || true

#############################################################################
# Deploy Istio Bookinfo sample app
#############################################################################
log "=== Deploying Bookinfo sample app ==="

BOOKINFO_BASE="https://raw.githubusercontent.com/istio/istio/${ISTIO_VERSION}/samples/bookinfo"

kubectl apply -f "${BOOKINFO_BASE}/platform/kube/bookinfo.yaml"
kubectl apply -f "${BOOKINFO_BASE}/networking/bookinfo-gateway.yaml"
kubectl apply -f "${BOOKINFO_BASE}/platform/kube/bookinfo-ratings-v2.yaml"
kubectl apply -f "${BOOKINFO_BASE}/platform/kube/bookinfo-db.yaml"
kubectl apply -f "${BOOKINFO_BASE}/networking/destination-rule-all.yaml"
kubectl apply -f "${BOOKINFO_BASE}/networking/virtual-service-ratings-db.yaml"

log "Waiting for Bookinfo pods to be ready..."
kubectl -n default wait --for=condition=ready pod -l app=productpage --timeout=300s
kubectl -n default wait --for=condition=ready pod -l app=details --timeout=300s
kubectl -n default wait --for=condition=ready pod -l app=ratings,version=v1 --timeout=300s
kubectl -n default wait --for=condition=ready pod -l app=reviews,version=v1 --timeout=300s
kubectl -n default wait --for=condition=ready pod -l app=reviews,version=v2 --timeout=300s

log "Deploying traffic generator..."
kubectl apply -f "$SCRIPT_DIR/traffic-gen.yaml"
kubectl -n default wait --for=condition=ready pod -l app=traffic-gen --timeout=60s

#############################################################################
# Cluster health check
#############################################################################
log "=== Cluster health check (remote_out_count) ==="
log "Waiting 30s for traffic to flow..."
sleep 30

OAP_PODS_CHECK=($(kubectl -n "$NAMESPACE" get pods -l app=skywalking,component=oap -o jsonpath='{.items[*].metadata.name}'))
EXPECTED_NODES=${#OAP_PODS_CHECK[@]}
CLUSTER_HEALTHY=true

CURL_IMAGE="curlimages/curl:latest"
for pod in "${OAP_PODS_CHECK[@]}"; do
    log "  Checking $pod..."
    POD_IP=$(kubectl -n "$NAMESPACE" get pod "$pod" -o jsonpath='{.status.podIP}')
    METRICS=$(kubectl -n "$NAMESPACE" run "health-check-${pod##*-}" --rm -i --restart=Never \
        --image="$CURL_IMAGE" -- curl -s "http://${POD_IP}:1234/metrics" 2>/dev/null) || METRICS=""
    REMOTE_OUT=$(echo "$METRICS" | grep '^remote_out_count{' || true)

    if [ -z "$REMOTE_OUT" ]; then
        REMOTE_IN=$(echo "$METRICS" | grep '^remote_in_count{' || true)
        if [ -n "$REMOTE_IN" ]; then
            log "    $pod: no remote_out_count but has remote_in_count (receiver-only node)"
        else
            log "    WARNING: $pod has no remote_out_count or remote_in_count"
            CLUSTER_HEALTHY=false
        fi
    else
        DEST_COUNT=$(echo "$REMOTE_OUT" | wc -l | tr -d ' ')
        log "    $pod: $DEST_COUNT dest(s)"
    fi
done

if [ "$CLUSTER_HEALTHY" = true ]; then
    log "Cluster health check passed."
else
    log "WARNING: Cluster health check has issues. Proceeding anyway."
fi

#############################################################################
# Port-forward OAP for local queries
#############################################################################
log "Setting up port-forwards..."
kubectl -n "$NAMESPACE" port-forward svc/skywalking-oap 12800:12800 &
SETUP_BG_PIDS=($!)
kubectl -n "$NAMESPACE" port-forward svc/skywalking-ui 8080:80 &
SETUP_BG_PIDS+=($!)
sleep 3

log "Environment is up. OAP at localhost:12800, UI at localhost:8080"

#############################################################################
# Write context file
#############################################################################
CONTEXT_FILE="$REPORT_DIR/env-context.sh"
cat > "$CONTEXT_FILE" <<EOF
# Auto-generated by envs-setup/istio-cluster_graalvm-banyandb/setup.sh
export ENV_NAME="istio-cluster_graalvm-banyandb"
export OAP_VARIANT="graalvm"
export NAMESPACE="$NAMESPACE"
export CLUSTER_NAME="$CLUSTER_NAME"
export OAP_HOST="localhost"
export OAP_PORT="12800"
export OAP_GRPC_PORT="11800"
export UI_HOST="localhost"
export UI_PORT="8080"
export OAP_SELECTOR="app=skywalking,component=oap"
export REPORT_DIR="$REPORT_DIR"
export ISTIO_VERSION="$ISTIO_VERSION"
export ALS_ANALYZER="$ALS_ANALYZER"
export DOCKER_CPUS="$DOCKER_CPUS"
export DOCKER_MEM_GB="$DOCKER_MEM_GB"
export DOCKER_SERVER_VERSION="$DOCKER_SERVER_VERSION"
export DOCKER_OS="$DOCKER_OS"
export DOCKER_STORAGE_DRIVER="$DOCKER_STORAGE_DRIVER"
export KIND_VERSION="$KIND_VERSION"
export KUBECTL_CLIENT_VERSION="${KUBECTL_CLIENT_VERSION:-unknown}"
export HELM_VERSION="$HELM_VERSION"
export ISTIOCTL_VERSION="$ISTIOCTL_VERSION"
export K8S_NODE_MINOR="$K8S_NODE_MINOR"
export SETUP_BG_PIDS="${SETUP_BG_PIDS[*]}"
EOF

log "Context written to: $CONTEXT_FILE"
log "Environment setup complete."
