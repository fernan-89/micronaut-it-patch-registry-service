package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.AuditEntryResponse;
import com.thinklab.application.mapper.PatchMapper;
import com.thinklab.domain.exception.PatchFindingNotFoundException;
import com.thinklab.domain.repository.PatchFindingRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** Use Case for the forensic ledger of a finding (BIAN Behavior Qualifier: {@code finding/audit-log/retrieve}). Staff only. */
@Singleton
public class RetrievePatchFindingAuditLogUseCase {

    private static final Logger log = LoggerFactory.getLogger(RetrievePatchFindingAuditLogUseCase.class);

    private final PatchFindingRepository repository;

    public RetrievePatchFindingAuditLogUseCase(PatchFindingRepository repository) {
        this.repository = repository;
    }

    public Mono<List<AuditEntryResponse>> execute(UUID id, UUID organisationId, String role) {
        log.info("[USE CASE] Retrieving the audit log of PatchFinding ID: {}", id);

        return Mono.fromRunnable(() -> PatchAccess.requireStaff(role, "read the audit trail of a patch finding"))
                .then(Mono.defer(() -> repository.findById(id, organisationId)))
                .switchIfEmpty(Mono.error(new PatchFindingNotFoundException("PatchFinding " + id + " not found.")))
                .map(finding -> finding.getAuditTrail().stream().map(PatchMapper::toResponse).collect(Collectors.toList()));
    }
}
