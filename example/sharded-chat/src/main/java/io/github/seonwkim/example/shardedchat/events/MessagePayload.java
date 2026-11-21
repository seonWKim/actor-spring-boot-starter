package io.github.seonwkim.example.shardedchat.events;

import io.github.seonwkim.actor.AskCommand;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;

/**
 * The payload of a message event, built from the message an actor really sends: its type name and its
 * fields, e.g. {@code Post{from=chat-user-3, text=hello}}. A record shows its components, any other
 * class its own instance fields (an ask's reply-to is left out); a string, number, boolean or enum is
 * shown as it is. A payload longer than {@link #MAX_LENGTH} characters is cut, ending in {@code …}.
 */
public final class MessagePayload {
    public static final int MAX_LENGTH = 200;
    private static final String CUT = "…";

    private MessagePayload() {}

    /** The simple class name of the message, e.g. {@code Post}. */
    public static String typeOf(Object message) {
        return message == null ? "null" : message.getClass().getSimpleName();
    }

    public static String of(Object message) {
        String text = plain(message)
                ? String.valueOf(message)
                : typeOf(message) + "{" + String.join(", ", fields(message)) + "}";
        return text.length() <= MAX_LENGTH ? text : text.substring(0, MAX_LENGTH - CUT.length()) + CUT;
    }

    private static boolean plain(Object value) {
        return value == null
                || value instanceof CharSequence
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Enum<?>;
    }

    private static List<String> fields(Object message) {
        List<String> parts = new ArrayList<>();
        Class<?> type = message.getClass();
        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                try {
                    parts.add(component.getName() + "=" + component.getAccessor().invoke(message));
                } catch (ReflectiveOperationException e) {
                    parts.add(component.getName() + "=?");
                }
            }
            return parts;
        }
        for (Class<?> c = type; c != null && c != Object.class && c != AskCommand.class; c = c.getSuperclass()) {
            List<String> own = new ArrayList<>();
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    own.add(f.getName() + "=" + f.get(message));
                } catch (ReflectiveOperationException | RuntimeException e) {
                    own.add(f.getName() + "=?");
                }
            }
            parts.addAll(0, own);
        }
        return parts;
    }
}
