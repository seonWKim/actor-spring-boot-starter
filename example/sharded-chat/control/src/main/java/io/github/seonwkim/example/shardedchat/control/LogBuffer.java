package io.github.seonwkim.example.shardedchat.control;

import java.util.ArrayDeque;
import java.util.List;

/** The last lines of one process's output, in memory only: past the limit the oldest line is dropped. */
public final class LogBuffer {

    /** Lines kept per process. */
    public static final int LINES = 2000;

    private final int limit;
    private final ArrayDeque<String> lines;

    public LogBuffer() {
        this(LINES);
    }

    LogBuffer(int limit) {
        this.limit = limit;
        this.lines = new ArrayDeque<>(limit);
    }

    public synchronized void add(String line) {
        if (lines.size() == limit) {
            lines.removeFirst();
        }
        lines.addLast(line);
    }

    /** The kept lines, oldest first. */
    public synchronized List<String> lines() {
        return List.copyOf(lines);
    }
}
