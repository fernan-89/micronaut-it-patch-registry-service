package com.thinklab.domain.repository;

import com.thinklab.domain.model.PatchFinding;
import com.thinklab.domain.model.PatchFinding.FindingAuditEntry;
import com.thinklab.domain.model.PatchFinding.FindingStatus;
import com.thinklab.domain.model.Severity;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Outbound Port for PatchFinding persistence. {@link #save} is a guarded write on the status loaded (ADR-033); every lookup is tenant-scoped. */
public interface PatchFindingRepository {

    /**
     * Inserts the finding. The same policy and patch reference is the same finding (a unique index): the loser of a repeated or simultaneous
     * report gets the stored one back and {@code created} false, so a tool may retry a report as often as it likes.
     */
    Mono<Stored> create(PatchFinding finding);

    /** The finding already reported for that policy and patch reference, if any. */
    Mono<PatchFinding> findByNaturalKey(UUID organisationId, UUID policyId, String patchRef);

    Mono<PatchFinding> findById(UUID id, UUID organisationId);

    /** The tenant's findings, newest announcement first, at most {@code limit}. */
    Flux<PatchFinding> findAll(UUID organisationId, Filter filter, int limit);

    /** Every finding of the tenant that is not APPLIED (what a policy is judged on), for the whole tenant or for one policy. */
    Flux<PatchFinding> findUnresolved(UUID organisationId, UUID policyId);

    Mono<Void> save(PatchFinding finding, FindingStatus expectedStatus, FindingAuditEntry auditEntry);

    record Stored(PatchFinding finding, boolean created) {
    }

    record Filter(UUID policyId, FindingStatus status, Severity severity) {
    }
}
