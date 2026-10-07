package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.PatchPolicyResponse;
import com.thinklab.application.mapper.PatchMapper;
import com.thinklab.domain.exception.PatchPolicyNotFoundException;
import com.thinklab.domain.repository.PatchFindingRepository;
import com.thinklab.domain.repository.PatchPolicyRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** Use Case for reading one policy of the tenant, judged now over its unresolved findings (BIAN Behavior Qualifier: {@code retrieve}). Staff only; another tenant is a 404. */
@Singleton
public class RetrievePatchPolicyUseCase {

    private static final Logger log = LoggerFactory.getLogger(RetrievePatchPolicyUseCase.class);

    private final PatchPolicyRepository policies;
    private final PatchFindingRepository findings;
    private final Clock clock;

    public RetrievePatchPolicyUseCase(PatchPolicyRepository policies, PatchFindingRepository findings, Clock clock) {
        this.policies = policies;
        this.findings = findings;
        this.clock = clock;
    }

    public Mono<PatchPolicyResponse> execute(UUID id, UUID organisationId, String role) {
        log.info("[USE CASE] Retrieving PatchPolicy ID: {}", id);

        return Mono.fromRunnable(() -> PatchAccess.requireStaff(role, "read a patch policy"))
                .then(Mono.defer(() -> policies.findById(id, organisationId)))
                .switchIfEmpty(Mono.error(new PatchPolicyNotFoundException("PatchPolicy " + id + " not found.")))
                .flatMap(policy -> findings.findUnresolved(organisationId, id).collectList()
                        .map(unresolved -> PatchMapper.toResponse(policy, unresolved, Instant.now(clock))));
    }
}
