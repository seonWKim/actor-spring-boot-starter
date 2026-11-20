package io.github.seonwkim.actor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.pekko.cluster.MemberStatus;
import org.apache.pekko.cluster.typed.Cluster;
import org.apache.pekko.cluster.typed.Join;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

/** {@code singletonHandle()}: a handle usable at once, before the singleton runs. */
class SingletonHandleTest {

    /** Not annotated with {@code @Configuration}, so other tests' component scans skip it. */
    @EnableActorSupport
    static class App {
        @Bean
        Received received() {
            return new Received();
        }

        @Bean
        RecordingActor recordingActor(Received received) {
            return new RecordingActor(received);
        }
    }

    /** The messages the singleton has handled. */
    static class Received {
        final List<String> messages = new CopyOnWriteArrayList<>();
    }

    static class RecordingActor implements SpringActor<RecordingActor.Command> {
        public interface Command {}

        public record Record(String text) implements Command {}

        private final Received received;

        RecordingActor(Received received) {
            this.received = received;
        }

        @Override
        public SpringActorBehavior<Command> create(SpringActorContext actorContext) {
            return SpringActorBehavior.builder(Command.class, actorContext)
                    .onMessage(Record.class, (ctx, msg) -> {
                        received.messages.add(msg.text());
                        return SpringActorBehavior.same();
                    })
                    .build();
        }
    }

    private ConfigurableApplicationContext context;

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void aMessageSentBeforeTheSingletonStartsIsDeliveredOnceItStarts() {
        context = startOutsideTheCluster();
        SpringActorSystem system = context.getBean(SpringActorSystem.class);
        Cluster cluster = system.unwrap(Cluster.class);

        SpringActorHandle<RecordingActor.Command> singleton =
                system.actor(RecordingActor.class).withId("recorder").singletonHandle();
        singleton.tell(new RecordingActor.Record("early"));

        // The singleton can't run yet: no member is up.
        assertThat(cluster.selfMember().status()).isNotEqualTo(MemberStatus.up());
        assertThat(context.getBean(Received.class).messages).isEmpty();

        cluster.manager().tell(Join.create(cluster.selfMember().address()));

        await().atMost(Duration.ofSeconds(20))
                .until(() -> context.getBean(Received.class).messages.equals(List.of("early")));
    }

    @Test
    void findSeesASingletonStartedThroughItsHandle() {
        context = startOutsideTheCluster();
        SpringActorSystem system = context.getBean(SpringActorSystem.class);

        system.actor(RecordingActor.class).withId("found").singletonHandle();

        await().atMost(Duration.ofSeconds(5)).until(() -> system.find(RecordingActor.class, "found")
                .toCompletableFuture()
                .get()
                .isPresent());
    }

    /** No seed nodes and no join-self: the node stays out of the cluster until a test joins it. */
    private static ConfigurableApplicationContext startOutsideTheCluster() {
        return new SpringApplicationBuilder(App.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "spring.actor.pekko.name=singleton-handle",
                        "spring.actor.pekko.actor.provider=cluster",
                        "spring.actor.pekko.remote.artery.canonical.hostname=127.0.0.1",
                        "spring.actor.pekko.remote.artery.canonical.port=0",
                        "spring.actor.pekko.actor.allow-java-serialization=off",
                        "spring.actor.shutdown.exit-process=false")
                .run();
    }
}
