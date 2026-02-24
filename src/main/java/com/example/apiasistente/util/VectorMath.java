package com.example.apiasistente.util;

public class VectorMath {

    private VectorMath() {
    }

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

    /**
     * Normaliza el vector a norma 1 (unit vector).
     * Si no se puede normalizar, devuelve arreglo vacio.
     */
    public static double[] normalize(double[] vector) {
        if (vector == null || vector.length == 0) {
            return new double[0];
        }

        double normSquared = 0.0;
        for (double v : vector) {
            normSquared += v * v;
        }
        if (normSquared <= 0.0 || !Double.isFinite(normSquared)) {
            return new double[0];
        }

        double norm = Math.sqrt(normSquared);
        double[] normalized = new double[vector.length];
        for (int i = 0; i < vector.length; i++) {
            normalized[i] = vector[i] / norm;
        }
        return normalized;
    }

    /**
     * Similaridad para vectores unitarios.
     * Equivale al coseno y evita recalcular norma por candidato.
     */
    public static double cosineUnit(double[] a, double[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return -1.0;
        }

        double dot = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        return Double.isFinite(dot) ? dot : -1.0;
    }
}
