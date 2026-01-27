package com.example.apiasistente.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class VectorMathTest {

    @Test
    void cosineReturnsExpectedSimilarity() {
        // Colinear vectors should produce a similarity close to 1.0.
        double similarity = VectorMath.cosine(new double[] {1, 0}, new double[] {0.5, 0});

        assertThat(similarity).isCloseTo(1.0, within(0.0001));
    }

    @Test
    void cosineReturnsMinusOneOnInvalidInput() {
        // Empty vectors are treated as invalid input.
        double similarity = VectorMath.cosine(new double[] {}, new double[] {});

        assertThat(similarity).isEqualTo(-1.0);
    }
}
