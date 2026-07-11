package com.serasa.balancas.stabilization;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StabilizationServiceTest {

    private static final String SCALE_ID = "SCALE-1";
    private static final String PLATE = "ABC1234";

    // N=5, sigma_max=2.0, M=3, reset<10kg -- small numbers keep tests readable
    private final StabilizationProperties properties = new StabilizationProperties(5, 2.0, 3, 10.0);
    private final StabilizationService service = new StabilizationService(properties);

    @BeforeEach
    void resetNothing() {
        // each test constructs its own service instance above, nothing to reset
    }

    @Test
    void doesNotStabilizeBeforeWindowFills() {
        for (int i = 0; i < properties.windowSize() - 1; i++) {
            assertThat(service.process(SCALE_ID, PLATE, 1000.0)).isEmpty();
        }
    }

    @Test
    void stabilizesAfterConsecutiveStableWindows() {
        Optional<StabilizationResult> result = Optional.empty();
        // fill window (5) + 2 more stable windows to reach M=3 consecutive stable windows
        for (int i = 0; i < properties.windowSize() + properties.consecutiveWindows() - 1; i++) {
            result = service.process(SCALE_ID, PLATE, 1000.0);
        }

        assertThat(result).isPresent();
        assertThat(result.get().scaleId()).isEqualTo(SCALE_ID);
        assertThat(result.get().plate()).isEqualTo(PLATE);
        assertThat(result.get().stabilizedWeightKg()).isEqualTo(1000.0);
    }

    @Test
    void toleratesSingleOutlierWithoutPreventingStabilization() {
        Optional<StabilizationResult> result = Optional.empty();
        double[] readings = {1000, 1000, 1000, 1000, 1000, 1050, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000};
        for (double reading : readings) {
            Optional<StabilizationResult> outcome = service.process(SCALE_ID, PLATE, reading);
            if (outcome.isPresent()) {
                result = outcome;
            }
        }

        assertThat(result).isPresent();
    }

    @Test
    void noisyWindowResetsConsecutiveCounterAndDelaysStabilization() {
        // fill + stabilize 2 windows, then inject noise across a full window, then stabilize again
        for (int i = 0; i < properties.windowSize() + 1; i++) {
            service.process(SCALE_ID, PLATE, 1000.0);
        }
        // 5 wildly noisy readings -> stdDev over the window exceeds threshold, resets counter
        double[] noisy = {900, 1100, 900, 1100, 900};
        for (double reading : noisy) {
            assertThat(service.process(SCALE_ID, PLATE, reading)).isEmpty();
        }

        // now needs M=3 more consecutive stable windows before it stabilizes again
        Optional<StabilizationResult> result = Optional.empty();
        for (int i = 0; i < properties.consecutiveWindows() + properties.windowSize() - 1; i++) {
            result = service.process(SCALE_ID, PLATE, 1000.0);
        }
        assertThat(result).isPresent();
    }

    @Test
    void doesNotPersistTwiceForSameStableSession() {
        Optional<StabilizationResult> firstStabilization = Optional.empty();
        for (int i = 0; i < properties.windowSize() + properties.consecutiveWindows() - 1; i++) {
            firstStabilization = service.process(SCALE_ID, PLATE, 1000.0);
        }
        assertThat(firstStabilization).isPresent();

        // further stable readings for the same still-stationary truck must not re-trigger persistence
        for (int i = 0; i < 5; i++) {
            assertThat(service.process(SCALE_ID, PLATE, 1000.0)).isEmpty();
        }
    }

    @Test
    void weightBelowResetThresholdClearsStateForNextTruck() {
        for (int i = 0; i < properties.windowSize() + properties.consecutiveWindows() - 1; i++) {
            service.process(SCALE_ID, PLATE, 1000.0);
        }

        // truck leaves the scale
        assertThat(service.process(SCALE_ID, PLATE, 5.0)).isEmpty();

        // new truck settles and stabilizes again -- must not be blocked by the old alreadyPersisted flag
        Optional<StabilizationResult> result = Optional.empty();
        for (int i = 0; i < properties.windowSize() + properties.consecutiveWindows() - 1; i++) {
            result = service.process(SCALE_ID, "XYZ9999", 2000.0);
        }
        assertThat(result).isPresent();
        assertThat(result.get().plate()).isEqualTo("XYZ9999");
        assertThat(result.get().stabilizedWeightKg()).isEqualTo(2000.0);
    }

    @Test
    void secondSessionOnSameScaleStabilizesIndependentlyAfterReset() {
        String plateA = "AAA1111";
        String plateB = "BBB2222";

        Optional<StabilizationResult> firstStabilization = Optional.empty();
        for (int i = 0; i < properties.windowSize() + properties.consecutiveWindows() - 1; i++) {
            Optional<StabilizationResult> outcome = service.process(SCALE_ID, plateA, 1000.0);
            if (outcome.isPresent()) {
                firstStabilization = outcome;
            }
        }
        assertThat(firstStabilization).isPresent();
        assertThat(firstStabilization.get().plate()).isEqualTo(plateA);
        assertThat(firstStabilization.get().stabilizedWeightKg()).isEqualTo(1000.0);

        // truck A leaves the scale -- clears buffer and re-arms alreadyPersisted
        assertThat(service.process(SCALE_ID, plateA, 5.0)).isEmpty();

        Optional<StabilizationResult> secondStabilization = Optional.empty();
        for (int i = 0; i < properties.windowSize() + properties.consecutiveWindows() - 1; i++) {
            Optional<StabilizationResult> outcome = service.process(SCALE_ID, plateB, 3000.0);
            if (outcome.isPresent()) {
                secondStabilization = outcome;
            }
        }

        // exactly 3000.0, not blended with any leftover reading from truck A's buffer
        assertThat(secondStabilization).isPresent();
        assertThat(secondStabilization.get().plate()).isEqualTo(plateB);
        assertThat(secondStabilization.get().stabilizedWeightKg()).isEqualTo(3000.0);

        // alreadyPersisted re-armed correctly means it's now guarding truck B's session
        for (int i = 0; i < 5; i++) {
            assertThat(service.process(SCALE_ID, plateB, 3000.0)).isEmpty();
        }
    }

    @Test
    void continuouslyOscillatingWeightNeverStabilizes() {
        double[] oscillating = new double[25];
        for (int i = 0; i < oscillating.length; i++) {
            oscillating[i] = (i % 2 == 0) ? 900.0 : 1100.0;
        }
        for (double reading : oscillating) {
            assertThat(service.process(SCALE_ID, PLATE, reading)).isEmpty();
        }
    }

    @Test
    void concurrentReadingsOnSameScaleStabilizeExactlyOnce() throws InterruptedException {
        int threadCount = 20;
        int readingsPerThread = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        ConcurrentLinkedQueue<StabilizationResult> results = new ConcurrentLinkedQueue<>();

        try {
            for (int t = 0; t < threadCount; t++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < readingsPerThread; i++) {
                            service.process(SCALE_ID, PLATE, 1000.0).ifPresent(results::add);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown(); // release all threads at once to maximize overlap
            assertThat(doneLatch.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdown();
        }

        // every reading is the same constant, so stdDev is always 0 regardless of interleaving order --
        // compute()'s per-key atomicity must still guarantee exactly one persistence, not zero or many
        assertThat(results).hasSize(1);
        assertThat(results.peek().scaleId()).isEqualTo(SCALE_ID);
        assertThat(results.peek().stabilizedWeightKg()).isEqualTo(1000.0);
    }

    @Test
    void concurrentReadingsOnDifferentScalesStabilizeIndependentlyWithoutCrossContamination() throws InterruptedException {
        int scaleCount = 10;
        int readingsPerScale = properties.windowSize() + properties.consecutiveWindows() - 1 + 15;
        ExecutorService executor = Executors.newFixedThreadPool(scaleCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(scaleCount);
        ConcurrentLinkedQueue<StabilizationResult> results = new ConcurrentLinkedQueue<>();
        List<String> scaleIds = IntStream.range(0, scaleCount)
                .mapToObj(i -> "SCALE-" + i)
                .collect(Collectors.toList());

        try {
            for (String scaleId : scaleIds) {
                double weightForScale = 1000.0 + (scaleIds.indexOf(scaleId) * 100.0);
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < readingsPerScale; i++) {
                            service.process(scaleId, PLATE, weightForScale).ifPresent(results::add);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown(); // release all scale threads at once for true parallelism
            assertThat(doneLatch.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdown();
        }

        // exactly one persistence per scale, each with its own weight -- no blending across scales' buffers
        assertThat(results).hasSize(scaleCount);
        for (String scaleId : scaleIds) {
            double expectedWeight = 1000.0 + (scaleIds.indexOf(scaleId) * 100.0);
            StabilizationResult resultForScale = results.stream()
                    .filter(r -> r.scaleId().equals(scaleId))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("No result for " + scaleId));
            assertThat(resultForScale.stabilizedWeightKg()).isEqualTo(expectedWeight);
        }
    }

    @Test
    void independentScalesMaintainIndependentState() {
        for (int i = 0; i < properties.windowSize() - 1; i++) {
            service.process("SCALE-A", PLATE, 1000.0);
        }
        // SCALE-A is one reading short of filling its window
        Optional<StabilizationResult> scaleBResult = Optional.empty();
        for (int i = 0; i < properties.windowSize() + properties.consecutiveWindows() - 1; i++) {
            scaleBResult = service.process("SCALE-B", PLATE, 2000.0);
        }

        assertThat(scaleBResult).isPresent();
        assertThat(service.process("SCALE-A", PLATE, 1000.0)).isEmpty(); // still filling, unaffected by SCALE-B
    }
}
