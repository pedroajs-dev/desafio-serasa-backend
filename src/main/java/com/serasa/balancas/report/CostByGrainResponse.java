package com.serasa.balancas.report;

public record CostByGrainResponse(Long grainTypeId, String grainTypeName, Double totalLoadCost) {
}
