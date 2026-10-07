package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.PatchPolicyResponse;
import com.thinklab.application.mapper.PatchMapper;
import com.thinklab.domain.model.PatchFinding;
import com.thinklab.domain.model.PatchPolicy.Compliance;
import com.thinklab.domain.model.PatchPolicy.PolicyStatus;
import com.thinklab.domain.repository.PatchFindingRepository;
import com.thinklab.domain.repository.PatchPolicyRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Use Case for listing the tenant's policies, oldest first, judged now, filterable by status, asset and compliance (BIAN Behavior Qualifier:
 * {@code retrieve}, collection). The compliance is judged when read, so it is filtered in memory over the tenant's policies (ADR-031).
 */
@Singleton
public class RetrievePatchPoliciesUseCase {

    private static final Logger log = LoggerFactory.getLogger(RetrievePatchPoliciesUseCase.class);

    private final PatchPolicyRepository policies;
    private final PatchFindingRepository findings;
    private final Clock clock;

    public RetrievePatchPoliciesUseCase(PatchPolicyRepository policies, PatchFindingRepository findings, Clock clock) {
        this.policies = policies;
        this.findings = findings;
        this.clock = clock;
    }

    public Flux<PatchPolicyResponse> execute(UUID organisationId, PolicyStatus status, UUID assetId, Compliance compliance, String role) {
        log.info("[USE CASE] Retrieving the PatchPolicies of organisation: {}", organisationId);

        return Mono.fromRunnable(() -> PatchAccess.requireStaff(role, "list patch policies"))
                .then(Mono.defer(() -> findings.findUnresolved(organisationId, null).collectList()))
                .flatMapMany(unresolved -> {
                    Instant now = Instant.now(clock);
                    Map<UUID, List<PatchFinding>> byPolicy = unresolved.stream().collect(Collectors.groupingBy(PatchFinding::getPolicyId));
                    return policies.findAll(organisationId)
                            .filter(policy -> status == null || policy.getStatus() == status)
                            .filter(policy -> assetId == null || assetId.equals(policy.getAssetId()))
                            .map(policy -> PatchMapper.toResponse(policy, byPolicy.getOrDefault(policy.getId(), new ArrayList<>()), now))
                            .filter(response -> compliance == null || compliance.name().equals(response.compliance()));
                });
    }
}
