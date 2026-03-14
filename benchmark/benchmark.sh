#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ITERATIONS="${1:-5}"
IDLE_WAIT="${2:-60}"

RESULTS_DIR="$SCRIPT_DIR/results"
mkdir -p "$RESULTS_DIR"

JVM_COMPOSE="$SCRIPT_DIR/docker-compose-jvm.yml"
GRAALVM_COMPOSE="$SCRIPT_DIR/docker-compose-graalvm.yml"

cleanup_oap_only() {
  local compose_file=$1
  docker compose -f "$compose_file" stop oap 2>/dev/null || true
  docker compose -f "$compose_file" rm -f oap 2>/dev/null || true
}

cleanup_all() {
  local compose_file=$1
  docker compose -f "$compose_file" down -v 2>/dev/null || true
}

wait_for_banyandb() {
  echo "  Waiting for BanyanDB to be healthy..."
  local max_wait=120
  local elapsed=0
  until docker exec banyandb sh -c 'nc -nz 127.0.0.1 17912' 2>/dev/null; do
    sleep 1
    elapsed=$((elapsed + 1))
    if [ "$elapsed" -ge "$max_wait" ]; then
      echo "  ERROR: BanyanDB did not become healthy within ${max_wait}s"
      return 1
    fi
  done
  echo "  BanyanDB is ready."
}

# Wait for OAP to log "listening on 11800" — the real ready signal
wait_for_oap_ready() {
  local max_wait=180
  local elapsed=0
  echo "  Waiting for OAP gRPC server to be ready..."
  until docker logs oap 2>&1 | grep -q "listening on 11800"; do
    sleep 1
    elapsed=$((elapsed + 1))
    if [ "$elapsed" -ge "$max_wait" ]; then
      echo "  ERROR: OAP did not start within ${max_wait}s"
      docker logs oap 2>&1 | tail -10
      return 1
    fi
  done
}

millis_now() {
  perl -MTime::HiRes=time -e 'printf "%d\n", time()*1000'
}

# Extract boot time from OAP logs: first timestamp to "listening on 11800" timestamp
# Returns milliseconds
extract_boot_time_from_logs() {
  local logs
  logs=$(docker logs oap 2>&1)

  # JVM OAP format: "2026-03-14 06:10:28,351 ..."
  # GraalVM format: "2026-03-14 06:20:17,158 - ..."
  # Also possible: "2026-03-14T06:10:28.119575555Z ..."

  # Find the first application log timestamp (skip entrypoint script lines)
  local first_ts
  first_ts=$(echo "$logs" | grep -oE '^[0-9]{4}-[0-9]{2}-[0-9]{2}[T ][0-9]{2}:[0-9]{2}:[0-9]{2}[,\.][0-9]+' | head -1)

  # Find the "listening on 11800" timestamp
  local ready_ts
  ready_ts=$(echo "$logs" | grep "listening on 11800" | grep -oE '[0-9]{4}-[0-9]{2}-[0-9]{2}[T ][0-9]{2}:[0-9]{2}:[0-9]{2}[,\.][0-9]+' | head -1)

  if [ -z "$first_ts" ] || [ -z "$ready_ts" ]; then
    echo "0"
    return
  fi

  # Normalize timestamps: replace T with space, replace comma with dot
  first_ts=$(echo "$first_ts" | sed 's/T/ /; s/,/./')
  ready_ts=$(echo "$ready_ts" | sed 's/T/ /; s/,/./')

  # Calculate difference in milliseconds using perl
  perl -e '
    use POSIX qw(mktime);
    sub parse_ts {
      my $ts = shift;
      if ($ts =~ /(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})\.(\d+)/) {
        my $epoch = mktime($6, $5, $4, $3, $2-1, $1-1900);
        my $frac = substr($7 . "000", 0, 3);  # take first 3 digits as millis
        return $epoch * 1000 + $frac;
      }
      return 0;
    }
    my $start = parse_ts($ARGV[0]);
    my $end = parse_ts($ARGV[1]);
    print $end - $start . "\n";
  ' "$first_ts" "$ready_ts"
}

