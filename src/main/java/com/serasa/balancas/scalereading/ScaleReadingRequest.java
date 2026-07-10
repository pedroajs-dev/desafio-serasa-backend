package com.serasa.balancas.scalereading;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ScaleReadingRequest(@NotBlank String id, @NotBlank String plate, @NotNull Double weight) {
}
