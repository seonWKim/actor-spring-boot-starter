package io.github.seonwkim.example.shardedchat.web;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.seonwkim.example.shardedchat.chat.InvalidFieldException;

/**
 * Reads one field of a JSON request body strictly: a number that is not whole is refused, not rounded.
 * Each problem throws {@link InvalidFieldException} naming the field.
 */
final class RequestFields {
    private RequestFields() {}

    static int integer(JsonNode body, String field) {
        JsonNode value = body == null ? null : body.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new InvalidFieldException(field, field + " must be an integer");
        }
        return value.intValue();
    }

    /** The field as a whole number, or null when it is missing. */
    static Long optionalWhole(JsonNode body, String field) {
        JsonNode value = body == null ? null : body.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new InvalidFieldException(field, field + " must be a whole number");
        }
        return value.longValue();
    }

    static double number(JsonNode body, String field) {
        JsonNode value = body == null ? null : body.get(field);
        if (value == null || !value.isNumber()) {
            throw new InvalidFieldException(field, field + " must be a number");
        }
        return value.doubleValue();
    }

    /** The field as text of 1 to maxLength characters after trimming. */
    static String text(JsonNode body, String field, int maxLength) {
        JsonNode value = body == null ? null : body.get(field);
        String text = value == null || !value.isTextual() ? "" : value.textValue().trim();
        if (text.isEmpty() || text.length() > maxLength) {
            throw new InvalidFieldException(field, field + " must be text of 1 to " + maxLength + " characters");
        }
        return text;
    }
}
