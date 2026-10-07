package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.PatchFindingResponse;
import com.thinklab.application.mapper.PatchMapper;
import com.thinklab.domain.model.PatchPolicy;
import com.thinklab.domain.repository.PatchFindingRepository;
import com.thinklab.domain.repository.PatchFindingRepository.Filter;
import com.thinklab.domain.repository.PatchPolicyRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Use Case for listing the tenant's findings, newest announcement first, filterable by policy, status and severity, at most {@value #MAX_LIMIT} (BIAN Behavior Qualifier: {@code finding/retrieve}, collection). */
@Singleton
public class RetrievePatchFindingsUseCase {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;

    private static final Logger log = LoggerFactory.getLogger(RetrievePatchFindingsUseCase.class);

    private final PatchFindingRepository findings;
    private final PatchPolicyRepository policies;
    private final Clock clock;

    public RetrievePatchFindingsUseCase(PatchFindingRepository findings, PatchPolicyRepository policies, Clock clock) {
        this.findings = findings;
        this.policies = policies;
        this.clock = clock;
    }

    public Flux<PatchFindingResponse> execute(UUID organisationId, Filter filter, Integer limit, String role) {
        log.info("[USE CASE] Retrieving the PatchFindings of organisation: {}", organisationId);

        return Mono.fromRunnable(() -> PatchAccess.requireStaff(role, "list patch findings"))
                .then(Mono.fromCallable(() -> effectiveLimit(limit)))
                .flatMapMany(effective -> policies.findAll(organisationId).collectMap(PatchPolicy::getId, Function.identity())
                        .flatMapMany(byId -> {
                            Instant now = Instant.now(clock);
                            return findings.findAll(organisationId, filter, effective).map(finding -> PatchMapper.toResponse(finding, byId.get(finding.getPolicyId()), now));
                        }));
    }

    private static int effectiveLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("The limit must be between 1 and " + MAX_LIMIT + ".");
        }
        return limit;
    }
}
