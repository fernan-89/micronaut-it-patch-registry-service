package com.thinklab.domain.repository;

import com.thinklab.domain.model.PatchPolicy;
import com.thinklab.domain.model.PatchPolicy.PolicyAuditEntry;
import com.thinklab.domain.model.PatchPolicy.PolicyStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Outbound Port for PatchPolicy persistence. {@link #save} is a guarded write on the status loaded (ADR-033); every lookup is tenant-scoped. */
public interface PatchPolicyRepository {

    /** Inserts the policy. A name already used in the organisation is {@link com.thinklab.domain.exception.DuplicatePatchPolicyException}: the unique index is the atomic backstop. */
    Mono<PatchPolicy> create(PatchPolicy policy);

    Mono<PatchPolicy> findById(UUID id, UUID organisationId);

    /** The tenant's policies, oldest first. */
    Flux<PatchPolicy> findAll(UUID organisationId);

    Mono<Void> save(PatchPolicy policy, PolicyStatus expectedStatus, PolicyAuditEntry auditEntry);
}
