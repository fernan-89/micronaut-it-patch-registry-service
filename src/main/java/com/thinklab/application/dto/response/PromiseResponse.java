package com.thinklab.application.dto.response;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;
import java.util.UUID;

/**
 * One promise as every registry answers it (platform ADR-001): what it is called, the asset it is about if any, the verdict judged now, the fixed code when it is
 * broken, the registry's own status, and when it last changed.
 */
@Serdeable
public record PromiseResponse(UUID id, String name, @Nullable UUID assetId, String verdict, @Nullable String code, String status, Instant changedAt) {}
