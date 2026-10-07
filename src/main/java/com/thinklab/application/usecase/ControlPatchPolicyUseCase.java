package com.thinklab.application.usecase;

import com.thinklab.domain.model.PatchPolicy;
import com.thinklab.domain.model.PatchPolicy.PolicyAuditEntry;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Use Case for pausing and resuming a policy (BIAN Behavior Qualifier: {@code control/*}). */
@Singleton
public class ControlPatchPolicyUseCase {

    private static final Logger log = LoggerFactory.getLogger(ControlPatchPolicyUseCase.class);

    /** What a person can ask for; each constant says how the aggregate performs it. */
    public enum Action {
        PAUSE {
            @Override PolicyAuditEntry apply(PatchPolicy policy, String executor) { return policy.pause(executor); }
        },
        RESUME {
            @Override PolicyAuditEntry apply(PatchPolicy policy, String executor) { return policy.resume(executor); }
        };

        abstract PolicyAuditEntry apply(PatchPolicy policy, String executor);
    }

    private final PatchPolicyWorkflow workflow;

    public ControlPatchPolicyUseCase(PatchPolicyWorkflow workflow) {
        this.workflow = workflow;
    }

    public Mono<Void> execute(UUID id, UUID organisationId, Action action, String executor, String role) {
        log.info("[USE CASE] {} on PatchPolicy ID: {}", action, id);

        return workflow.apply(id, organisationId, role, "pause or resume a patch policy", policy -> action.apply(policy, executor));
    }
}
