package com.example.apiasistente.util;

import java.util.ArrayList;
import java.util.List;

public class TextChunker {

    public static List<String> chunk(String text, int chunkSize, int overlap) {
        String clean = text == null ? "" : text.trim();
        List<String> out = new ArrayList<>();
        if (clean.isEmpty() || chunkSize <= 0) {
            // Nothing to chunk when input is empty or chunk size is invalid.
            return out;
        }

        // Prevent infinite loops when overlap is negative or too large.
        int safeOverlap = Math.max(0, overlap);
        if (safeOverlap >= chunkSize) {
            safeOverlap = Math.max(0, chunkSize - 1);
        }

        int start = 0;
        while (start < clean.length()) {
            int end = Math.min(clean.length(), start + chunkSize);
            String piece = clean.substring(start, end).trim();
            if (!piece.isEmpty()) out.add(piece);

            if (end == clean.length()) break;
            start = Math.max(0, end - safeOverlap);
        }
        return out;
    }
}
