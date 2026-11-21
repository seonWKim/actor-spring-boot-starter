package io.github.seonwkim.example.shardedchat.chat;

import io.github.seonwkim.actor.AskCommand;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import org.springframework.stereotype.Service;

/**
 * What the HTTP API does with the chat, from any node: asks the simulator singleton for the state and
 * changes, and sends the page's messages straight to a user or a room entity, wherever it runs. Every
 * call answers as a stage, so no request thread waits on an actor.
 */
@Service
public class ChatService {
    /** The sender of the page's messages. */
    public static final String FROM_PAGE = "page";

    public static final int MAX_TEXT = 200;
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    /** Where a message from the page went: the users it reached, and whether the target answered in time. */
    public record Delivery(String target, List<String> delivered, boolean replied) {}

    /** The simulator singleton did not answer in time: it is still starting, or moving to another node. */
    public static class NotReadyException extends RuntimeException {
        public NotReadyException() {
            super("the chat simulator is starting; try again in a moment");
        }
    }

    private final ChatCluster cluster;

    public ChatService(ChatCluster cluster) {
        this.cluster = cluster;
    }

    public CompletionStage<ChatSimulatorActor.ChatState> state() {
        return askSimulator(new ChatSimulatorActor.GetState());
    }

    /** Changes the given (non-null) settings at once; a value out of range throws {@link InvalidFieldException}. */
    public CompletionStage<ChatSettings> update(Integer rooms, Integer users, Double messageRate) {
        if (rooms != null) {
            ChatSettings.requireRooms(rooms);
        }
        if (users != null) {
            ChatSettings.requireUsers(users);
        }
        if (messageRate != null) {
            ChatSettings.requireMessageRate(messageRate);
        }
        return askSimulator(new ChatSimulatorActor.Update(rooms, users, messageRate));
    }

    /** Opens one room now; the stage fails with {@link InvalidFieldException} when it cannot. */
    public CompletionStage<ChatSimulatorActor.RoomAdded> addRoom(Long number) {
        return askSimulator(new ChatSimulatorActor.AddRoom(number)).thenApply(added -> {
            if (added.refused() != null) {
                throw new InvalidFieldException(number == null ? "rooms" : "number", added.refused());
            }
            return added;
        });
    }

    /** Sends text from the page to one user of the run. */
    public CompletionStage<Delivery> send(String userId, String text) {
        if (userId.startsWith(ChatSimulatorActor.ROOM_PREFIX)) {
            throw new InvalidFieldException("to", userId + " is a room: broadcast to its members instead");
        }
        return known(userId, "to")
                .thenCompose(ignored -> replied(
                        userId,
                        cluster.user(userId)
                                .ask(new ChatUserActor.Direct(FROM_PAGE, text))
                                .withTimeout(ChatUserActor.REPLY_TIMEOUT)
                                .execute(),
                        ok -> List.of(userId)));
    }

    /** Sends text from the page to every member of a room of the run. */
    public CompletionStage<Delivery> broadcast(String roomId, String text) {
        return known(roomId, "room")
                .thenCompose(ignored -> replied(
                        roomId,
                        cluster.room(roomId)
                                .ask(new ChatRoomActor.Broadcast(FROM_PAGE, text))
                                .withTimeout(ChatUserActor.REPLY_TIMEOUT)
                                .execute(),
                        ChatRoomActor.Reached::userIds));
    }

    // A message to an id outside the run would start an entity the simulator then stops: refuse it.
    private CompletionStage<Boolean> known(String id, String field) {
        return askSimulator(new ChatSimulatorActor.Has(id)).thenApply(known -> {
            if (!known) {
                String kind = id.startsWith(ChatSimulatorActor.ROOM_PREFIX) ? "room" : "user";
                throw new InvalidFieldException(field, "Unknown " + kind + ": " + id);
            }
            return true;
        });
    }

    private static <R> CompletionStage<Delivery> replied(
            String target, CompletionStage<R> reply, Function<R, List<String>> delivered) {
        return reply.handle((value, error) -> error == null
                ? new Delivery(target, delivered.apply(value), true)
                : new Delivery(target, List.of(), false));
    }

    // The handle is usable at once; until the singleton runs, what it is sent waits in the proxy, so an
    // ask that is not answered in time means the simulator is not running yet.
    private <R> CompletionStage<R> askSimulator(AskCommand<R> command) {
        return cluster.simulator()
                .ask(command)
                .withTimeout(TIMEOUT)
                .execute()
                .exceptionallyCompose(error -> CompletableFuture.failedFuture(
                        unwrap(error) instanceof TimeoutException ? new NotReadyException() : error));
    }

    private static Throwable unwrap(Throwable error) {
        return error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
    }
}
