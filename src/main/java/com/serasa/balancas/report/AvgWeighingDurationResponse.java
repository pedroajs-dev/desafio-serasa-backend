package com.serasa.balancas.report;

public record AvgWeighingDurationResponse(Long branchId, String branchName, Double avgDurationSeconds) {
}
