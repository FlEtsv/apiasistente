package com.example.apiasistente.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class TextChunkerTest {

    @Test
    void chunkSplitsTextWithOverlap() {
        // Ensures we keep the requested overlap between chunks.
        List<String> chunks = TextChunker.chunk("abcdef", 4, 1);

        assertThat(chunks).containsExactly("abcd", "def");
    }

    @Test
    void chunkHandlesInvalidSizesSafely() {
        // Invalid chunk sizes should produce no output rather than looping.
        List<String> chunks = TextChunker.chunk("abcdef", 0, 3);

        assertThat(chunks).isEmpty();
    }

    @Test
    void chunkClampsExcessiveOverlap() {
        // Overlap larger than chunk size should be clamped to avoid infinite loops.
        List<String> chunks = TextChunker.chunk("abcdef", 3, 10);

        assertThat(chunks).containsExactly("abc", "cde", "ef");
    }
}
