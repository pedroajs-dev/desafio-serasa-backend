package com.serasa.balancas.transporttransaction;

import java.time.LocalDateTime;

public record TransportTransactionResponse(
        Long id,
        Long truckId,
        Long grainTypeId,
        Long branchId,
        TransactionStatus status,
        LocalDateTime startDate,
        LocalDateTime endDate,
        Double grossWeightKg,
        Double netWeightKg,
        Double loadCost) {

    public static TransportTransactionResponse from(TransportTransaction transaction) {
        return new TransportTransactionResponse(
                transaction.getId(),
                transaction.getTruck().getId(),
                transaction.getGrainType().getId(),
                transaction.getBranch().getId(),
                transaction.getStatus(),
                transaction.getStartDate(),
                transaction.getEndDate(),
                transaction.getGrossWeightKg(),
                transaction.getNetWeightKg(),
                transaction.getLoadCost());
    }
}
