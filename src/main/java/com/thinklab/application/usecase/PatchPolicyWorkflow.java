package com.thinklab.application.usecase;

import com.thinklab.domain.exception.PatchPolicyNotFoundException;
import com.thinklab.domain.model.PatchPolicy;
import com.thinklab.domain.model.PatchPolicy.PolicyAuditEntry;
import com.thinklab.domain.repository.PatchPolicyRepository;
import jakarta.inject.Singleton;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.function.Function;

/** The shape every staff change to a policy shares: staff only, load (a 404 if it is not the tenant's), apply, save with the status loaded. */
@Singleton
public class PatchPolicyWorkflow {

    private final PatchPolicyRepository repository;

    public PatchPolicyWorkflow(PatchPolicyRepository repository) {
        this.repository = repository;
    }

    public Mono<Void> apply(UUID id, UUID organisationId, String role, String operation, Function<PatchPolicy, PolicyAuditEntry> action) {
        return Mono.fromRunnable(() -> PatchAccess.requireStaff(role, operation))
                .then(Mono.defer(() -> repository.findById(id, organisationId)))
                .switchIfEmpty(Mono.error(new PatchPolicyNotFoundException("PatchPolicy " + id + " not found.")))
                .flatMap(policy -> {
                    var statusBefore = policy.getStatus();
                    PolicyAuditEntry entry = action.apply(policy);
                    return repository.save(policy, statusBefore, entry);
                });
    }
}
