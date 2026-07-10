package com.serasa.balancas.transporttransaction;

import jakarta.validation.constraints.NotNull;

public record TransportTransactionRequest(@NotNull Long truckId, @NotNull Long grainTypeId, @NotNull Long branchId) {
}
