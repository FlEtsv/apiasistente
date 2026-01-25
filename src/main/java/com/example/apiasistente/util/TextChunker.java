package com.example.apiasistente.util;

import java.util.ArrayList;
import java.util.List;

public class TextChunker {

    public static List<String> chunk(String text, int chunkSize, int overlap) {
        String clean = text == null ? "" : text.trim();
        List<String> out = new ArrayList<>();
        if (clean.isEmpty()) return out;

        int start = 0;
        while (start < clean.length()) {
            int end = Math.min(clean.length(), start + chunkSize);
            String piece = clean.substring(start, end).trim();
            if (!piece.isEmpty()) out.add(piece);

            if (end == clean.length()) break;
            start = Math.max(0, end - overlap);
        }
        return out;
    }
}
