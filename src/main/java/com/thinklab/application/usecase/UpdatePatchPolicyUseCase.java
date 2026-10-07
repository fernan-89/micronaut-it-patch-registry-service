package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.UpdatePatchPolicyRequest;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Use Case for changing a policy (BIAN Behavior Qualifier: {@code update}). */
@Singleton
public class UpdatePatchPolicyUseCase {

    private static final Logger log = LoggerFactory.getLogger(UpdatePatchPolicyUseCase.class);

    private final PatchPolicyWorkflow workflow;

    public UpdatePatchPolicyUseCase(PatchPolicyWorkflow workflow) {
        this.workflow = workflow;
    }

    public Mono<Void> execute(UUID id, UUID organisationId, UpdatePatchPolicyRequest request, String executor, String role) {
        log.info("[USE CASE] Updating PatchPolicy ID: {}", id);

        return workflow.apply(id, organisationId, role, "change a patch policy",
                policy -> policy.update(request.name(), request.assetId(), request.criticalDays(), request.highDays(), request.mediumDays(), request.lowDays(), executor));
    }
}
