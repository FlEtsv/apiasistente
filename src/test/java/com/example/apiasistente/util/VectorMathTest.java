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

    @Test
    void normalizeReturnsUnitVector() {
        double[] normalized = VectorMath.normalize(new double[] {3, 4});

        assertThat(normalized[0]).isCloseTo(0.6, within(0.0001));
        assertThat(normalized[1]).isCloseTo(0.8, within(0.0001));
    }

    @Test
    void cosineUnitUsesDotProductForUnitVectors() {
        double[] v1 = VectorMath.normalize(new double[] {1, 1});
        double[] v2 = VectorMath.normalize(new double[] {1, 0});

        double similarity = VectorMath.cosineUnit(v1, v2);

        assertThat(similarity).isCloseTo(0.7071, within(0.0001));
    }
}
