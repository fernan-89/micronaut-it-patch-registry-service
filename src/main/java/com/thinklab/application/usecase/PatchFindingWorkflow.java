package com.thinklab.application.usecase;

import com.thinklab.domain.exception.PatchFindingNotFoundException;
import com.thinklab.domain.model.PatchFinding;
import com.thinklab.domain.model.PatchFinding.FindingAuditEntry;
import com.thinklab.domain.repository.PatchFindingRepository;
import jakarta.inject.Singleton;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.function.Function;

/** The shape every change to a finding shares: staff only, load (a 404 if it is not the tenant's), apply, save with the status loaded. */
@Singleton
public class PatchFindingWorkflow {

    private final PatchFindingRepository repository;

    public PatchFindingWorkflow(PatchFindingRepository repository) {
        this.repository = repository;
    }

    public Mono<Void> apply(UUID id, UUID organisationId, String role, String operation, Function<PatchFinding, FindingAuditEntry> action) {
        return Mono.fromRunnable(() -> PatchAccess.requireStaff(role, operation))
                .then(Mono.defer(() -> repository.findById(id, organisationId)))
                .switchIfEmpty(Mono.error(new PatchFindingNotFoundException("PatchFinding " + id + " not found.")))
                .flatMap(finding -> {
                    var statusBefore = finding.getStatus();
                    FindingAuditEntry entry = action.apply(finding);
                    return repository.save(finding, statusBefore, entry);
                });
    }
}
