#!/usr/bin/env bash
# Blue-green for the chat on Kubernetes, with kubectl only: the apps never touch the Kubernetes API.
#   blue-green.sh deploy <color> [version]   scale the color's StatefulSet to 3 pods running the version
#                                            (default: one past the newest) and wait until each is warmed
#                                            up: an Up member of its color's cluster (readiness)
#   blue-green.sh activate <color>           make it the active generation: POST /actuator/generation, a
#                                            compare-and-set on the epoch, from inside one of its pods so
#                                            the deployer password never leaves them
#   blue-green.sh verify <color>             wait until every running pod of both colors reads it
#   blue-green.sh retire <color>             scale the color to 0 (refused while it is active)
#   blue-green.sh status                     the active generation and each color's pods
# Activating the old color again, before it is retired, rolls back. A retry of the same activation is
# answered with the first result (the operation ID names it); a competing one fails with 409.
set -euo pipefail

NS=${NAMESPACE:-sharded-chat}
NODES=${NODES:-3}
TIMEOUT=${TIMEOUT:-300}

usage() { sed -n '2,13p' "$0" >&2; exit 2; }
color_arg() {
    case "${1:-}" in blue|green) echo "$1" ;; *) usage ;; esac
}
# CONTEXT picks the cluster (bin/k8s-up sets it); else kubectl's current context.
k() { kubectl ${CONTEXT:+--context "$CONTEXT"} -n "$NS" "$@"; }

# Runs curl inside pod $1 against its /actuator/generation as the deployer (credentials from the pod's env).
generation() {
    pod=$1; shift
    k exec "$pod" -c chat -- sh -c \
        'curl -s -u "deployer:$SHARDED_CHAT_DEPLOYER_PASSWORD" "$@" localhost:8080/actuator/generation' sh "$@"
}

running_pods() {
    k get pods -l "app=sharded-chat${1:+,color=$1}" -o json | jq -r \
        '.items[] | select(.status.phase == "Running" and .metadata.deletionTimestamp == null) | .metadata.name'
}

any_pod() {
    pod=$(running_pods "$1" | head -n 1)
    [ -n "$pod" ] || { echo "no running $1 pod" >&2; exit 1; }
    echo "$pod"
}

version_of() {
    k get statefulset "chat-$1" -o jsonpath='{.spec.template.metadata.labels.version}'
}

next_version() {
    newest=0
    for c in blue green; do
        n=$(version_of "$c" | tr -dc '0-9'); newest=$(( ${n:-0} > newest ? ${n:-0} : newest ))
    done
    # A color that never ran has no release yet: blue's first deploy is v1.
    running=$(running_pods | wc -l | tr -d ' ')
    [ "$running" = 0 ] && echo "v1" || echo "v$(( newest + 1 ))"
}

deploy() {
    color=$(color_arg "${1:-}"); version=${2:-$(next_version)}
    echo "deploy $color $version: $NODES pods"
    k patch statefulset "chat-$color" --type=strategic -p "{
      \"spec\": {\"replicas\": $NODES, \"template\": {
        \"metadata\": {\"labels\": {\"version\": \"$version\"}},
        \"spec\": {\"containers\": [{\"name\": \"chat\", \"env\": [{\"name\": \"SHARDED_CHAT_VERSION\", \"value\": \"$version\"}]}]}}}}"
    k rollout status statefulset "chat-$color" --timeout="${TIMEOUT}s"
    echo "$color $version: $NODES pods warmed up"
}

activate() {
    color=$(color_arg "${1:-}"); pod=$(any_pod "$color")
    epoch=${EXPECTED_EPOCH:-$(generation "$pod" | jq '.active.epoch // 0')}
    operation=${OPERATION_ID:-activate-$color-$(version_of "$color")-from-$epoch}
    reply=$(generation "$pod" -X POST -H 'Content-Type: application/json' -w '\n%{http_code}' \
        -d "{\"color\":\"$color\",\"expectedEpoch\":$epoch,\"operationId\":\"$operation\"}")
    if [ "$(tail -n 1 <<< "$reply")" != 200 ]; then
        echo "activating $color (expected epoch $epoch, operation $operation) was refused:" >&2
        sed '$d' <<< "$reply" >&2
        exit 1
    fi
    sed '$d' <<< "$reply" | jq -c '{outcome, generation: {color: .generation.color, epoch: .generation.epoch}}'
}

verify() {
    color=$(color_arg "${1:-}"); deadline=$(( $(date +%s) + TIMEOUT ))
    epoch=$(generation "$(any_pod "$color")" | jq '.active.epoch')
    while :; do
        behind=""
        for pod in $(running_pods); do
            reads=$(generation "$pod" | jq -r '"\(.node.active.color) \(.node.active.epoch)"' 2>/dev/null || echo "?")
            [ "$reads" = "$color $epoch" ] || behind="$behind $pod($reads)"
        done
        [ -z "$behind" ] && { echo "verified: every running pod reads $color at epoch $epoch"; return; }
        [ "$(date +%s)" -lt "$deadline" ] || { echo "not verified:$behind" >&2; exit 1; }
        sleep 1
    done
}

retire() {
    color=$(color_arg "${1:-}")
    active=$(generation "$(any_pod "")" | jq -r '.active.color // empty')
    [ "$active" != "$color" ] || { echo "$color is active: activate the other color first" >&2; exit 1; }
    k scale statefulset "chat-$color" --replicas=0
    echo "retiring $color $(version_of "$color")"
}

status() {
    pod=$(running_pods | head -n 1)
    [ -n "$pod" ] && generation "$pod" | jq -c '{active: .active}' || echo '{"active": null}'
    k get pods -l app=sharded-chat -L color,version
}

case "${1:-}" in
    deploy|activate|verify|retire) cmd=$1; shift; "$cmd" "$@" ;;
    status) status ;;
    *) usage ;;
esac
