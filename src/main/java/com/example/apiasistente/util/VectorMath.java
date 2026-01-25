package com.example.apiasistente.util;

public class VectorMath {

    public static double cosine(double[] a, double[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) return -1.0;

        double dot = 0.0, na = 0.0, nb = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) return -1.0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
