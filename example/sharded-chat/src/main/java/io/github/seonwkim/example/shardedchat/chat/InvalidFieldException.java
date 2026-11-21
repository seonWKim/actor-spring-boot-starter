package io.github.seonwkim.example.shardedchat.chat;

/** A request value that is missing or out of range; {@link #field()} names it. Answered with 400. */
public class InvalidFieldException extends IllegalArgumentException {
    private final String field;

    public InvalidFieldException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
