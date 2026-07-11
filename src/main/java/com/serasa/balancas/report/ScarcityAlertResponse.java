package com.serasa.balancas.report;

import java.math.BigDecimal;

public record ScarcityAlertResponse(
        Long grainTypeId,
        String grainTypeName,
        BigDecimal margin,
        Double currentStock,
        Double maxReferenceStock) {
}
