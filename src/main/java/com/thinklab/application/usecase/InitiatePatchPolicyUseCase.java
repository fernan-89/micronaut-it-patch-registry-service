package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.InitiatePatchPolicyRequest;
import com.thinklab.application.dto.response.PatchPolicyResponse;
import com.thinklab.application.mapper.PatchMapper;
import com.thinklab.domain.model.PatchPolicy;
import com.thinklab.domain.port.HashServicePort;
import com.thinklab.domain.repository.PatchPolicyRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Use Case for promising something about an asset (BIAN Behavior Qualifier: {@code initiate}). Staff only. */
@Singleton
public class InitiatePatchPolicyUseCase {

    private static final Logger log = LoggerFactory.getLogger(InitiatePatchPolicyUseCase.class);

    private final HashServicePort hashServicePort;
    private final PatchPolicyRepository repository;
    private final Clock clock;

    public InitiatePatchPolicyUseCase(HashServicePort hashServicePort, PatchPolicyRepository repository, Clock clock) {
        this.hashServicePort = hashServicePort;
        this.repository = repository;
        this.clock = clock;
    }

    public Mono<PatchPolicyResponse> execute(UUID organisationId, InitiatePatchPolicyRequest request, String executor, String role) {
        log.info("[USE CASE] Creating the PatchPolicy [{}] for organisation: {}", request.name(), organisationId);

        return Mono.fromRunnable(() -> PatchAccess.requireStaff(role, "create a patch policy"))
                .then(Mono.defer(() -> hashServicePort.generateSovereignId("patch-policy-creation")))
                .map(id -> PatchPolicy.createNew(id, organisationId, request.name(), request.assetId(), request.criticalDays(), request.highDays(), request.mediumDays(),
                        request.lowDays(), executor))
                .flatMap(repository::create)
                .map(policy -> PatchMapper.toResponse(policy, List.of(), Instant.now(clock)));
    }
}
