package com.serasa.balancas.scale;

public record ScaleResponse(String id, Long branchId, String apiKey) {

    public static ScaleResponse from(Scale scale) {
        return new ScaleResponse(scale.getId(), scale.getBranch().getId(), scale.getApiKey());
    }
}
