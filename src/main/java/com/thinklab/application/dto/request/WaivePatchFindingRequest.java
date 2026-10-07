package com.thinklab.application.dto.request;

import com.thinklab.domain.model.PatchFinding.WaiverReason;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/** DTO for {@code finding/control/waive}: a reason from a fixed list and the moment the waiver ends (in the future, at most a year ahead). */
@Serdeable
public record WaivePatchFindingRequest(
        @NotNull(message = "The reason is required") WaiverReason reason,
        @NotNull(message = "When the waiver ends is required") Instant until
) {}
