# Cluster Formation

A node joins a cluster in one of two ways, set by `spring.actor.cluster.join`:

| `join` | How the node finds the cluster | Use it when |
|---|---|---|
| `seed-nodes` (default) | The addresses in `spring.actor.cluster.seed-nodes` | Nodes have fixed addresses |
| `discovery` | Peers found through `spring.actor.cluster.discovery.method`, then Pekko Cluster Bootstrap forms or joins the cluster | Nodes come and go with changing addresses (Kubernetes, autoscaling groups) |

## Joining through a store you already run

With `discovery.method` set to `jdbc` or `redis`, each node writes its own entry to a table or a Redis key,
and reads the others' entries to find its peers. This needs no Kubernetes API access (no RBAC) and no DNS
setup: only the store the application already uses, and network access between the nodes.

What each node does:

1. At startup it registers itself (its management host and the port its management server bound) and starts
   Cluster Bootstrap.
2. Every `heartbeat-interval` (default `5s`) it renews its entry.
3. Bootstrap lists the entries renewed within `ttl` (default `30s`), contacts those nodes on their management
   port, and either joins the cluster they are in or, if there is none yet, the node with the lowest address
   forms it.
4. On a graceful shutdown it deletes its entry first, so new nodes no longer contact it. A node that crashes
   stops renewing, and its entry is ignored once it is older than the ttl.

Times come from the store's clock (the database's `CURRENT_TIMESTAMP`, Redis `TIME`), so the nodes' clocks
need not agree.

### Dependencies

Discovery uses Pekko Cluster Bootstrap and the management HTTP server, plus the module of your store:

=== "Gradle"

    ```kotlin
    implementation("io.github.seonwkim:actor-spring-boot-starter-cluster:$version")
    implementation("org.apache.pekko:pekko-management-cluster-bootstrap_3:1.1.1")
    implementation("org.apache.pekko:pekko-management-cluster-http_3:1.1.1")
    implementation("io.github.seonwkim:actor-jdbc:$version")   // or actor-redis
    ```

=== "Maven"

    ```xml
    <dependency>
        <groupId>io.github.seonwkim</groupId>
        <artifactId>actor-jdbc</artifactId> <!-- or actor-redis -->
        <version>${actor.version}</version>
    </dependency>
    ```

`actor-jdbc` uses the application's `DataSource` (or [one of its own](#a-separate-datasource)), and `actor-redis` its `RedisConnectionFactory`
(`spring-boot-starter-data-redis`).

### JDBC

```yaml
spring:
  datasource:
    url: jdbc:postgresql://db:5432/orders
  actor:
    name: orders
    provider: cluster
    cluster:
      join: discovery
      discovery:
        method: jdbc
    jdbc:
      initialize-schema: always   # default: embedded (only H2, HSQLDB, Derby)
    pekko:
      management:
        http:
          port: 7626
```

The entries live in the `actor_peer` table. `spring.actor.jdbc.initialize-schema` decides when the starter creates it:
`embedded` (the default) only in an embedded database, `always`, or `never`. With `never`, create it
yourself, for example in a Flyway or Liquibase migration, from the schema file for your database in the
`actor-jdbc` jar: `io/github/seonwkim/actor/jdbc/schema-{h2,postgresql,mysql,mariadb}.sql`.

With `always`, nodes that start together each run the script. On PostgreSQL they take an advisory lock
first and create the tables one at a time, since concurrent `CREATE TABLE IF NOT EXISTS` fails there. For
production, prefer `never` with a migration (Flyway, Liquibase or a one-off job), so the application's
database user needs no `CREATE` rights.

```sql
CREATE TABLE IF NOT EXISTS actor_peer (
    service_name VARCHAR(200) NOT NULL,
    node_id VARCHAR(500) NOT NULL,
    host VARCHAR(255) NOT NULL,
    port INT NOT NULL,
    last_seen BIGINT NOT NULL,
    PRIMARY KEY (service_name, node_id)
);
```

A node that crashes leaves its row behind; it is ignored once it is older than the ttl, and you can delete
old rows at any time.

