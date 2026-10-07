package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.PatchFindingResponse;
import com.thinklab.application.mapper.PatchMapper;
import com.thinklab.domain.exception.PatchFindingNotFoundException;
import com.thinklab.domain.repository.PatchFindingRepository;
import com.thinklab.domain.repository.PatchPolicyRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** Use Case for reading one finding of the tenant with its deadline (BIAN Behavior Qualifier: {@code finding/retrieve}). Staff only; another tenant is a 404. */
@Singleton
public class RetrievePatchFindingUseCase {

    private static final Logger log = LoggerFactory.getLogger(RetrievePatchFindingUseCase.class);

    private final PatchFindingRepository findings;
    private final PatchPolicyRepository policies;
    private final Clock clock;

    public RetrievePatchFindingUseCase(PatchFindingRepository findings, PatchPolicyRepository policies, Clock clock) {
        this.findings = findings;
        this.policies = policies;
        this.clock = clock;
    }

    public Mono<PatchFindingResponse> execute(UUID id, UUID organisationId, String role) {
        log.info("[USE CASE] Retrieving PatchFinding ID: {}", id);

        return Mono.fromRunnable(() -> PatchAccess.requireStaff(role, "read a patch finding"))
                .then(Mono.defer(() -> findings.findById(id, organisationId)))
                .switchIfEmpty(Mono.error(new PatchFindingNotFoundException("PatchFinding " + id + " not found.")))
                .flatMap(finding -> policies.findById(finding.getPolicyId(), organisationId)
                        .map(policy -> PatchMapper.toResponse(finding, policy, Instant.now(clock)))
                        .switchIfEmpty(Mono.fromSupplier(() -> PatchMapper.toResponse(finding, null, Instant.now(clock)))));
    }
}
