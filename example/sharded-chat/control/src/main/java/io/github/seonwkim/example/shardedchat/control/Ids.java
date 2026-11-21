package io.github.seonwkim.example.shardedchat.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Both colors run the same chat, so both have a {@code chat-user-1}. The page sees every id with its
 * color, {@code chat-user-1@green}, and a request for such an id goes to that color.
 */
public final class Ids {

    private static final Pattern ID = Pattern.compile("\\b(chat-(?:room|user)-\\d+|chat-simulator)\\b(?!@)");

    private Ids() {}

    /** Every chat id in the text, with {@code @color} after it (ids that have a color keep theirs). */
    public static String qualify(String text, String color) {
        return ID.matcher(text).replaceAll("$1@" + color);
    }

    /** The same, for every text value in a JSON tree, in place; returns the tree. */
    public static JsonNode qualify(JsonNode node, String color) {
        if (node instanceof ObjectNode object) {
            for (Map.Entry<String, JsonNode> field : object.properties()) {
                if (field.getValue().isTextual()) {
                    field.setValue(TextNode.valueOf(qualify(field.getValue().asText(), color)));
                } else {
                    qualify(field.getValue(), color);
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                if (array.get(i).isTextual()) {
                    array.set(i, TextNode.valueOf(qualify(array.get(i).asText(), color)));
                } else {
                    qualify(array.get(i), color);
                }
            }
        }
        return node;
    }

    /** The id as the chat nodes know it: without its color. */
    public static String bare(String id) {
        int at = id.indexOf('@');
        return at < 0 ? id : id.substring(0, at);
    }

    /** The color an id names, if any. */
    public static Optional<String> colorOf(String id) {
        int at = id.indexOf('@');
        return at < 0 ? Optional.empty() : Optional.of(id.substring(at + 1));
    }
}
