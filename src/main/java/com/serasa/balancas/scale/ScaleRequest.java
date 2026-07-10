package com.serasa.balancas.scale;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ScaleRequest(@NotBlank String id, @NotNull Long branchId, @NotBlank String apiKey) {
}
