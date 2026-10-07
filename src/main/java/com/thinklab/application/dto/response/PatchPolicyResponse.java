package com.thinklab.application.dto.response;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;
import java.util.UUID;

/** A policy and how its promise holds right now: {@code compliance} and the counts are judged when read, never stored. */
@Serdeable
public record PatchPolicyResponse(UUID id, UUID organisationId, String name, UUID assetId, int criticalDays, int highDays, int mediumDays, int lowDays, String status,
                                  String compliance, int openFindings, int overdueFindings, int waivedFindings, @Nullable Instant oldestOverdueDueAt,
                                  Instant createdAt, Instant updatedAt) {}
