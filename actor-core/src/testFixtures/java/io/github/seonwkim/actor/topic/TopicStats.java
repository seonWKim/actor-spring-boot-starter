package io.github.seonwkim.actor.topic;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.function.Predicate;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Scheduler;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.apache.pekko.actor.typed.pubsub.Topic;

/**
 * Waits for a topic's subscriptions to take effect. Subscribing and unsubscribing are messages to the
 * topic actor, so a publish right after may not reach (or may still reach) the subscriber; tests wait
 * on the topic's own statistics instead of sleeping.
 */
public final class TopicStats {

    private TopicStats() {}

    /** Waits until this node's instance of {@code topic} has {@code count} subscribers. */
    public static void awaitLocalSubscribers(SpringTopicRef<?> topic, Scheduler scheduler, int count) {
        waitFor(topic, scheduler, stats -> stats.localSubscriberCount() == count);
    }

    /** Waits until {@code topic} knows of {@code count} instances (one per node with a subscriber). */
    public static void awaitInstances(SpringTopicRef<?> topic, Scheduler scheduler, int count) {
        waitFor(topic, scheduler, stats -> stats.topicInstanceCount() == count);
    }

    @SuppressWarnings("unchecked")
    private static void waitFor(SpringTopicRef<?> topic, Scheduler scheduler, Predicate<Topic.TopicStats> done) {
        ActorRef<Topic.Command<Object>> ref = topic.unwrap(ActorRef.class);
        await().atMost(Duration.ofSeconds(10))
                .until(() -> done.test(AskPattern.<Topic.Command<Object>, Topic.TopicStats>ask(
                                ref, Topic::getTopicStats, Duration.ofSeconds(3), scheduler)
                        .toCompletableFuture()
                        .get()));
    }
}
