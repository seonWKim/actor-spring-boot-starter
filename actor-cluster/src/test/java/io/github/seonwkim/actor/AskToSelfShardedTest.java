package io.github.seonwkim.actor;

import static org.awaitility.Awaitility.await;

import io.github.seonwkim.actor.fixture.TestShardedActor;
import io.github.seonwkim.actor.shard.SpringShardedActorHandle;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

/** {@code askToSelf} with a sharded entity as the target: its reply comes back as a message. */
class AskToSelfShardedTest {

    /** Not annotated with {@code @Configuration}, so other tests' component scans skip it. */
    @EnableActorSupport
    static class App {
        @Bean
        TestShardedActor testShardedActor() {
            return new TestShardedActor();
        }

        @Bean
        Received received() {
            return new Received();
        }

        @Bean
        AskingActor askingActor(Received received) {
            return new AskingActor(received);
        }
    }

    static class Received {
        final List<String> messages = new CopyOnWriteArrayList<>();
    }

    static class AskingActor implements SpringActor<AskingActor.Command> {
        public interface Command {}

        public record AskEntity(SpringShardedActorHandle<TestShardedActor.Command> entity) implements Command {}

        public record Answered(String text) implements Command {}

        private final Received received;

        AskingActor(Received received) {
            this.received = received;
        }

        @Override
        public SpringActorBehavior<Command> create(SpringActorContext actorContext) {
            return SpringActorBehavior.builder(Command.class, actorContext)
                    .onMessage(AskEntity.class, (ctx, msg) -> {
                        ctx.askToSelf(
                                msg.entity(),
                                new TestShardedActor.GetState(),
                                Duration.ofSeconds(5),
                                (state, error) -> new Answered(
                                        error == null ? "count:" + state.getMessageCount() : "error:" + error));
                        return SpringActorBehavior.same();
                    })
                    .onMessage(Answered.class, (ctx, msg) -> {
                        received.messages.add(msg.text());
                        return SpringActorBehavior.same();
                    })
                    .build();
        }
    }

    @Test
    void theReplyOfAShardedEntityComesBackAsAMessage() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(App.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "spring.actor.pekko.name=ask-to-self-sharded",
                        "spring.actor.pekko.actor.provider=cluster",
                        "spring.actor.pekko.remote.artery.canonical.hostname=127.0.0.1",
                        "spring.actor.pekko.remote.artery.canonical.port=0",
                        "spring.actor.pekko.actor.allow-java-serialization=off",
                        "spring.actor.cluster.join-self=true",
                        "spring.actor.shutdown.exit-process=false")
                .run()) {
            SpringActorSystem system = context.getBean(SpringActorSystem.class);
            SpringShardedActorHandle<TestShardedActor.Command> entity =
                    system.sharded(TestShardedActor.class).withId("entity-1").get();
            entity.tell(new TestShardedActor.Ping("one"));
            SpringActorHandle<AskingActor.Command> asker = system.actor(AskingActor.class)
                    .withId("asker")
                    .spawn()
                    .toCompletableFuture()
                    .join();

            asker.tell(new AskingActor.AskEntity(entity));

            await().atMost(Duration.ofSeconds(20))
                    .until(() -> context.getBean(Received.class).messages.equals(List.of("count:1")));
        }
    }
}
