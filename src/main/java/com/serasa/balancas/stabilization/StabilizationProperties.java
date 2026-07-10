package com.serasa.balancas.stabilization;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "stabilization")
public record StabilizationProperties(
        int windowSize,
        double stdDevThreshold,
        int consecutiveWindows,
        double resetThresholdKg
) {
}
