package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.ApplyPatchFindingRequest;
import com.thinklab.application.dto.request.WaivePatchFindingRequest;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** Use Case for what happens to a finding after it was reported (BIAN Behavior Qualifier: {@code finding/control/*}): applied by the tool, waived or un-waived by staff. */
@Singleton
public class ControlPatchFindingUseCase {

    private static final Logger log = LoggerFactory.getLogger(ControlPatchFindingUseCase.class);

    private final PatchFindingWorkflow workflow;
    private final Clock clock;

    public ControlPatchFindingUseCase(PatchFindingWorkflow workflow, Clock clock) {
        this.workflow = workflow;
        this.clock = clock;
    }

    public Mono<Void> apply(UUID id, UUID organisationId, ApplyPatchFindingRequest request, String executor, String role) {
        log.info("[USE CASE] APPLY on PatchFinding ID: {}", id);

        return Mono.defer(() -> {
            Instant now = Instant.now(clock);
            Instant when = request.appliedAt() != null ? request.appliedAt() : now;
            return workflow.apply(id, organisationId, role, "report a patch as applied", finding -> finding.apply(when, now, executor));
        });
    }

    public Mono<Void> waive(UUID id, UUID organisationId, WaivePatchFindingRequest request, String executor, String role) {
        log.info("[USE CASE] WAIVE on PatchFinding ID: {}", id);

        return Mono.defer(() -> workflow.apply(id, organisationId, role, "waive a patch",
                finding -> finding.waive(request.reason(), request.until(), Instant.now(clock), executor)));
    }

    public Mono<Void> unwaive(UUID id, UUID organisationId, String executor, String role) {
        log.info("[USE CASE] UNWAIVE on PatchFinding ID: {}", id);

        return Mono.defer(() -> workflow.apply(id, organisationId, role, "withdraw the waiver of a patch", finding -> finding.unwaive(Instant.now(clock), executor)));
    }
}