collect_memory() {
  local output_file=$1
  echo "  Collecting memory samples (5 samples, 10s apart)..."
  for s in $(seq 1 5); do
    docker stats oap --no-stream --format '{{.MemUsage}}' >> "$output_file"
    sleep 10
  done
}

save_oap_logs() {
  local label=$1
  local boot_type=$2
  local iteration=$3
  local logfile="$RESULTS_DIR/${label}_${boot_type}_logs_${iteration}.txt"
  docker logs oap > "$logfile" 2>&1 || true
}

run_benchmark() {
  local label=$1
  local compose_file=$2
  local cold_startup_file="$RESULTS_DIR/${label}_cold_startup.txt"
  local warm_startup_file="$RESULTS_DIR/${label}_warm_startup.txt"
  local cold_memory_file="$RESULTS_DIR/${label}_cold_memory.txt"
  local warm_memory_file="$RESULTS_DIR/${label}_warm_memory.txt"

  > "$cold_startup_file"
  > "$warm_startup_file"
  > "$cold_memory_file"
  > "$warm_memory_file"

  echo "=== Benchmarking: $label ($ITERATIONS iterations) ==="

  for i in $(seq 1 "$ITERATIONS"); do
    echo ""
    echo "--- Run $i/$ITERATIONS ---"

    # ---- COLD BOOT: fresh BanyanDB (container destroyed), tables will be created ----
    echo "[COLD BOOT] Starting fresh (tables will be created)..."
    cleanup_all "$compose_file"
    sleep 3

    docker compose -f "$compose_file" up -d banyandb
    wait_for_banyandb

    docker compose -f "$compose_file" up -d oap
    wait_for_oap_ready

    ms=$(extract_boot_time_from_logs)
    echo "  Cold startup (from logs): ${ms}ms"
    echo "$ms" >> "$cold_startup_file"
    save_oap_logs "$label" "cold" "$i"

    echo "  Waiting ${IDLE_WAIT}s for idle state..."
    sleep "$IDLE_WAIT"
    collect_memory "$cold_memory_file"

    # ---- WARM BOOT: stop OAP only, BanyanDB keeps data, restart OAP ----
    echo "[WARM BOOT] Restarting OAP only (tables already exist)..."
    cleanup_oap_only "$compose_file"
    sleep 3

    docker compose -f "$compose_file" up -d oap
    wait_for_oap_ready

    ms=$(extract_boot_time_from_logs)
    echo "  Warm startup (from logs): ${ms}ms"
    echo "$ms" >> "$warm_startup_file"
    save_oap_logs "$label" "warm" "$i"

    echo "  Waiting ${IDLE_WAIT}s for idle state..."
    sleep "$IDLE_WAIT"
    collect_memory "$warm_memory_file"

    cleanup_all "$compose_file"
    sleep 3
  done

  echo ""
  echo "=== $label complete ==="
  echo ""
}

print_summary() {
  local label=$1

  for boot_type in cold warm; do
    local startup_file="$RESULTS_DIR/${label}_${boot_type}_startup.txt"
    local memory_file="$RESULTS_DIR/${label}_${boot_type}_memory.txt"

    echo "--- $label ($boot_type boot) ---"
    echo "Startup times (ms):"
    sort -n "$startup_file" | while read -r ms; do echo "  $ms"; done

    local median
    median=$(sort -n "$startup_file" | awk '{a[NR]=$1} END{print a[int((NR+1)/2)]}')
    echo "  Median: ${median}ms"

    echo "Memory samples:"
    cat "$memory_file" | while read -r line; do echo "  $line"; done
    echo ""
  done
}

# Pull images first to exclude pull time from benchmark
echo "Pulling images..."
docker compose -f "$JVM_COMPOSE" pull
docker compose -f "$GRAALVM_COMPOSE" pull
echo ""

# Run benchmarks
run_benchmark "jvm" "$JVM_COMPOSE"
run_benchmark "graalvm" "$GRAALVM_COMPOSE"

# Summary
echo "========================================="
echo "          BENCHMARK SUMMARY"
echo "========================================="
echo ""
print_summary "jvm"
print_summary "graalvm"
