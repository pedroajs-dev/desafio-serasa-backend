package com.serasa.balancas.stabilization;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;

@Service
public class StabilizationService {

    private final StabilizationProperties properties;
    private final ConcurrentHashMap<String, ScaleState> states = new ConcurrentHashMap<>();

    public StabilizationService(StabilizationProperties properties) {
        this.properties = properties;
    }

    public Optional<StabilizationResult> process(String scaleId, String plate, double weightKg) {
        AtomicReference<StabilizationResult> ready = new AtomicReference<>();

        states.compute(scaleId, (id, existing) -> {
            ScaleState state = existing != null ? existing : new ScaleState();

            if (weightKg < properties.resetThresholdKg()) {
                state.reset();
                return state;
            }

            state.addReading(weightKg, properties.windowSize());
            if (state.buffer().size() < properties.windowSize()) {
                return state;
            }

            if (state.stdDev() <= properties.stdDevThreshold()) {
                state.consecutiveStableWindows++;
            } else {
                state.consecutiveStableWindows = 0;
            }

            if (state.consecutiveStableWindows >= properties.consecutiveWindows() && !state.alreadyPersisted) {
                state.alreadyPersisted = true;
                ready.set(StabilizationResult.of(scaleId, plate, state.average()));
            }

            return state;
        });

        return Optional.ofNullable(ready.get());
    }
}
