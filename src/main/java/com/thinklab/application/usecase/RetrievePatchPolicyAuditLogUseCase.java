package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.AuditEntryResponse;
import com.thinklab.application.mapper.PatchMapper;
import com.thinklab.domain.exception.PatchPolicyNotFoundException;
import com.thinklab.domain.repository.PatchPolicyRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** Use Case for the forensic ledger of a policy (BIAN Behavior Qualifier: {@code audit-log/retrieve}). Staff only. */
@Singleton
public class RetrievePatchPolicyAuditLogUseCase {

    private static final Logger log = LoggerFactory.getLogger(RetrievePatchPolicyAuditLogUseCase.class);

    private final PatchPolicyRepository repository;

    public RetrievePatchPolicyAuditLogUseCase(PatchPolicyRepository repository) {
        this.repository = repository;
    }

    public Mono<List<AuditEntryResponse>> execute(UUID id, UUID organisationId, String role) {
        log.info("[USE CASE] Retrieving the audit log of PatchPolicy ID: {}", id);

        return Mono.fromRunnable(() -> PatchAccess.requireStaff(role, "read the audit trail of a patch policy"))
                .then(Mono.defer(() -> repository.findById(id, organisationId)))
                .switchIfEmpty(Mono.error(new PatchPolicyNotFoundException("PatchPolicy " + id + " not found.")))
                .map(policy -> policy.getAuditTrail().stream().map(PatchMapper::toResponse).collect(Collectors.toList()));
    }
}
