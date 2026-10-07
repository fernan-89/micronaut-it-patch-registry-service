package com.thinklab.application.dto.request;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;

/** DTO for {@code finding/control/apply}: when the tool applied the patch; left out means now. */
@Serdeable
public record ApplyPatchFindingRequest(@Nullable Instant appliedAt) {}
