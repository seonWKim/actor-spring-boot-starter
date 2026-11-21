package io.github.seonwkim.example.shardedchat.chat;

import io.github.seonwkim.actor.serialization.JsonSerializable;
import java.time.Duration;

/**
 * The settings of the simulation: the most chat rooms at a time ({@code rooms}, 0 to 50), how many
 * users ({@code users}, 0 to 1000), the messages a second all users post together ({@code messageRate},
 * 0 to 100; at 0 nobody posts) and the share of posts a room drops without a reply ({@code
 * timeoutShare}, 0 to 1). A value out of range throws {@link InvalidFieldException} naming the field.
 *
 * <p>It is a reply of the simulator, which may run on another node, so it is {@link JsonSerializable}.
 */
public record ChatSettings(int rooms, int users, double messageRate, double timeoutShare) implements JsonSerializable {
    public static final int MAX_ROOMS = 50;
    public static final int MAX_USERS = 1000;
    public static final double MAX_MESSAGE_RATE = 100;

    public ChatSettings {
        requireRooms(rooms);
        requireUsers(users);
        requireMessageRate(messageRate);
        if (!(timeoutShare >= 0 && timeoutShare <= 1)) {
            throw new InvalidFieldException("timeoutShare", "timeoutShare must be between 0 and 1");
        }
    }

    public static int requireRooms(int rooms) {
        if (rooms < 0 || rooms > MAX_ROOMS) {
            throw new InvalidFieldException("rooms", "rooms must be between 0 and " + MAX_ROOMS);
        }
        return rooms;
    }

    public static int requireUsers(int users) {
        if (users < 0 || users > MAX_USERS) {
            throw new InvalidFieldException("users", "users must be between 0 and " + MAX_USERS);
        }
        return users;
    }

    public static double requireMessageRate(double messageRate) {
        if (!(messageRate >= 0 && messageRate <= MAX_MESSAGE_RATE)) {
            throw new InvalidFieldException(
                    "messageRate", "messageRate must be between 0 and " + (int) MAX_MESSAGE_RATE);
        }
        return messageRate;
    }

    /** These settings with each given (non-null) value in place of its own. */
    public ChatSettings with(Integer rooms, Integer users, Double messageRate) {
        return new ChatSettings(
                rooms == null ? this.rooms : rooms,
                users == null ? this.users : users,
                messageRate == null ? this.messageRate : messageRate,
                timeoutShare);
    }

    /** The mean time between one user's posts, so that all users together post messageRate a second; zero for none. */
    public Duration pace() {
        if (users == 0 || messageRate == 0) {
            return Duration.ZERO;
        }
        return Duration.ofMillis(Math.max(1, Math.round(1000 * users / messageRate)));
    }
}
