#!/usr/bin/env bash
# Sends a steady mix of customer API requests to the running app, so the Grafana dashboard has something to show:
# collections, card spends (some declined), retries that replay, and a few client mistakes.
#
# Usage: scripts/traffic.sh [seconds] [base URL]     defaults: 120 and http://localhost:8080
# Needs bash and curl; on Windows, run it from Git Bash. Ctrl+C stops it early.
set -euo pipefail

duration=${1:-120}
base=${2:-http://localhost:8080}
run=$RANDOM # keeps this run's Idempotency-Keys apart from earlier runs'

# POSTs an amount with an optional Idempotency-Key and prints the HTTP status.
post() { # path amount [key]
    local key_header=()
    if [[ -n ${3:-} ]]; then key_header=(-H "Idempotency-Key: $3"); fi
    curl -s -o /dev/null -w '%{http_code}' -X POST "$base$1" \
        -H 'Content-Type: application/json' "${key_header[@]}" \
        -d "{\"amount\":\"$2\",\"currency\":\"AUD\"}"
}

customer=$(curl -sf -X POST "$base/customers" -H 'Content-Type: application/json' -d '{"name":"Traffic Co"}' |
    grep -o '"id":[0-9]*' | cut -d: -f2)
echo "Customer $customer: sending requests to $base for $duration seconds"

declare -A statuses=()
count() { statuses[$1]=$((${statuses[$1]:-0} + 1)); }

end=$((SECONDS + duration))
i=0
while ((SECONDS < end)); do
    i=$((i + 1))
    key="traffic-$run-$i"

    # Spends outpace collections a little, so the balance runs low and some spends are declined.
    if ((i % 4 == 0)); then
        path="/customers/$customer/collections"
        amount="$((RANDOM % 200 + 50)).00"
    else
        path="/customers/$customer/card-spend"
        amount="$((RANDOM % 90 + 10)).$((RANDOM % 90 + 10))"
    fi
    count "$(post "$path" "$amount" "$key")"

    # A client retrying after a timeout: same key, same body, so a replay.
    if ((i % 5 == 0)); then count "$(post "$path" "$amount" "$key")"; fi
    # A client bug: the same key with a different amount (422 idempotency_key_reused).
    if ((i % 17 == 0)); then count "$(post "$path" "1.00" "$key")"; fi
    # Another: no key at all (400).
    if ((i % 23 == 0)); then count "$(post "$path" "1.00")"; fi

    sleep 0.1
done

echo "Sent $i transfers plus retries and mistakes. Responses by status:"
for status in "${!statuses[@]}"; do echo "  $status: ${statuses[$status]}"; done
