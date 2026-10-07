package com.thinklab.application.dto.request;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** DTO for {@code initiate}: how many days a patch of each severity may stay unapplied. A more severe patch cannot be given more time (the domain says why with a 400). */
@Serdeable
public record InitiatePatchPolicyRequest(
        @NotBlank(message = "Name is required") @Size(max = 80, message = "Name must not exceed 80 characters") String name,
        @NotNull(message = "The asset the policy covers is required") UUID assetId,
        @NotNull(message = "Days for a critical patch are required") @Min(value = 1, message = "Days must be at least 1") @Max(value = 730, message = "Days must not exceed 730") Integer criticalDays,
        @NotNull(message = "Days for a high patch are required") @Min(value = 1, message = "Days must be at least 1") @Max(value = 730, message = "Days must not exceed 730") Integer highDays,
        @NotNull(message = "Days for a medium patch are required") @Min(value = 1, message = "Days must be at least 1") @Max(value = 730, message = "Days must not exceed 730") Integer mediumDays,
        @NotNull(message = "Days for a low patch are required") @Min(value = 1, message = "Days must be at least 1") @Max(value = 730, message = "Days must not exceed 730") Integer lowDays
) {}
