package io.github.seonwkim.example.shardedchat.control;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LogBufferTest {

    @Test
    void pastTheLimitTheOldestLinesAreDropped() {
        LogBuffer buffer = new LogBuffer(3);
        for (int i = 1; i <= 5; i++) {
            buffer.add("line " + i);
        }
        assertThat(buffer.lines()).containsExactly("line 3", "line 4", "line 5");
    }
}
