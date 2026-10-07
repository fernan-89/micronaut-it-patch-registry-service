package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.ReportPatchFindingRequest;
import com.thinklab.application.dto.response.PatchFindingResponse;
import com.thinklab.application.mapper.PatchMapper;
import com.thinklab.domain.exception.PatchPolicyNotFoundException;
import com.thinklab.domain.model.PatchFinding;
import com.thinklab.domain.model.PatchPolicy;
import com.thinklab.domain.port.HashServicePort;
import com.thinklab.domain.repository.PatchFindingRepository;
import com.thinklab.domain.repository.PatchFindingRepository.Stored;
import com.thinklab.domain.repository.PatchPolicyRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Use Case for what the scanner reports when a patch becomes available (BIAN Behavior Qualifier: {@code finding/initiate}), ADR-030. Idempotent: the
 * same policy and patch reference is the same finding, so a tool may retry; the stored finding comes back with {@code created} false and keeps
 * whatever happened to it since (a finding that was applied is not reopened by being reported again). A sovereign id is spent only for a new one.
 */
@Singleton
public class ReportPatchFindingUseCase {

    private static final Logger log = LoggerFactory.getLogger(ReportPatchFindingUseCase.class);

    /** The finding as stored, and whether this report created it (201) or found it already there (200). */
    public record Reported(PatchFindingResponse finding, boolean created) {
    }

    private final HashServicePort hashServicePort;
    private final PatchPolicyRepository policies;
    private final PatchFindingRepository findings;
    private final Clock clock;

    public ReportPatchFindingUseCase(HashServicePort hashServicePort, PatchPolicyRepository policies, PatchFindingRepository findings, Clock clock) {
        this.hashServicePort = hashServicePort;
        this.policies = policies;
        this.findings = findings;
        this.clock = clock;
    }

    public Mono<Reported> execute(UUID organisationId, ReportPatchFindingRequest request, String executor, String role) {
        log.info("[USE CASE] Reporting a {} patch for PatchPolicy ID: {}", request.severity(), request.policyId());

        return Mono.fromRunnable(() -> PatchAccess.requireStaff(role, "report a patch finding"))
                .then(Mono.defer(() -> policies.findById(request.policyId(), organisationId)))
                .switchIfEmpty(Mono.error(new PatchPolicyNotFoundException("PatchPolicy " + request.policyId() + " not found.")))
                .flatMap(policy -> findings.findByNaturalKey(organisationId, policy.getId(), request.patchRef())
                        .map(existing -> new Stored(existing, false))
                        .switchIfEmpty(Mono.defer(() -> store(organisationId, request, executor)))
                        .map(stored -> new Reported(PatchMapper.toResponse(stored.finding(), policy, Instant.now(clock)), stored.created())));
    }

    private Mono<Stored> store(UUID organisationId, ReportPatchFindingRequest request, String executor) {
        return hashServicePort.generateSovereignId("patch-finding-creation")
                .map(id -> PatchFinding.createNew(id, organisationId, request.policyId(), request.patchRef(), request.severity(), request.announcedAt(), executor,
                        Instant.now(clock)))
                .flatMap(findings::create);
    }
}
