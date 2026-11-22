#!/bin/bash
# Checks the starter's deployment features on the local kind cluster, and fails if one doesn't hold.
# Needs the example deployed by ../setup-local.sh (cluster spring-actor-demo, 3 replicas, Redis).
#   1. readiness and the cluster  a new pod is not ready before it joins, and a pod that leaves the
#                                 cluster stops being ready while Spring would still accept traffic
#   2. disruption budget          draining a node under load evicts one pod at a time
#   3. readiness follows the color one Service selects both colors; activating green moves the traffic
#                                 with no selector change (management.health.actor.generation.enabled)
# STEPS="1 3" runs only those steps. Step 3 leaves green serving: run ../setup-local.sh again (or cleanup-local.sh first) to start over.
set -euo pipefail

cd "$(dirname "$0")"
NS=${NAMESPACE:-spring-actor}
CLUSTER=${KIND_CLUSTER:-spring-actor-demo}
BLUE=spring-actor
GREEN=spring-actor-green
SERVICE=spring-actor-http
GRACE=${GRACE:-20s}
LOGS=$(mktemp -d)
failed=0

pass() { printf '  ok      %s\n' "$*"; }
fail() { printf '  FAILED  %s\n' "$*"; failed=1; }

# The running pods with app=$1, without the ones shutting down.
running_pods() {
    kubectl -n "$NS" get pods -l app="$1" -o json | jq -r \
        '.items[] | select(.status.phase == "Running" and .metadata.deletionTimestamp == null) | .metadata.name'
}

ready_count() {
    kubectl -n "$NS" get pods -l app="$1" -o json | jq '[.items[]
        | select(.metadata.deletionTimestamp == null)
        | select(any(.status.conditions[]?; .type == "Ready" and .status == "True"))] | length'
}

wait_ready() {
    kubectl -n "$NS" rollout status deploy/"$1" --timeout=300s > /dev/null
    until [ "$(ready_count "$1")" -eq "$(kubectl -n "$NS" get deploy "$1" -o jsonpath='{.spec.replicas}')" ]; do
        sleep 2
    done
}

# "<readiness status> <readinessState> <actorCluster> <actorGeneration>" as pod $1 answers now, or
# nothing when it doesn't answer.
readiness() {
    kubectl -n "$NS" exec "$1" -- curl -s -m 2 localhost:8081/actuator/health/readiness 2> /dev/null \
        | jq -r '"\(.status) \(.components.readinessState.status // "-") \(.components.actorCluster.status // "-") \(.components.actorGeneration.status // "-")"' \
            2> /dev/null || true
}

generation() {
    pod=$1; shift
    kubectl -n "$NS" exec "$pod" -- sh -c \
        'curl -s -u "$SPRING_SECURITY_USER_NAME:$SPRING_SECURITY_USER_PASSWORD" "$@" localhost:8081/actuator/generation' \
        sh "$@"
}

activate() {
    color=$1 pod=$2
    epoch=$(generation "$pod" | jq '.active.epoch // 0')
    generation "$pod" -X POST -H 'Content-Type: application/json' -o /dev/null -w '%{http_code}' \
        -d "{\"color\":\"$color\",\"expectedEpoch\":$epoch,\"operationId\":\"test-features-$color-$epoch\"}"
}

# Every pod named in $1 is in the space-separated list $2.
all_in() {
    for p in $1; do [[ " $2 " == *" $p "* ]] || return 1; done
}

# No pod named in $1 is in the space-separated list $2.
none_in() {
    for p in $1; do [[ " $2 " != *" $p "* ]] || return 1; done
}

# Every running pod of $1 answers UP on /actuator/health/warmup, and there are $2 of them.
warm() {
    pods=$(running_pods "$1")
    [ "$(wc -w <<< "$pods" | tr -d ' ')" -eq "$2" ] || return 1
    for p in $pods; do
        kubectl -n "$NS" exec "$p" -- curl -sf localhost:8081/actuator/health/warmup > /dev/null 2>&1 || return 1
    done
}

# The Service's ready endpoints, as pod names.
serving() {
    kubectl -n "$NS" get endpointslices -l kubernetes.io/service-name="$SERVICE" -o json \
        | jq -r '[.items[].endpoints[]? | select(.conditions.ready) | .targetRef.name] | sort | join(" ")'
}

# --- 1. readiness and the cluster -----------------------------------------------------------------

