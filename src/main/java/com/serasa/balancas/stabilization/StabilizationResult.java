package com.serasa.balancas.stabilization;

public record StabilizationResult(String scaleId, String plate, double stabilizedWeightKg) {

    public static StabilizationResult of(String scaleId, String plate, double stabilizedWeightKg) {
        return new StabilizationResult(scaleId, plate, stabilizedWeightKg);
    }
}
