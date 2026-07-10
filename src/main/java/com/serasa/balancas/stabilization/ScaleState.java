package com.serasa.balancas.stabilization;

import java.util.ArrayDeque;
import java.util.Deque;

class ScaleState {

    private final Deque<Double> buffer = new ArrayDeque<>();
    int consecutiveStableWindows = 0;
    boolean alreadyPersisted = false;

    void addReading(double weightKg, int windowSize) {
        buffer.addLast(weightKg);
        if (buffer.size() > windowSize) {
            buffer.removeFirst();
        }
    }

    Deque<Double> buffer() {
        return buffer;
    }

    void reset() {
        buffer.clear();
        consecutiveStableWindows = 0;
        alreadyPersisted = false;
    }

    double average() {
        return buffer.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    double stdDev() {
        double mean = average();
        double variance = buffer.stream()
                .mapToDouble(w -> (w - mean) * (w - mean))
                .average()
                .orElse(0.0);
        return Math.sqrt(variance);
    }
}
