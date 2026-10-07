package com.thinklab.application.dto.response;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;
import java.util.UUID;

/** A finding and its deadline, judged when read: {@code overdue} is true when it is open past {@code dueAt}. {@code dueAt} is left out when its policy no longer exists. */
@Serdeable
public record PatchFindingResponse(UUID id, UUID organisationId, UUID policyId, String patchRef, String severity, Instant announcedAt, String status,
                                   @Nullable Instant dueAt, boolean overdue, @Nullable Instant appliedAt, @Nullable String waiverReason, @Nullable Instant waivedUntil,
                                   String reportedBy, Instant reportedAt, Instant updatedAt) {}
