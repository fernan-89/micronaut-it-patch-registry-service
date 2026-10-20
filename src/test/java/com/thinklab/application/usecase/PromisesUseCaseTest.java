package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.PromiseResponse;
import com.thinklab.domain.exception.PatchAccessDeniedException;
import com.thinklab.domain.model.PatchFinding;
import com.thinklab.domain.model.PatchPolicy;
import com.thinklab.domain.model.Severity;
import com.thinklab.domain.repository.PatchFindingRepository;
import com.thinklab.domain.repository.PatchPolicyRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PromisesUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");
    private final UUID org = UUID.randomUUID();
    private final UUID asset = UUID.randomUUID();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private PatchPolicy policy(String name, Instant createdAt) {
        return PatchPolicy.reconstitute(UUID.randomUUID(), org, name, asset, 2, 7, 30, 90, PatchPolicy.PolicyStatus.ACTIVE, createdAt, createdAt, List.of());
    }

    private PatchFinding finding(PatchPolicy policy, Severity severity, Instant announced, Instant updatedAt) {
        return PatchFinding.reconstitute(UUID.randomUUID(), org, policy.getId(), "KB1", severity, announced, PatchFinding.FindingStatus.PENDING, null, null, null, "tool", announced, updatedAt, List.of());
    }

    private RetrievePromisesUseCase useCase(List<PatchPolicy> found, List<PatchFinding> unresolved, List<PatchFinding> changed) {
        PatchPolicyRepository policies = mock(PatchPolicyRepository.class);
        PatchFindingRepository findings = mock(PatchFindingRepository.class);
        when(policies.findAll(org)).thenReturn(Flux.fromIterable(found));
        when(findings.findUnresolved(org, null)).thenReturn(Flux.fromIterable(unresolved));
        when(findings.findChangedSince(org, NOW.minus(Duration.ofDays(30)))).thenReturn(Flux.fromIterable(changed));
        return new RetrievePromisesUseCase(policies, findings, clock);
    }

    @Test
    @DisplayName("a policy with an open finding past its deadline is broken with PATCH_OVERDUE; one that changed lately (a finding applied) is listed as kept with the finding's moment as when it changed")
    void selects() {
        PatchPolicy broken = policy("Broken", NOW.minus(Duration.ofDays(90)));
        PatchPolicy recovered = policy("Recovered", NOW.minus(Duration.ofDays(90)));
        PatchPolicy quiet = policy("Quiet", NOW.minus(Duration.ofDays(90)));
        PatchFinding overdue = finding(broken, Severity.CRITICAL, NOW.minus(Duration.ofDays(10)), NOW.minus(Duration.ofDays(10)));
        PatchFinding appliedToday = finding(recovered, Severity.HIGH, NOW.minus(Duration.ofDays(20)), NOW.minus(Duration.ofHours(3)));
        PatchFinding olderChange = finding(recovered, Severity.LOW, NOW.minus(Duration.ofDays(20)), NOW.minus(Duration.ofDays(5)));

        StepVerifier.create(useCase(List.of(quiet, recovered, broken), List.of(overdue), List.of(appliedToday, olderChange)).execute(org, null, null, "ENGINEER")).assertNext(promises -> {
            assertEquals(List.of("Broken", "Recovered"), promises.stream().map(PromiseResponse::name).toList());
            assertEquals("BROKEN", promises.get(0).verdict());
            assertEquals("PATCH_OVERDUE", promises.get(0).code());
            assertEquals(asset, promises.get(0).assetId());
            assertEquals("ACTIVE", promises.get(0).status());
            assertEquals("KEPT", promises.get(1).verdict());
            assertEquals(NOW.minus(Duration.ofHours(3)), promises.get(1).changedAt());
        }).verifyComplete();
    }

    @Test
    @DisplayName("of several findings that changed, the latest dates the policy, whatever the order they are read in")
    void latestFindingWins() {
        PatchPolicy policy = policy("Policy", NOW.minus(Duration.ofDays(90)));
        PatchFinding older = finding(policy, Severity.LOW, NOW.minus(Duration.ofDays(20)), NOW.minus(Duration.ofDays(5)));
        PatchFinding newer = finding(policy, Severity.LOW, NOW.minus(Duration.ofDays(20)), NOW.minus(Duration.ofHours(2)));

        StepVerifier.create(useCase(List.of(policy), List.of(), List.of(older, newer)).execute(org, null, null, "ENGINEER"))
                .assertNext(promises -> assertEquals(NOW.minus(Duration.ofHours(2)), promises.get(0).changedAt())).verifyComplete();
    }

    @Test
    @DisplayName("a policy updated after its findings changed is dated by the update")
    void updateIsLater() {
        PatchPolicy updated = policy("Updated", NOW.minus(Duration.ofHours(1)));
        PatchFinding old = finding(updated, Severity.LOW, NOW.minus(Duration.ofDays(20)), NOW.minus(Duration.ofDays(2)));

        StepVerifier.create(useCase(List.of(updated), List.of(), List.of(old)).execute(org, null, null, "ENGINEER"))
                .assertNext(promises -> assertEquals(NOW.minus(Duration.ofHours(1)), promises.get(0).changedAt())).verifyComplete();
    }

    @Test
    @DisplayName("the days must be 1 to 365 and the limit 1 to 1000; a requester is refused")
    void validation() {
        RetrievePromisesUseCase useCase = useCase(List.of(), List.of(), List.of());

        assertThrows(IllegalArgumentException.class, () -> useCase.execute(org, 0, null, "ENGINEER").block());
        assertThrows(IllegalArgumentException.class, () -> useCase.execute(org, 366, null, "ENGINEER").block());
        assertThrows(IllegalArgumentException.class, () -> useCase.execute(org, null, 0, "ENGINEER").block());
        assertThrows(IllegalArgumentException.class, () -> useCase.execute(org, null, 1001, "ENGINEER").block());
        assertThrows(PatchAccessDeniedException.class, () -> useCase.execute(org, null, null, "REQUESTER").block());
    }

    @Test
    @DisplayName("the window and the limit apply: an earlier change is left out, and each kind is limited")
    void window() {
        PatchPolicy kept = policy("Kept", NOW.minus(Duration.ofDays(90)));
        PatchPolicy keptOld = policy("KeptOld", NOW.minus(Duration.ofHours(30)));
        PatchPolicy brokenOne = policy("B1", NOW.minus(Duration.ofDays(90)));
        PatchPolicy brokenTwo = policy("B2", NOW.minus(Duration.ofDays(90)));
        PatchFinding one = finding(brokenOne, Severity.CRITICAL, NOW.minus(Duration.ofDays(10)), NOW.minus(Duration.ofDays(10)));
        PatchFinding two = finding(brokenTwo, Severity.CRITICAL, NOW.minus(Duration.ofDays(10)), NOW.minus(Duration.ofDays(10)));
        PatchPolicyRepository policies = mock(PatchPolicyRepository.class);
        PatchFindingRepository findings = mock(PatchFindingRepository.class);
        when(policies.findAll(org)).thenReturn(Flux.just(keptOld, kept, brokenOne, brokenTwo));
        when(findings.findUnresolved(org, null)).thenReturn(Flux.just(one, two));
        when(findings.findChangedSince(org, NOW.minus(Duration.ofDays(1)))).thenReturn(Flux.just(finding(kept, Severity.LOW, NOW.minus(Duration.ofDays(5)), NOW.minus(Duration.ofHours(5)))));

        StepVerifier.create(new RetrievePromisesUseCase(policies, findings, clock).execute(org, 1, 1, "ENGINEER"))
                .assertNext(promises -> assertEquals(List.of("B1", "Kept"), promises.stream().map(PromiseResponse::name).toList())).verifyComplete();
    }

    @Test
    @DisplayName("the selection is a utility class")
    void utility() throws ReflectiveOperationException {
        Constructor<PromiseSelection> constructor = PromiseSelection.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        assertThrows(InvocationTargetException.class, constructor::newInstance);
    }
}
