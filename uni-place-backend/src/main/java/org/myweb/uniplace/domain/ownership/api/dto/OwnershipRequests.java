package org.myweb.uniplace.domain.ownership.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public final class OwnershipRequests {
    private OwnershipRequests() {}

    public record OwnerApplication(@NotBlank @Size(max = 2000) String applicationNote) {}

    public record AssetClaim(@NotBlank String resourceType, @NotNull @Positive Integer resourceId,
                             @NotBlank @Size(max = 1000) String proofReference) {}

    public record Review(@NotBlank @Size(max = 2000) String reviewNote) {}
}
