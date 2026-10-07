package com.thinklab.application.dto.request;

import com.thinklab.domain.model.Severity;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO for {@code finding/initiate}: a patch that is available for the asset a policy covers, as the scanner reports it. The reference is an
 * identifier (KB number, CVE, package version), never a sentence. Reporting the same policy and reference again answers the finding already stored.
 */
@Serdeable
public record ReportPatchFindingRequest(
        @NotNull(message = "The policy is required") UUID policyId,
        @NotBlank(message = "The patch reference is required") @Size(max = 64, message = "The patch reference must not exceed 64 characters") String patchRef,
        @NotNull(message = "The severity is required") Severity severity,
        @NotNull(message = "When the patch was announced is required") Instant announcedAt
) {}
