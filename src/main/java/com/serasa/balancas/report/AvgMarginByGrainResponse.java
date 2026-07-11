package com.serasa.balancas.report;

import java.math.BigDecimal;

public record AvgMarginByGrainResponse(Long grainTypeId, String grainTypeName, BigDecimal margin) {
}