`last_seen` holds the database's time in epoch milliseconds, so nodes in different time zones compare it
alike. Tables created before this version have `last_seen TIMESTAMP` (and `actor_lease.expires_at
TIMESTAMP(3)`): stop the nodes, drop `actor_peer` and `actor_lease`, and let them be created again. Both
hold only live entries, which the nodes write again within a heartbeat.

#### A separate `DataSource`

By default the JDBC stores (peers, leases, generations) and the [event journal](../data/event-sourcing.md)
share the application's `DataSource` and its pool. To give them a database, a user or a pool of their own,
declare a `DataSource` bean marked `@ActorDataSource`:

```java
@Configuration
class ActorDatabaseConfig {

    // defaultCandidate = false: Spring Boot still creates the application's DataSource from spring.datasource.*
    @Bean(defaultCandidate = false)
    @ActorDataSource
    @ConfigurationProperties("actor.datasource")
    HikariDataSource actorDataSource() {
        return DataSourceBuilder.create().type(HikariDataSource.class).build();
    }
}
```

```yaml
actor:
  datasource:
    jdbc-url: jdbc:postgresql://db:5432/actors
    username: actors
    password: ${ACTOR_DB_PASSWORD}
    maximum-pool-size: 5
```

The starter then creates its tables there, the journal's threads match that pool, and lease renewals and
heartbeats no longer compete with the application's queries for a connection. Projections stay on the
application's `DataSource`, because a projection writes its offset in the same transaction as its read model.

### Redis

```yaml
spring:
  data:
    redis:
      host: redis
  actor:
    name: orders
    provider: cluster
    cluster:
      join: discovery
      discovery:
        method: redis
```

Each node is a hash under `actor:peer:<service>:<node address>` that expires after the ttl, so a crashed
node's entry removes itself. The starter reads the keys with `SCAN`, which sees one server: use a single
Redis or a primary with replicas (Sentinel), not Redis Cluster.

### Your own store

Implement `io.github.seonwkim.actor.spi.PeerRegistry` (in `actor-api`, no Pekko types) and declare it as a
bean. Set `discovery.method: custom`. A `PeerRegistry` bean of your own also replaces the `jdbc` and `redis`
stores.

=== "Java"

    ```java
    @Bean
    PeerRegistry peerRegistry(MyStore store) {
        return new PeerRegistry() {
            public void register(Peer peer) { store.upsert(peer); }       // also marks it seen now
            public void deregister(Peer peer) { store.delete(peer); }
            public List<Peer> peers(String service, Duration ttl) { return store.seenWithin(service, ttl); }
        };
    }
    ```

=== "Kotlin"

    ```kotlin
    @Bean
    fun peerRegistry(store: MyStore): PeerRegistry = object : PeerRegistry {
        override fun register(peer: Peer) = store.upsert(peer)            // also marks it seen now
        override fun deregister(peer: Peer) = store.delete(peer)
        override fun peers(service: String, ttl: Duration): List<Peer> = store.seenWithin(service, ttl)
    }
    ```

`MyStore` stands for your own storage code. A `Peer` is the service name, the node id, and the management
host and port. `heartbeat(peer)` runs every `heartbeat-interval` and calls `register` unless you override it.