readiness_and_cluster() {
    echo "1. readiness and the cluster"
    # Writable management routes, so one pod can leave the cluster without being shut down.
    kubectl -n "$NS" set env deploy/"$BLUE" \
        JAVA_TOOL_OPTIONS=-Dspring.actor.pekko.management.http.route-providers-read-only=false > /dev/null
    wait_ready "$BLUE"

    # Join: sample a new pod's readiness until it is UP. It must never be UP before actorCluster is.
    before=$(running_pods "$BLUE" | sort)
    kubectl -n "$NS" scale deploy/"$BLUE" --replicas=4 > /dev/null
    new=""
    until [ -n "$new" ]; do
        new=$(comm -13 <(echo "$before") <(running_pods "$BLUE" | sort) | head -n 1)
        sleep 1
    done
    end=$(( $(date +%s) + 180 ))
    : > "$LOGS/join.log"
    while [ "$(date +%s)" -lt "$end" ]; do
        sample=$(readiness "$new")
        [ -n "$sample" ] && echo "$sample" >> "$LOGS/join.log"
        [ "${sample%% *}" = UP ] && break
        sleep 0.5
    done
    early=$(awk '$1 == "UP" && $3 != "UP"' "$LOGS/join.log" | wc -l | tr -d ' ')
    waited=$(awk '$1 != "UP" && $2 == "UP" && $3 != "UP"' "$LOGS/join.log" | wc -l | tr -d ' ')
    if ! grep -q '^UP' "$LOGS/join.log"; then
        fail "join: the new pod never became ready"
    elif ! awk '{print $3}' "$LOGS/join.log" | grep -qv '^-$'; then
        fail "join: readiness has no actorCluster component"
    elif [ "$early" -gt 0 ]; then
        fail "join: ready $early time(s) before the pod was an Up member"
    else
        pass "join: not ready before the pod was an Up member ($waited sample(s) with Spring ready but the pod not joined)"
    fi
    wait_ready "$BLUE"
    kubectl -n "$NS" scale deploy/"$BLUE" --replicas=3 > /dev/null
    wait_ready "$BLUE"

    # Leave: one pod leaves the cluster through Pekko Management. Spring still accepts traffic
    # (readinessState UP), so only actorCluster can take it out of the Service.
    pod=$(running_pods "$BLUE" | head -n 1)
    self=$(kubectl -n "$NS" exec "$pod" -- curl -s localhost:7626/cluster/members | jq -r '.selfNode')
    kubectl -n "$NS" exec "$pod" -- curl -s -X DELETE "localhost:7626/cluster/members/$(jq -rn --arg a "$self" '$a|@uri')" > /dev/null
    : > "$LOGS/leave.log"
    end=$(( $(date +%s) + 60 ))
    while [ "$(date +%s)" -lt "$end" ]; do
        sample=$(readiness "$pod")
        [ -z "$sample" ] && break   # the actor system terminated and the JVM exited
        echo "$sample" >> "$LOGS/leave.log"
        sleep 0.3
    done
    refused=$(awk '$1 != "UP" && $2 == "UP" && $3 != "UP"' "$LOGS/leave.log" | wc -l | tr -d ' ')
    if [ "$refused" -gt 0 ]; then
        pass "leave: not ready once the pod left the cluster, while Spring still accepted traffic ($refused sample(s))"
    else
        fail "leave: no sample showed readiness down with readinessState UP (see $LOGS/leave.log)"
    fi
    # The container restarts (spring.actor.shutdown.exit-process) and the pod joins again.
    sleep 10
    wait_ready "$BLUE"
}

# --- 2. disruption budget -------------------------------------------------------------------------

disruption_budget() {
    echo "2. disruption budget"
    replicas=$(kubectl -n "$NS" get deploy "$BLUE" -o jsonpath='{.spec.replicas}')
    node=$(kubectl -n "$NS" get pods -l app="$BLUE" -o jsonpath='{range .items[*]}{.spec.nodeName}{"\n"}{end}' \
        | sort | uniq -c | sort -rn | awk 'NR == 1 {print $2}')
    on_node=$(kubectl -n "$NS" get pods -l app="$BLUE" --field-selector spec.nodeName="$node" -o name | wc -l | tr -d ' ')

    ./load.sh 120 "$LOGS/drain.log" > "$LOGS/drain.sum" &
    load=$!
    sleep 5
    kubectl drain "$node" --ignore-daemonsets --delete-emptydir-data --timeout=300s > "$LOGS/drain.out" 2>&1 &
    drain=$!
    lowest=$replicas
    while kill -0 "$drain" 2> /dev/null; do
        now=$(ready_count "$BLUE")
        [ "$now" -lt "$lowest" ] && lowest=$now
        sleep 1
    done
    wait "$drain" || fail "drain: kubectl drain failed (see $LOGS/drain.out)"
    wait_ready "$BLUE"
    kubectl uncordon "$node" > /dev/null
    if wait "$load"; then load_result=ok; else load_result=FAILED; fi

    if [ "$lowest" -ge $(( replicas - 1 )) ]; then
        pass "drain of $node ($on_node pod(s)): at least $lowest of $replicas pods ready throughout"
    else
        fail "drain of $node ($on_node pod(s)): only $lowest of $replicas pods ready at one point"
    fi
    if [ "$on_node" -gt 1 ]; then
        if grep -q "disruption budget" "$LOGS/drain.out"; then
            pass "drain: the budget held back the second eviction until the first pod was replaced"
        else
            fail "drain: the budget never held an eviction back (see $LOGS/drain.out)"
        fi
    fi
    if [ "$load_result" = ok ]; then
        pass "load during the drain: $(cat "$LOGS/drain.sum")"
    else
        fail "load during the drain: $(cat "$LOGS/drain.sum")"
    fi
}

