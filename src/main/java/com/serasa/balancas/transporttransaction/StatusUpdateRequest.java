package com.serasa.balancas.transporttransaction;

import jakarta.validation.constraints.NotNull;

public record StatusUpdateRequest(@NotNull TransactionStatus status) {
}