Methods may block: the starter calls them on the blocking dispatcher. The starter owns the timers and the
shutdown hook; a store only stores and reads entries. Run `PeerRegistryContract` from `actor-test` against
your store to check it behaves like the built-in ones (see [Testing](../testing.md#contract-suites-for-your-own-store)).

## Other discovery methods

`discovery.method` also accepts Pekko's own methods. Each needs its library and its Pekko settings:

| `method` | Pekko method | Library |
|---|---|---|
| `kubernetes-api` | `kubernetes-api` | `pekko-discovery-kubernetes-api_3` (the pods' service account needs RBAC to list pods) |
| `dns` | `pekko-dns` | built in |
| `config` | `config` | built in; peers under `spring.actor.pekko.discovery.config.services` |

## Who is in the cluster

`actorSystem.cluster()` tells a node who it is and who is in the cluster now:

=== "Java"

    ```java
    ClusterMember me = actorSystem.cluster().self();
    List<ClusterMember> up = actorSystem.cluster().members().stream()
            .filter(member -> member.status() == MemberStatus.UP)
            .toList();
    ```

=== "Kotlin"

    ```kotlin
    val me: ClusterMember = actorSystem.cluster().self()
    val up = actorSystem.cluster().members().filter { it.status() == MemberStatus.UP }
    ```

- `self()` is this node: its address, its roles and its status.
- `members()` lists the members this node sees, in every status, oldest first. A node that left drops out
  once it is removed.
- Each call reads the current state. To react to changes, listen for `ClusterEvent`s instead
  (`@EventListener void on(ClusterEvent.MemberUp event)`).
- `ClusterMember.host()` and `port()` read the host and port from the address (`::1`, without brackets, for
  `pekko://app@[::1]:25520`).
- Outside cluster mode, `self()` is this node with the address `nodeAddress()` returns, status `UP` and no
  roles, and `members()` lists only it.

To tell nodes apart in your own code, give each one a role and read it back, rather than working it out
from its port or address:

```yaml
spring:
  actor:
    cluster:
      roles: [zone-a]
```

```java
boolean inZoneA = actorSystem.cluster().self().roles().contains("zone-a");
```

The roles also include one that Pekko adds for the node's data center (`dc-default`), so check for the role
you set rather than comparing the whole set.

## Properties

| Key | Default | Meaning |
|---|---|---|
| `spring.actor.cluster.join` | `seed-nodes` | `seed-nodes` or `discovery` |
| `spring.actor.cluster.seed-nodes` | none | Addresses to join, as `pekko://<name>@<host>:<port>` |
| `spring.actor.cluster.discovery.method` | none | `jdbc`, `redis`, `custom`, `kubernetes-api`, `dns` or `config` |
| `spring.actor.cluster.discovery.heartbeat-interval` | `5s` | How often a node renews its entry |
| `spring.actor.cluster.discovery.ttl` | `30s` | Entries older than this are ignored |
| `spring.actor.cluster.discovery.service-name` | `spring.actor.name`, or `<name>-<color>` (see below) | The name entries are stored and looked up under |
| `spring.actor.cluster.join-self` | `false` | Form a one-node cluster without seed nodes, for local runs and tests |
| `spring.actor.cluster.roles` | none | Roles of this node, for [sharded actors](sharded-actors.md#run-entities-on-some-nodes-only) and [singletons](singletons.md#limit-it-to-some-nodes) |
| `spring.actor.jdbc.initialize-schema` | `embedded` | When to create `actor_peer` and `actor_lease`: `embedded`, `always`, `never` |

## What the nodes need

- **Management port** (Pekko's default `7626`, `spring.actor.pekko.management.http.port`): Bootstrap contacts
  peers there. `spring.actor.pekko.management.http.hostname` must be an address other nodes can reach (the
  default is the machine's address). With `jdbc` or `redis` discovery the port may be `0`: the node registers
  the port it bound.
- **Remoting port** (`spring.actor.remote.port`, default `25520`) between all nodes. Other nodes reach this one
  at `spring.actor.remote.hostname`. When that is not an address on the host, such as a StatefulSet pod's DNS
  name that may not resolve yet at startup, NAT or a load balancer, set `spring.actor.remote.bind-hostname: 0.0.0.0`
  so the node listens on every interface (`bind-port` likewise when the port is mapped).
- **The store** (database or Redis) from every node.
- The nodes of one cluster use the same `spring.actor.name`: it is the service name their entries are stored
  under. With `spring.actor.generation.color` set, `jdbc`, `redis` and `custom` store them under
  `<name>-<color>` (`orders-blue`, `orders-green`), so the two colors of a
  [blue-green deployment](../operations/deployments.md#blue-green) can share the store and still form
  separate clusters. `spring.actor.cluster.discovery.service-name` sets the name yourself.

The Kubernetes example has ready-made overlays for both stores, with the NetworkPolicies:
see `example/kubernetes/overlays/discovery-jdbc` and `discovery-redis` and the "Without RBAC" section of
its README.

Bootstrap waits for `pekko.management.cluster.bootstrap.contact-point-discovery.required-contact-point-nr`
(default `2`) peers before it forms a new cluster. Set it to `1` for a single replica.

With `join: discovery`, the split brain resolver defaults to `keep-majority`. To choose another strategy, or
to keep singletons and shards on one node with a lease, see [Split Brain and Leases](split-brain-and-leases.md).

## Securing the discovery store

With `jdbc` or `redis` discovery, the store decides which hosts a new node contacts and joins: Bootstrap
trusts every entry it reads. Leases in the same store ([Split Brain and Leases](split-brain-and-leases.md#leases))
decide which node may run a singleton or a shard, or stays up after a split. So whoever can write
`actor_peer`, `actor_lease` or the `actor:peer:` and `actor:lease:` keys can point new nodes at a host they
control, or take a lease. Treat the store as part of the cluster's trust boundary: only the nodes write to it.

Remoting (`25520`) accepts any connection unless you set up Pekko's TLS transport
(`spring.actor.pekko.remote.artery.transport: tls-tcp` and its `ssl` settings), and the management port
(`7626`) has no authentication. So the store's permissions and the network rules (only the cluster's nodes
reach these ports) are the controls.

**Redis.** Give the nodes a dedicated ACL user that can touch only the starter's keys and run only the
commands the stores use:

```text
ACL SETUSER actor-store on >change-me resetkeys ~actor:peer:* ~actor:lease:* resetchannels -@all +@connection +time +scan +hgetall +get +set +del +eval +evalsha +hset +pexpire
```

- `HSET`, `PEXPIRE`, `GET` and `DEL` also run inside the stores' Lua scripts, and Redis checks a script's
  commands against the same ACL.
- Turn on TLS (`spring.data.redis.ssl.enabled: true`) and auth (`spring.data.redis.username`, `password`).
- Don't share the Redis database with untrusted writers. `SCAN` lists key names of the whole database,
  whatever the user's key patterns.

**JDBC.** Give the nodes a database user with rights only on the two tables, and create the tables from your
migrations with another user (`spring.actor.jdbc.initialize-schema: never`):

```sql
-- PostgreSQL
CREATE ROLE actor_store LOGIN PASSWORD 'change-me';
GRANT SELECT, INSERT, UPDATE, DELETE ON actor_peer, actor_lease TO actor_store;

-- MySQL / MariaDB (orders is the schema)
CREATE USER 'actor_store'@'%' IDENTIFIED BY 'change-me';
GRANT SELECT, INSERT, UPDATE, DELETE ON orders.actor_peer TO 'actor_store'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON orders.actor_lease TO 'actor_store'@'%';
```

No user that untrusted code can reach (another service, a reporting tool, an admin console) should be able to
write these tables.

The built-in stores use the application's `DataSource` and `RedisConnectionFactory`, so these rights are the
rights of the user the application connects as. If the application needs more rights on the same server,
give the stores their own connection. For JDBC, declare an [`@ActorDataSource`](#a-separate-datasource)
connecting as a user with only these rights. For Redis, declare `PeerRegistry` and `LeaseStore` beans built
on a separate `RedisConnectionFactory` (`new RedisPeerRegistry(connectionFactory, ttl)`,
`new RedisLeaseStore(connectionFactory)`) and set `discovery.method: custom` and `lease.store: custom` (see
[Your own store](#your-own-store)).

## Related

- [Split Brain and Leases](split-brain-and-leases.md): what happens when nodes lose each other
- [Sharded Actors](sharded-actors.md) and [Cluster Singletons](singletons.md): what runs on the cluster
- [Kubernetes example](../../examples/kubernetes.md): discovery overlays and network policies
- [Testing](../testing.md#cluster-tests-with-actorcluster): several nodes in one JVM
