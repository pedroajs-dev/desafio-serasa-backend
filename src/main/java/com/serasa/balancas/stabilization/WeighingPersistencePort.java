package com.serasa.balancas.stabilization;

/**
 * Extension point for Story 3.2: persists a WeighingRecord and closes the
 * matching TransportTransaction once StabilizationService reports a stable weight.
 * Not implemented or wired in Story 3.1.
 */
public interface WeighingPersistencePort {

    void persist(StabilizationResult result);
}