# --- 3. readiness follows the color ---------------------------------------------------------------

readiness_follows_color() {
    echo "3. readiness follows the color (grace $GRACE)"
    # Blue must be active before readiness depends on it, or its pods would never be ready again.
    pod=$(running_pods "$BLUE" | head -n 1)
    if [ "$(generation "$pod" | jq -r '.active.color // "none"')" != blue ]; then
        [ "$(activate blue "$pod")" = 200 ] || { fail "could not activate blue"; return; }
    fi

    # Both colors get the opt-in and a shared label, and the HTTP Service selects that label.
    kubectl -n "$NS" patch deploy "$BLUE" --type=strategic -p "$(jq -n --arg grace "$GRACE" '{spec: {template: {
        metadata: {labels: {serves: "chat"}},
        spec: {containers: [{name: "spring-actor", env: [
            {name: "MANAGEMENT_HEALTH_ACTOR_GENERATION_ENABLED", value: "true"},
            {name: "MANAGEMENT_HEALTH_ACTOR_GENERATION_GRACE", value: $grace}]}]}}}}')" > /dev/null
    wait_ready "$BLUE"
    kubectl -n "$NS" patch svc "$SERVICE" --type=json \
        -p '[{"op":"replace","path":"/spec/selector","value":{"serves":"chat"}}]' > /dev/null

    image=$(kubectl -n "$NS" get deploy "$BLUE" -o jsonpath='{.spec.template.spec.containers[0].image}')
    policies=$(kubectl -n "$NS" get networkpolicy -o json \
        | jq --arg blue "$BLUE" --arg green "$GREEN" -f blue-green-policies.jq)
    if [ "$(jq '.items | length' <<< "$policies")" -gt 0 ]; then
        kubectl apply -f - <<< "$policies" > /dev/null
    fi
    kubectl -n "$NS" get deploy "$BLUE" -o json \
        | jq --arg name "$GREEN" --arg image "$image" -f blue-green-deployment.jq \
        | jq '.spec.template.metadata.labels.serves = "chat"' \
        | kubectl apply -f - > /dev/null
    # Green warms up but isn't ready: its color isn't active.
    until warm "$GREEN" 3; do
        sleep 3
    done
    sleep 15   # a few readiness probe periods
    if [ "$(ready_count "$GREEN")" -eq 0 ] && [ "$(ready_count "$BLUE")" -eq 3 ]; then
        pass "before activation: blue ready, green warm but not ready"
    else
        fail "before activation: $(ready_count "$BLUE") blue and $(ready_count "$GREEN") green pods ready"
    fi
    blue_pods=$(running_pods "$BLUE" | sort | tr '\n' ' ')
    green_pods=$(running_pods "$GREEN" | sort | tr '\n' ' ')

    ./load.sh 90 "$LOGS/color.log" > "$LOGS/color.sum" &
    load=$!
    sleep 5
    activated=$(date +%s)
    [ "$(activate green "$(running_pods "$GREEN" | head -n 1)")" = 200 ] || fail "could not activate green"
    green_in="" blue_out=""
    while [ $(( $(date +%s) - activated )) -lt 80 ] && { [ -z "$green_in" ] || [ -z "$blue_out" ]; }; do
        now=$(serving)
        if [ -z "$green_in" ] && all_in "$green_pods" "$now"; then
            green_in=$(( $(date +%s) - activated ))
        fi
        if [ -z "$blue_out" ] && none_in "$blue_pods" "$now"; then
            blue_out=$(( $(date +%s) - activated ))
        fi
        sleep 1
    done
    if wait "$load"; then load_result=ok; else load_result=FAILED; fi
    after=$(awk -v t="$activated" '$1 >= t && $2 != "ok"' "$LOGS/color.log" | wc -l | tr -d ' ')

    if [ -n "$green_in" ] && [ -n "$blue_out" ] && [ "$green_in" -le "$blue_out" ]; then
        pass "activation: every green pod in the Service after ${green_in}s, every blue pod out after ${blue_out}s"
    else
        fail "activation: green in after ${green_in:-never}s, blue out after ${blue_out:-never}s"
    fi
    if [ "$load_result" = ok ]; then
        pass "load across the switch: $(cat "$LOGS/color.sum"), $after failed from activation on"
    else
        fail "load across the switch: $(cat "$LOGS/color.sum"), $after failed from activation on"
    fi
}

for step in ${STEPS:-1 2 3}; do
    case $step in
    1) readiness_and_cluster ;;
    2) disruption_budget ;;
    3) readiness_follows_color ;;
    esac
done

echo "logs: $LOGS"
exit $failed
