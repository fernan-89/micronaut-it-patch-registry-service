package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.PromiseResponse;
import com.thinklab.domain.model.PatchFinding;
import com.thinklab.domain.model.PatchPolicy;
import com.thinklab.domain.model.Promise;
import com.thinklab.domain.repository.PatchFindingRepository;
import com.thinklab.domain.repository.PatchPolicyRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Use Case for the promises of the tenant's policies, judged now (BIAN Behavior Qualifier: {@code promises/retrieve}, platform ADR-001): a policy with an open
 * finding past its deadline is broken ({@code PATCH_OVERDUE}), one with none is kept, a paused one is not judged. A policy changed when it was updated or when
 * one of its findings changed (reported, applied, waived): that is read for the window asked, so {@code changedAt} is exact inside the window. Staff only.
 */
@Singleton
public class RetrievePromisesUseCase {

    private static final Logger log = LoggerFactory.getLogger(RetrievePromisesUseCase.class);

    private final PatchPolicyRepository policies;
    private final PatchFindingRepository findings;
    private final Clock clock;

    public RetrievePromisesUseCase(PatchPolicyRepository policies, PatchFindingRepository findings, Clock clock) {
        this.policies = policies;
        this.findings = findings;
        this.clock = clock;
    }

    public Mono<List<PromiseResponse>> execute(UUID organisationId, Integer changedWithinDays, Integer limit, String role) {
        log.info("[USE CASE] Retrieving the promises of the PatchPolicies of organisation: {}", organisationId);
        return Mono.fromRunnable(() -> PatchAccess.requireStaff(role, "list the promises of patch policies"))
                .then(Mono.fromCallable(() -> new int[]{PromiseSelection.days(changedWithinDays), PromiseSelection.limit(limit)}))
                .flatMap(window -> {
                    Instant now = Instant.now(clock);
                    Instant since = now.minus(Duration.ofDays(window[0]));
                    return Mono.zip(findings.findUnresolved(organisationId, null).collectList(), findings.findChangedSince(organisationId, since).collectList(),
                                    policies.findAll(organisationId).collectList())
                            .map(read -> {
                                Map<UUID, List<PatchFinding>> unresolved = read.getT1().stream().collect(Collectors.groupingBy(PatchFinding::getPolicyId));
                                Map<UUID, Instant> lastChange = read.getT2().stream().collect(Collectors.toMap(PatchFinding::getPolicyId, PatchFinding::getUpdatedAt, (a, b) -> a.isAfter(b) ? a : b));
                                List<PromiseResponse> all = new ArrayList<>();
                                for (PatchPolicy policy : read.getT3()) {
                                    all.add(toResponse(policy, unresolved.getOrDefault(policy.getId(), List.of()), lastChange.get(policy.getId()), now));
                                }
                                return PromiseSelection.select(all, since, window[1]);
                            });
                });
    }

    private static PromiseResponse toResponse(PatchPolicy policy, List<PatchFinding> unresolved, Instant lastFindingChange, Instant now) {
        Promise promise = policy.promise(now, unresolved);
        Instant changedAt = lastFindingChange != null && lastFindingChange.isAfter(policy.getUpdatedAt()) ? lastFindingChange : policy.getUpdatedAt();
        return new PromiseResponse(policy.getId(), policy.getName(), policy.getAssetId(), promise.verdict().name(), promise.code(), policy.getStatus().name(), changedAt);
    }
}
