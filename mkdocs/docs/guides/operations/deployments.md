# Deployments

This guide covers how to release a new version of a clustered actor application without failing requests:
rolling updates, canary releases and blue-green switches, and how to change messages so that two versions
can run side by side.

The commands come from the [Kubernetes example](../../examples/kubernetes.md)
([`example/kubernetes`](https://github.com/seonWKim/actor-spring-boot-starter/tree/main/example/kubernetes)),
which runs the chat example as a three-pod cluster on a local kind cluster. The ideas apply to any platform.

## Choosing a strategy

| Strategy | What runs | Traffic | Versions mix in one cluster | Script |
|---|---|---|---|---|
| Rolling update | One cluster; old and new pods side by side during the rollout | Moves pod by pod | Yes | `kubectl set image` |
| Canary | One cluster; a few new pods join the running cluster | Share = canary pods / all pods | Yes | `scripts/canary.sh` |
| Blue-green | Two separate clusters (blue and green) | Switches in one step | No | `scripts/blue-green.sh` |

- Use a **rolling update** for most releases. It needs no extra capacity beyond one pod.
- Use a **canary** when you want a few real requests on the new version first, with a quick way back.
- Use **blue-green** when the new version cannot talk to the old one (an incompatible message change) or
  when you want to switch all traffic at once. It needs twice the pods for a while, and in-memory state
  does not move.

All three rely on the [graceful shutdown](production.md#graceful-shutdown) settings: each old pod leaves the
cluster on purpose, so its sharded entities and singletons move instead of waiting for the split brain
resolver.

## Rolling update

A rolling update replaces pods one at a time inside one cluster. The example's Deployment adds a new pod
before it removes an old one, so the cluster never drops below its size:

```yaml
spec:
  replicas: 3
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxUnavailable: 0    # never fewer than 3 ready pods
      maxSurge: 1          # start one new pod before stopping an old one
```

Each new pod joins the running cluster. Its readiness probe includes `actorCluster`, so it gets traffic only
once it is an `Up` member (see [Health checks](production.md#health-checks)). Then Kubernetes stops an old
pod: the `preStop` sleep lets the load balancer drop it, and coordinated shutdown moves its entities to the
other nodes.

```bash
kubectl set image deployment/spring-actor spring-actor=spring-actor-chat:v2 -n spring-actor
kubectl rollout status deployment/spring-actor -n spring-actor

# Go back to the previous version
kubectl rollout undo deployment/spring-actor -n spring-actor
```

## Canary

A canary runs a few pods of the new version in the same cluster as the main pods. `canary.sh start` copies
the live Deployment as `spring-actor-canary` with the new image and a `track: canary` label. The Services
select `app=spring-actor`, so they send the canary pods their share of the requests: with three main pods and
one canary, about one in four.

```bash
cd example/kubernetes
scripts/canary.sh start spring-actor-chat:v3      # one canary pod; `start <image> 2` for two
scripts/canary.sh promote                         # roll the main pods to v3, then remove the canary
# scripts/canary.sh abort                         # or remove the canary and keep the main pods as they are
```

Because the canary is a member of the same cluster, sharded entities can move to it and it exchanges messages
with the old pods. Both versions must read each other's messages and entity state (see
[Message compatibility](#message-compatibility-across-versions)).

## Blue-green

Blue-green starts the new version as a second, separate cluster. `blue-green.sh deploy` copies the live
Deployment as `spring-actor-green` with the new image, labels its pods `app=spring-actor-green`, and points
its Kubernetes API discovery at that label
(`--spring.actor.pekko.discovery.kubernetes-api.pod-label-selector=app=spring-actor-green`). So green forms
its own cluster and never exchanges messages with blue.

With `jdbc` or `redis` discovery there is no label to select: each color registers in the shared store under
`<spring.actor.name>-<color>` (`spring-actor-system-green`), so Bootstrap hands green only green's pods. The
generation record keeps `spring.actor.name` (or `spring.actor.generation.name`), so both colors read the
same one.

**Upgrading a running color in place.** Versions before this name registered every color under
`spring.actor.name`. A rolling update onto this version would leave the new pods looking under
`<name>-<color>`, where none of the running pods are. Release the upgrade as blue-green (the new color
forms its own cluster anyway), or set `spring.actor.cluster.discovery.service-name` to the old name
(`spring.actor.name`) for the rolling update.

Before green starts, `deploy` also copies each NetworkPolicy that selects `app=spring-actor` (for example
`spring-actor-green` from `spring-actor`, and `spring-actor-green-store` from `spring-actor-store` in the
discovery overlays), with every `app=spring-actor` selector changed to `app=spring-actor-green`. Green gets
the same restrictions as blue, and remoting (25520) and management (7626) open only between pods of the same
color: blue and green cannot reach each other's cluster ports. A canary keeps `app=spring-actor`, so blue's
policies cover it.

### The release pipeline

A blue-green release runs in four stages. Each one waits for a signal before the next starts:

| Stage | Command | Waits for |
|---|---|---|
| 1. Warm up | `blue-green.sh deploy <image>` | every green pod answers `UP` on `/actuator/health/warmup`, then is ready |
| 2. Activate | `blue-green.sh activate` | the [generation endpoint](#the-active-generation) accepts green; then the HTTP Service's selector picks the green pods |
| 3. Verify | `blue-green.sh verify` | the generation record says green, every pod of both colors reads its epoch (`node` in `GET /actuator/generation`), and the Service's ready endpoints are exactly the green pods |
| 4. Retire | `blue-green.sh retire` | run it once green has served for a while; it deletes blue |

```bash
cd example/kubernetes
scripts/blue-green.sh deploy spring-actor-chat:v4   # start green beside blue and wait until it is warm
scripts/blue-green.sh activate                      # green becomes the active generation; the Service selects it
scripts/blue-green.sh verify                        # wait until only green pods serve
# scripts/blue-green.sh rollback                    # activate blue again (a new epoch) and select it
scripts/blue-green.sh retire                        # delete blue
```

The first stage gates on **warm-up**, not on `kubectl rollout status` or the Deployment's `Available`
condition. Those follow traffic readiness, and in a pipeline where the standby color refuses traffic until it
is activated they never come. The warm-up group says only that the pod can be activated:

- the pod is an `Up` member of its cluster,
- the cluster has at least `management.health.actor.warmup.min-members` `Up` members (the example sets 2),
- and the discovery store answers, when the cluster forms through `jdbc` or `redis` discovery.

It never depends on which color is active. The three probes stay apart: liveness says whether to restart the
pod, readiness whether to send it traffic, and warm-up whether it can be activated (see
[Health checks](production.md#warm-up)).

In-memory state does not move: entities, singletons and topics start empty on green. Keep state that must
survive the switch in a database or event journal both clusters read
([State in a Database](../data/state-in-a-database.md), [Event-Sourced Actors](../data/event-sourcing.md)).
Until `retire`, `rollback` sends traffic back to blue, which still has its state.

### The active generation

Which color is active is a record in the store the cluster already uses (Redis or the database), not only a
Service selector. Each record is a **generation**: a color, an **epoch** that grows by one with every
activation, the deployment operation ID that activated it, and when. The application can read it, and the
pipeline changes it only through a compare-and-set:

- **Competing deploys fail loudly.** An activation names the epoch it expects to be active. If another deploy
  activated in between, it is rejected (`EPOCH_MISMATCH`, HTTP 409) and nothing changes. Of several
  activations that expect the same epoch, exactly one wins.
- **Retries are idempotent.** An activation carries an operation ID. Retrying it with the same color and
  expected epoch returns the generation it activated the first time (`ALREADY_ACTIVATED`, HTTP 200), even
  after the epoch has moved on. The same operation ID with another color or epoch is `OPERATION_CONFLICT`
  (409). A pipeline keeps the expected epoch and the operation ID it used, and sends both again when it
  retries. Make the operation ID one run of the pipeline, not the git tag: `<tag>@<commit>#<ci-run-id>`,
  such as `v1.4.0@d4e5f6a#8812`. A retry within the run reuses it, and a later run of the same tag (a
  re-release, or a rollback to it after its slot was retired) gets a new one. A bare tag can be activated
  only once: the second time it is `OPERATION_CONFLICT`.
- **Old work stays fenced.** Blue, then green, then blue again gives epochs 1, 2 and 3. Work that blue stamped
  in its first turn carries epoch 1 and is refused in its second (epoch 3), because the epoch never goes back.

Turn it on in each color with its color and a store:

```yaml
spring:
  actor:
    generation:
      color: blue          # green in the other deployment
      store: redis         # jdbc: the actor_generation table; custom: your GenerationStore bean
      # name: orders       # the record both colors share; default: spring.actor.name
      # refresh: 1s        # how long a node reuses a read of the record
management:
  endpoints:
    web:
      exposure:
        include: health,generation
  endpoint:
    generation:
      access: unrestricted   # activation is a write: read-only by default
```

**The `generation` endpoint.** `GET /actuator/generation` returns this node's color, the active
generation in the store (`active`) and what this node reads now (`node`, *new in the next release*). `POST /actuator/generation` with `color`, `expectedEpoch` (0 before the first activation) and
`operationId` activates a color:

```bash
curl -u deployer:$PASSWORD -X POST localhost:8080/actuator/generation \
  -H 'Content-Type: application/json' \
  -d '{"color":"green","expectedEpoch":1,"operationId":"release-2026-11-11-1"}'
# {"outcome":"ACTIVATED","generation":{"color":"green","epoch":2,"operationId":"release-2026-11-11-1",...}}
```

**Verifying a switch.** A node reuses its read of the record for `spring.actor.generation.refresh`, so
after an activation each node keeps stamping and checking with the old epoch for up to that long. `node`
says what this node reads now: the generation (`active`), when it read it from the store (`readAt`),
whether its own color is the active one (`colorActive`), whether it is warmed up (`warm`, from the
`actorWarmup` health indicator; `null` outside cluster mode) and the build it runs (`build`, *new in the
next release*: `version` from Spring Boot's build info, `springBoot { buildInfo() }`, and `commit` from
`git.properties`, for example through the `com.gorylenko.gradle-git-properties` plugin; `null` without
either). Reading the endpoint does not refresh the node's read. A pipeline knows the switch is done when every node of both colors reports the new epoch:

```bash
curl -s localhost:8081/actuator/generation
# {"deployment":"orders","color":"blue",
#  "active":{"color":"green","epoch":2,...},
#  "node":{"active":{"color":"blue","epoch":1,...},"readAt":"2026-11-11T10:00:00.250Z","colorActive":true,"warm":true,
#          "build":{"version":"1.4.0","commit":"d4e5f6a"}}}

# Every pod reads the record's epoch
for pod in $PODS; do
  [ "$(kubectl exec "$pod" -- curl -s localhost:8081/actuator/generation | jq '.node.active.epoch')" = 2 ] || echo "$pod lags"
done
```

`blue-green.sh verify` does this for every running pod of both colors, and skips a pod whose answer has no
`node` (a version of the starter without it). With `EXPECTED_COMMIT` set, it also checks that every green
pod runs that commit, so "green is live" means the build the pipeline deployed is live.

Activation needs an authenticated user: a request without one is answered `401` (`UNAUTHENTICATED`). Secure
the endpoint with Spring Security like any other write endpoint, for example with a role:

=== "Java"

    ```java
    @Bean
    SecurityFilterChain actuator(HttpSecurity http) throws Exception {
        return http.securityMatcher(EndpointRequest.to("generation"))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.POST).hasRole("DEPLOYER")
                        .anyRequest().permitAll())
                .httpBasic(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable()) // called by a pipeline, not a browser session
                .build();
    }
    ```

=== "Kotlin"

    ```kotlin
    @Bean
    fun actuator(http: HttpSecurity): SecurityFilterChain =
        http.securityMatcher(EndpointRequest.to("generation"))
            .authorizeHttpRequests {
                it.requestMatchers(HttpMethod.POST).hasRole("DEPLOYER")
                    .anyRequest().permitAll()
            }
            .httpBasic(Customizer.withDefaults())
            .csrf { it.disable() } // called by a pipeline, not a browser session
            .build()
    ```

**Audit.** Every attempt that reaches the endpoint, or `DeploymentGeneration.activate` in your code, publishes
an `ActivationEvent` (an `ApplicationEvent`) with the requested color, expected epoch, operation ID, the
user's name and the result. The starter also logs it (`INFO` when accepted, `WARN` when rejected) and counts
it in `actor.generation.activations`, tagged with `outcome`. A request that Spring Security turns away never
reaches the endpoint, so it shows in Spring Security's events instead.

**Fencing in your code.** Inject `DeploymentGeneration`. Without `spring.actor.generation.color` the
starter's bean is a no-op (*new in the next release*): always active, `stamp()` returns 0 and nothing is stale, so the same code runs
with and without blue-green. Your own `DeploymentGeneration` bean replaces it.

Stamp a message with `stamp()` where the work starts (it fails when this node's color is not active). A
message that implements `GenerationStamped` (a record with an `epoch` component does) can be checked where
it runs by wrapping its handler with `ifCurrent` (*new in the next release*): a message of an older epoch is dropped and logged as a
warning on the actor's log, and the handler does not run.

=== "Java"

    ```java
    record ChargeOrder(String orderId, long epoch) implements Command, GenerationStamped {}

    orders.tell(new ChargeOrder(orderId, generation.stamp()));   // where the work starts

    .onMessage(ChargeOrder.class, generation.ifCurrent((ctx, msg) -> {
        payments.charge(msg.orderId());                         // only for the active epoch
        return SpringActorBehavior.same();
    }))
    ```

=== "Kotlin"

    ```kotlin
    data class ChargeOrder(val orderId: String, override val epoch: Long) : Command, GenerationStamped

    orders.tell(ChargeOrder(orderId, generation.stamp()))       // where the work starts

    .onMessage(ChargeOrder::class.java, generation.ifCurrent { _, msg ->
        payments.charge(msg.orderId)                            // only for the active epoch
        SpringActorBehavior.same()
    })
    ```

The stamp is not added on send: messages are immutable, so the sender passes the epoch when it builds the
message. With [reliable delivery](../core/reliable-delivery.md#stale-generation-not-the-owner-yet-or-anymore),
call `check(epoch)` in the handler instead of `ifCurrent`. It throws `StaleGenerationException`, the
retryable outcome, so the sender retries, while a dropped message would be acknowledged:

=== "Java"

    ```java
    sender.send(new ChargeOrder(orderId, generation.stamp()), key);

    .onMessage(ChargeOrder.class, (ctx, msg) -> {
        generation.check(msg.epoch());                     // StaleGenerationException if it is old
        payments.charge(msg.orderId());
        return SpringActorBehavior.same();
    })
    ```

=== "Kotlin"

    ```kotlin
    sender.send(ChargeOrder(orderId, generation.stamp()), key)

    .onMessage(ChargeOrder::class.java) { _, msg ->
        generation.check(msg.epoch)                        // StaleGenerationException if it is old
        payments.charge(msg.orderId)
        SpringActorBehavior.same()
    }
    ```

A node reuses a read of the record for `spring.actor.generation.refresh` (1 second), so for up to that long
after an activation elsewhere it still accepts the old epoch. Where that matters, keep the epoch with the
data and compare it in the write itself (`UPDATE ... WHERE epoch <= ?`).

**Stores.** `jdbc` keeps one row per activation in `actor_generation` (created with the other actor-jdbc
tables): the primary key on deployment and epoch is the compare-and-set. `redis` keeps the record and the
operation IDs in two hashes and changes them in one Lua script. Redis replicates asynchronously, so a failover
can lose an activation it had just accepted; use `jdbc` where that is not acceptable. Your own
`GenerationStore` bean replaces both, and must pass `GenerationStoreContract` from `actor-test`.

### Requests at the cutover

In the example the Service selector switches the colors, and readiness does not depend on the color. So
`deploy` waits for warm-up **and** then for the green pods to be ready. Warm-up alone is not enough there:
a pod can be warm before its readiness probe first passes (the example's probe starts after 30 seconds),
and a Service that selects only pods that are not ready yet has no endpoints. Measured on kind with
`test-strategies.sh` (four workers asking sharded entities):

| `deploy` waited for | Requests | Failed | Service served only green |
|---|---|---|---|
| warm-up only | 15,057 | 20, all within 10 seconds after `activate` | 15 seconds after `activate` |
| warm-up, then ready (the script today) | 23,151 and 22,890 (two runs) | 0 and 0 | at the first check after `activate` |

Blue keeps running and serving until `retire`, so a node whose proxy rules still point at blue for a moment
after the switch still gets answers.

**Readiness that follows the color** (*new in the next release*). With
`management.health.actor.generation.enabled: true`, the `actorGeneration` health indicator is `UP` while this
node's color is the active generation and `OUT_OF_SERVICE` otherwise, and with the probes on
(`management.endpoint.health.probes.enabled: true`) the readiness group includes it. One load balancer
target group (or one Service) can then hold both colors, and activation alone moves the traffic, without a
selector change, a second target group or Kubernetes:

1. Deploy green beside blue. Green passes `/actuator/health/warmup` (warm-up ignores the color) and fails
   readiness.
2. `POST /actuator/generation` activates green. Within `spring.actor.generation.refresh` each node reads it:
   green turns `UP`, blue `OUT_OF_SERVICE`.
3. The load balancer's health checks admit green and drain blue.

A color that loses activation stays `UP` for `management.health.actor.generation.grace` (default 30s), so
the load balancer admits green before it drains blue. Set it to at least the time your load balancer takes
to admit a target (its interval times its healthy threshold). During the grace blue still serves, as it
does until `retire` in the selector mode, and work it stamps is refused as stale. While the store can't be
read, each node keeps its last status, so an outage doesn't take both colors out. A node that starts with
its color inactive gets no grace.

Turn it on only where readiness should switch the colors: it also makes the standby's overall
`/actuator/health` `OUT_OF_SERVICE`, and `kubectl rollout status` waits forever for a standby
Deployment's pods. The example's `blue-green.sh` keeps the selector mode.

In that mode the cutover waits for readiness to propagate: each pod's readiness probe has to pass
(`periodSeconds` times `successThreshold` for the new color, `periodSeconds` times `failureThreshold` for the
old one), then the endpoint controller and every node's kube-proxy update. **Shorter probe intervals shorten
that window but don't close it**: without the grace, requests routed during it reach a pod that is about to
leave or none at all. The grace keeps blue in the pool until green is in it. Measured on kind with
`scripts/test-features.sh` (one Service selecting both colors, readiness probe every 5 seconds, failure
threshold 3, grace 20s): every green pod was in the Service 5 seconds after activation, every blue pod out
after 36, and none of 17,110 requests failed. The selector mode closes the window with draining:

- switch with the Service selector (or another routing change) while both colors are ready, and keep the old
  pods running until `verify` shows the Service no longer selects them (`retire` comes last);
- the `preStop` sleep (10 seconds in the example) keeps a terminating pod serving while the endpoint removal
  propagates;
- `server.shutdown: graceful` finishes the requests the pod has already accepted.

`scripts/test-strategies.sh` prints, for the blue-green step, how long the Service took to serve only green
and how many requests failed from activation on.

## Testing the strategies under load

`scripts/test-strategies.sh` runs all three strategies on the kind cluster from `setup-local.sh` while
`scripts/load.sh` sends requests, and fails if any request failed:

```bash
cd example/kubernetes
./setup-local.sh
scripts/test-strategies.sh
# rolling        total=... ok=... failed=0 ok
# canary         total=... ok=... failed=0 ok
# blue-green     total=... ok=... failed=0 ok
```

It runs, in order: a rolling update to `v2` (120 seconds of load), a canary of `v3` that is then promoted
(180 seconds), and a blue-green release of `v4` through the four stages above (90 seconds, then `retire`). Each step's load runs four workers that ask
sharded entities (`/hello?message=hi&entityId=e<0-49>`).

What it checks and what it does not:

- `load.sh` counts a request as ok only when the actor replied. The chat app answers `200` with
  `Request timed out ...` when an ask fails, so the HTTP status alone would hide lost messages.
- The new versions are retags of the same image, so it checks the rollout mechanics (joining, leaving,
  entities moving, the Service switching), not a change in the code.

## Message compatibility across versions

**Rolling updates and canaries mix versions in one cluster.** Old and new pods exchange messages, and a
sharded entity can move from an old pod to a new one. Every message and every piece of entity state that
crosses nodes must be readable by both versions:

- Add fields as optional. The old version must accept a message with the new field, and the new version a
  message without it.
- Don't rename or remove fields or message types in the same release.
- Keep each type on the same serializer (`JsonSerializable` or `CborSerializable`).
- Make an incompatible change in two releases: first one that reads both formats, then one that writes the
  new format.

For example, a second release adds an optional field to a command:

=== "Java"

    ```java
    // v1
    public static class SayHello extends AskCommand<String> implements Command {
        public final String message;

        public SayHello(String message) {
            this.message = message;
        }
    }

    // v2: a new, optional field. Messages from v1 nodes don't have it.
    public static class SayHello extends AskCommand<String> implements Command {
        public final String message;
        @Nullable public final String language;

        public SayHello(String message, @Nullable String language) {
            this.message = message;
            this.language = language;
        }
    }
    ```

=== "Kotlin"

    ```kotlin
    // v1
    class SayHello(val message: String) : AskCommand<String>(), Command

    // v2: a new, optional field. Messages from v1 nodes don't have it.
    class SayHello(val message: String, val language: String? = null) : AskCommand<String>(), Command
    ```

The starter's build checks this on every change: `MixedVersionMessageTest` runs two nodes in one JVM,
one with a message class and one with the same class plus a field, and sends a message each way between
sharded entities. The old node reads the new message and ignores the field it doesn't know; the new node
reads the old message with the missing field as `null`. The test covers `JsonSerializable` messages with
the starter's default Jackson settings. It does not cover a
renamed or removed field, a renamed class, or stored events and snapshots. To run the same check on your own
messages, see [Two versions of a message in one cluster](../testing.md#two-versions-of-a-message-in-one-cluster).

On Kubernetes, this was also measured on kind with a canary of the chat app: adding a field to `SayHello` lost no requests
(28,842 of 28,842), while renaming the class lost 104 of 7,105, which timed out because the old nodes could
not read the new type.

**Blue-green never mixes versions.** The two clusters never exchange messages, so the message format can
change freely. Stored state is different: events and snapshots in a journal or database must still be
readable by the new version (see [Event-Sourced Actors](../data/event-sourcing.md)).

## Rollback

| Strategy | How to go back |
|---|---|
| Rolling update | `kubectl rollout undo deployment/spring-actor -n spring-actor`: a rolling update in the other direction, so the same compatibility rules apply |
| Canary | `scripts/canary.sh abort` removes the canary pods; the main pods were never changed |
| Blue-green | `scripts/blue-green.sh rollback` until `retire`; blue still has its in-memory state |

## Related

- [Running in Production](production.md): graceful shutdown, health checks and the production checklist
- [Monitoring](monitoring.md): watch `actor.cluster.members` and `actor.cluster.unreachable` during a rollout
- [Kubernetes example](../../examples/kubernetes.md): the manifests and scripts on this page
- [Sharded Actors](../cluster/sharded-actors.md)
