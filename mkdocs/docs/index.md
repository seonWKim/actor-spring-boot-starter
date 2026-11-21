---
title: Actor Spring Boot Starter
description: The actor model for Spring Boot, on Apache Pekko. Actors are Spring beans.
hide:
  - navigation
  - toc
---

<div class="asb-hero" markdown>
<div class="asb-hero__pitch" markdown>
<div class="asb-hero__title" markdown>

![](assets/brand/mark.svg#only-light){ .asb-hero__mark }
![](assets/brand/mark-dark.svg#only-dark){ .asb-hero__mark }

# Actor Spring Boot Starter

</div>

The actor model for Spring Boot, on [Apache Pekko](https://pekko.apache.org/) (the open-source fork of Akka).
{ .asb-hero__lead }

Actors are Spring beans. You spawn them, send them messages, and scale them across a cluster, and the API
you compile against has no Pekko types.

Java 21+ · Spring Boot 3.5+ · Apache 2.0
{ .asb-hero__meta }

[Get started](getting-started.md){ .md-button .md-button--primary }
[Browse the guides](guides/core/actors.md){ .md-button }

</div>
<div class="asb-hero__code" markdown>

=== "Gradle"

    ```groovy
    implementation 'io.github.seonwkim:actor-spring-boot-starter:1.0.0'
    ```

=== "Maven"

    ```xml
    <dependency>
      <groupId>io.github.seonwkim</groupId>
      <artifactId>actor-spring-boot-starter</artifactId>
      <version>1.0.0</version>
    </dependency>
    ```

```java title="GreeterActor.java"
@Component
public class GreeterActor implements SpringActor<GreeterActor.Command> {
    public interface Command {}

    public static class Greet extends AskCommand<String> implements Command {
        public final String name;
        public Greet(String name) { this.name = name; }
    }

    @Override
    public SpringActorBehavior<Command> create(SpringActorContext actorContext) {
        return SpringActorBehavior.builder(Command.class, actorContext)
                .onMessage(Greet.class, (ctx, msg) -> {
                    msg.reply("Hello, " + msg.name + "!");
                    return SpringActorBehavior.same();
                })
                .build();
    }
}
```

</div>
</div>

## Why actors in a Spring application

Most Spring services are stateless: every request reads its state from a database. Some problems are
stateful by nature, and an actor fits them better:

- **A chat room or a game session** that many users talk to at once
- **A user session, a cart or a device** that receives a stream of events
- **A workflow or a long-running AI agent** that waits for timers, tools, model replies and approvals

An actor owns its state and handles one message at a time, so you write plain code with no locks.
A failed actor is restarted by its supervisor. Sharding spreads thousands of actors over a cluster,
and event sourcing brings an actor's state back after a restart.

## What you get

<div class="asb-grid" markdown>
<div class="asb-card" markdown>

**Core**
{ .asb-card__title }

Actors as beans, tell and ask, supervision, timers, routers, pub/sub topics, receptionist, dispatchers, logging with actor context

[Actors](guides/core/actors.md)
{ .asb-card__link }

</div>
<div class="asb-card" markdown>

**Cluster**
{ .asb-card__title }

Sharding, singletons, replicated data, split brain resolver and leases; join through seed nodes, Kubernetes, a database or Redis

[Sharded actors](guides/cluster/sharded-actors.md)
{ .asb-card__link }

</div>
<div class="asb-card" markdown>

**Data**
{ .asb-card__title }

Actor state in your database, event-sourced actors, projections into Spring Data or Kafka

[State in a database](guides/data/state-in-a-database.md)
{ .asb-card__link }

</div>
<div class="asb-card" markdown>

**Streaming**
{ .asb-card__title }

Reactor `Mono`/`Flux`, SSE, WebSocket and gRPC

[Reactor streams](guides/streaming/reactor-streams.md)
{ .asb-card__link }

</div>
<div class="asb-card" markdown>

**Operations**
{ .asb-card__title }

Graceful shutdown, Actuator health, metrics, tracing, dashboards, rolling/canary/blue-green deployments

[Running in production](guides/operations/production.md)
{ .asb-card__link }

</div>
<div class="asb-card" markdown>

**Kotlin**
{ .asb-card__title }

`suspend` ask and spawn, reified helpers

[Kotlin](guides/kotlin.md)
{ .asb-card__link }

</div>
<div class="asb-card" markdown>

**Testing**
{ .asb-card__title }

`@ActorTest` slice, multi-node cluster helpers

[Testing](guides/testing.md)
{ .asb-card__link }

</div>
<div class="asb-card" markdown>

**AI agents**
{ .asb-card__title }

Agents as sharded actors with tools, streaming and approvals

[AI agents](guides/ai-agents.md)
{ .asb-card__link }

</div>
</div>

## Modules

Start with `actor-spring-boot-starter`, and add the others when you need them. All are published as
`io.github.seonwkim:<module>:1.0.0` and need Java 21+ and Spring Boot 3.5+.

| Module | What it adds |
|---|---|
| `actor-spring-boot-starter` | The actor system, actors as beans, messaging, routers, topics, receptionist |
| `actor-spring-boot-starter-cluster` | Cluster mode: sharding, singletons, replicated data, split brain resolver (use it instead of the starter above) |
| `actor-jdbc` | Cluster join and leases through your `DataSource` |
| `actor-redis` | Cluster join and leases through Redis |
| `actor-persistence` | Event-sourced actors, with journal and snapshots on your `DataSource` |
| `actor-projection` | Projections of persisted events into Spring Data or Kafka |
| `actor-streams` | Reactor bridges, and SSE, WebSocket and gRPC streaming |
| `actor-metrics` | Micrometer meters for actors, mailboxes, dispatchers, cluster and sharding |
| `actor-kotlin` | Kotlin coroutines and reified helpers |
| `actor-test` | `@ActorTest`, cluster test helpers and store contract tests (test scope) |

Using several? Import `actor-bom` to set the version once (see [Getting started](getting-started.md#several-modules-import-the-bom)).

## Next steps

- [Getting started](getting-started.md): an actor answering HTTP requests in a few minutes
- [Examples](examples/index.md): runnable applications, from a single node to Kubernetes
- [llms.txt](llms.txt): the index of these docs for AI assistants
