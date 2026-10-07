package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.ApplyPatchFindingRequest;
import com.thinklab.application.dto.request.InitiatePatchPolicyRequest;
import com.thinklab.application.dto.request.ReportPatchFindingRequest;
import com.thinklab.application.dto.request.UpdatePatchPolicyRequest;
import com.thinklab.application.dto.request.WaivePatchFindingRequest;
import com.thinklab.domain.exception.InvalidPatchFindingStatusException;
import com.thinklab.domain.exception.InvalidPatchPolicyStatusException;
import com.thinklab.domain.exception.PatchAccessDeniedException;
import com.thinklab.domain.exception.PatchFindingNotFoundException;
import com.thinklab.domain.exception.PatchPolicyNotFoundException;
import com.thinklab.domain.model.PatchFinding;
import com.thinklab.domain.model.PatchFinding.FindingStatus;
import com.thinklab.domain.model.PatchFinding.WaiverReason;
import com.thinklab.domain.model.PatchPolicy;
import com.thinklab.domain.model.PatchPolicy.Compliance;
import com.thinklab.domain.model.PatchPolicy.PolicyStatus;
import com.thinklab.domain.model.Severity;
import com.thinklab.domain.port.HashServicePort;
import com.thinklab.domain.repository.PatchFindingRepository;
import com.thinklab.domain.repository.PatchFindingRepository.Filter;
import com.thinklab.domain.repository.PatchFindingRepository.Stored;
import com.thinklab.domain.repository.PatchPolicyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PatchUseCasesTest {

    private static final String STAFF = "ENGINEER";
    private static final String REQUESTER = "REQUESTER";
    private static final UUID ELSEWHERE = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final UUID tenant = UUID.randomUUID();
    private final UUID asset = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-07T12:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

    private HashServicePort hash;
    private PatchPolicyRepository policies;
    private PatchFindingRepository findings;

    @BeforeEach
    void setUp() {
        hash = mock(HashServicePort.class);
        policies = mock(PatchPolicyRepository.class);
        findings = mock(PatchFindingRepository.class);
    }

    private PatchPolicy policy() {
        return PatchPolicy.createNew(UUID.randomUUID(), tenant, "Servers", asset, 2, 7, 30, 90, "op");
    }

    private PatchFinding finding(PatchPolicy policy, Severity severity, int announcedDaysAgo) {
        return PatchFinding.createNew(UUID.randomUUID(), tenant, policy.getId(), "KB" + announcedDaysAgo + severity, severity, now.minus(Duration.ofDays(announcedDaysAgo)), "tool", now);
    }

    private InitiatePatchPolicyRequest initiateRequest() {
        return new InitiatePatchPolicyRequest("Servers", asset, 2, 7, 30, 90);
    }

    private UpdatePatchPolicyRequest updateRequest() {
        return new UpdatePatchPolicyRequest("Renamed", asset, 1, 3, 10, 60);
    }

    @Test
    @DisplayName("the access rule refuses a requester and lets staff and a missing role through; it is a utility class")
    void access() throws ReflectiveOperationException {
        PatchAccess.requireStaff(STAFF, "x");
        PatchAccess.requireStaff(null, "x");
        PatchAccessDeniedException denied = assertThrows(PatchAccessDeniedException.class, () -> PatchAccess.requireStaff(REQUESTER, "do it"));
        assertTrue(denied.getMessage().contains("do it"));
        Constructor<PatchAccess> constructor = PatchAccess.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        assertThrows(InvocationTargetException.class, constructor::newInstance);
    }

    @Test
    @DisplayName("initiate spends a sovereign id, stores the policy and answers it judged now; a requester spends nothing")
    void initiate() {
        UUID id = UUID.randomUUID();
        when(hash.generateSovereignId("patch-policy-creation")).thenReturn(Mono.just(id));
        when(policies.create(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        InitiatePatchPolicyUseCase useCase = new InitiatePatchPolicyUseCase(hash, policies, clock);

        StepVerifier.create(useCase.execute(tenant, initiateRequest(), "op", STAFF)).assertNext(response -> {
            assertEquals(id, response.id());
            assertEquals("ACTIVE", response.status());
            assertEquals("COMPLIANT", response.compliance());
        }).verifyComplete();

        StepVerifier.create(useCase.execute(tenant, initiateRequest(), "op", REQUESTER)).expectError(PatchAccessDeniedException.class).verify();
        verify(hash, times(1)).generateSovereignId("patch-policy-creation");
    }

    @Test
    @DisplayName("retrieve judges the policy over its unresolved findings, a 404 when it is not the tenant's, and refuses a requester")
    void retrieve() {
        PatchPolicy policy = policy();
        PatchFinding late = finding(policy, Severity.CRITICAL, 5);
        when(policies.findById(policy.getId(), tenant)).thenReturn(Mono.just(policy));
        when(policies.findById(any(), eq(ELSEWHERE))).thenReturn(Mono.empty());
        when(findings.findUnresolved(tenant, policy.getId())).thenReturn(Flux.just(late));
        RetrievePatchPolicyUseCase useCase = new RetrievePatchPolicyUseCase(policies, findings, clock);

        StepVerifier.create(useCase.execute(policy.getId(), tenant, STAFF)).assertNext(response -> {
            assertEquals("OVERDUE", response.compliance());
            assertEquals(1, response.overdueFindings());
        }).verifyComplete();
        StepVerifier.create(useCase.execute(policy.getId(), ELSEWHERE, STAFF)).expectError(PatchPolicyNotFoundException.class).verify();
        StepVerifier.create(useCase.execute(policy.getId(), tenant, REQUESTER)).expectError(PatchAccessDeniedException.class).verify();
    }

    @Test
    @DisplayName("the list judges every policy over its own findings and filters by status, asset and compliance; a requester is refused")
    void retrieveAll() {
        PatchPolicy late = policy();
        PatchPolicy fine = PatchPolicy.createNew(UUID.randomUUID(), tenant, "Other", UUID.randomUUID(), 2, 7, 30, 90, "op");
        PatchPolicy paused = PatchPolicy.createNew(UUID.randomUUID(), tenant, "Old", UUID.randomUUID(), 2, 7, 30, 90, "op");
        paused.pause("op");
        when(policies.findAll(tenant)).thenReturn(Flux.just(late, fine, paused));
        when(findings.findUnresolved(tenant, null)).thenReturn(Flux.just(finding(late, Severity.CRITICAL, 5), finding(paused, Severity.CRITICAL, 5)));
        RetrievePatchPoliciesUseCase useCase = new RetrievePatchPoliciesUseCase(policies, findings, clock);

        StepVerifier.create(useCase.execute(tenant, null, null, null, STAFF)).expectNextCount(3).verifyComplete();
        StepVerifier.create(useCase.execute(tenant, PolicyStatus.PAUSED, null, null, STAFF)).assertNext(response -> assertEquals("PAUSED", response.compliance())).verifyComplete();
        StepVerifier.create(useCase.execute(tenant, null, asset, null, STAFF)).assertNext(response -> assertEquals(late.getId(), response.id())).verifyComplete();
        StepVerifier.create(useCase.execute(tenant, null, null, Compliance.COMPLIANT, STAFF)).assertNext(response -> assertEquals(fine.getId(), response.id())).verifyComplete();
        StepVerifier.create(useCase.execute(tenant, null, null, Compliance.OVERDUE, STAFF)).assertNext(response -> assertEquals(late.getId(), response.id())).verifyComplete();
        StepVerifier.create(useCase.execute(tenant, null, null, null, REQUESTER)).expectError(PatchAccessDeniedException.class).verify();
    }

    @Test
    @DisplayName("update and control load the policy, apply the change and save it with the status loaded; an unknown policy is a 404 and a requester is refused")
    void updateAndControl() {
        PatchPolicy policy = policy();
        when(policies.findById(policy.getId(), tenant)).thenReturn(Mono.just(policy));
        when(policies.findById(any(), eq(ELSEWHERE))).thenReturn(Mono.empty());
        when(policies.save(any(), any(), any())).thenReturn(Mono.empty());
        PatchPolicyWorkflow workflow = new PatchPolicyWorkflow(policies);
        UpdatePatchPolicyUseCase update = new UpdatePatchPolicyUseCase(workflow);
        ControlPatchPolicyUseCase control = new ControlPatchPolicyUseCase(workflow);

        StepVerifier.create(update.execute(policy.getId(), tenant, updateRequest(), "op", STAFF)).verifyComplete();
        assertEquals("Renamed", policy.getName());
        StepVerifier.create(control.execute(policy.getId(), tenant, ControlPatchPolicyUseCase.Action.PAUSE, "op", STAFF)).verifyComplete();
        assertEquals(PolicyStatus.PAUSED, policy.getStatus());
        StepVerifier.create(control.execute(policy.getId(), tenant, ControlPatchPolicyUseCase.Action.PAUSE, "op", STAFF)).expectError(InvalidPatchPolicyStatusException.class).verify();
        StepVerifier.create(control.execute(policy.getId(), tenant, ControlPatchPolicyUseCase.Action.RESUME, "op", STAFF)).verifyComplete();
        assertEquals(PolicyStatus.ACTIVE, policy.getStatus());

        ArgumentCaptor<PolicyStatus> expected = ArgumentCaptor.forClass(PolicyStatus.class);
        verify(policies, times(3)).save(any(), expected.capture(), any());
        assertEquals(PolicyStatus.ACTIVE, expected.getAllValues().get(0));
        assertEquals(PolicyStatus.ACTIVE, expected.getAllValues().get(1));
        assertEquals(PolicyStatus.PAUSED, expected.getAllValues().get(2));

        StepVerifier.create(update.execute(policy.getId(), ELSEWHERE, updateRequest(), "op", STAFF)).expectError(PatchPolicyNotFoundException.class).verify();
        StepVerifier.create(update.execute(policy.getId(), tenant, updateRequest(), "op", REQUESTER)).expectError(PatchAccessDeniedException.class).verify();
        StepVerifier.create(control.execute(policy.getId(), tenant, ControlPatchPolicyUseCase.Action.PAUSE, "op", REQUESTER)).expectError(PatchAccessDeniedException.class).verify();
    }

    @Test
    @DisplayName("the audit logs are the trail of the policy and of the finding, a 404 when unknown, and staff only")
    void auditLogs() {
        PatchPolicy policy = policy();
        policy.pause("op");
        PatchFinding finding = finding(policy, Severity.LOW, 1);
        when(policies.findById(policy.getId(), tenant)).thenReturn(Mono.just(policy));
        when(policies.findById(any(), eq(ELSEWHERE))).thenReturn(Mono.empty());
        when(findings.findById(finding.getId(), tenant)).thenReturn(Mono.just(finding));
        when(findings.findById(any(), eq(ELSEWHERE))).thenReturn(Mono.empty());
        RetrievePatchPolicyAuditLogUseCase policyLog = new RetrievePatchPolicyAuditLogUseCase(policies);
        RetrievePatchFindingAuditLogUseCase findingLog = new RetrievePatchFindingAuditLogUseCase(findings);

        StepVerifier.create(policyLog.execute(policy.getId(), tenant, STAFF)).assertNext(entries -> {
            assertEquals(2, entries.size());
            assertEquals("INITIATED", entries.get(0).action());
        }).verifyComplete();
        StepVerifier.create(policyLog.execute(policy.getId(), ELSEWHERE, STAFF)).expectError(PatchPolicyNotFoundException.class).verify();
        StepVerifier.create(policyLog.execute(policy.getId(), tenant, REQUESTER)).expectError(PatchAccessDeniedException.class).verify();
        finding.waive(WaiverReason.ACCEPTED_RISK, now.plus(Duration.ofDays(3)), now, "staff");
        StepVerifier.create(findingLog.execute(finding.getId(), tenant, STAFF)).assertNext(entries -> {
            assertEquals("REPORTED", entries.get(0).action());
            assertNull(entries.get(0).fromStatus());
            assertEquals("PENDING", entries.get(1).fromStatus());
        }).verifyComplete();
        StepVerifier.create(findingLog.execute(finding.getId(), ELSEWHERE, STAFF)).expectError(PatchFindingNotFoundException.class).verify();
        StepVerifier.create(findingLog.execute(finding.getId(), tenant, REQUESTER)).expectError(PatchAccessDeniedException.class).verify();
    }

    private ReportPatchFindingRequest report(UUID policyId) {
        return new ReportPatchFindingRequest(policyId, "KB5030211", Severity.HIGH, now.minus(Duration.ofDays(2)));
    }

    @Test
    @DisplayName("a new finding is stored, answered with its deadline, and says it was created")
    void reportNew() {
        PatchPolicy policy = policy();
        UUID findingId = UUID.randomUUID();
        when(policies.findById(policy.getId(), tenant)).thenReturn(Mono.just(policy));
        when(findings.findByNaturalKey(tenant, policy.getId(), "KB5030211")).thenReturn(Mono.empty());
        when(hash.generateSovereignId("patch-finding-creation")).thenReturn(Mono.just(findingId));
        when(findings.create(any())).thenAnswer(invocation -> Mono.just(new Stored(invocation.getArgument(0), true)));
        ReportPatchFindingUseCase useCase = new ReportPatchFindingUseCase(hash, policies, findings, clock);

        StepVerifier.create(useCase.execute(tenant, report(policy.getId()), "tool", STAFF)).assertNext(reported -> {
            assertTrue(reported.created());
            assertEquals(findingId, reported.finding().id());
            assertEquals("PENDING", reported.finding().status());
            assertEquals(now.minus(Duration.ofDays(2)).plus(Duration.ofDays(7)), reported.finding().dueAt());
            assertFalse(reported.finding().overdue());
        }).verifyComplete();
    }

    @Test
    @DisplayName("the same patch reported again is the stored one, no id is spent, and it keeps whatever happened to it")
    void reportAgain() {
        PatchPolicy policy = policy();
        PatchFinding stored = finding(policy, Severity.HIGH, 2);
        stored.apply(now.minus(Duration.ofDays(1)), now, "tool");
        when(policies.findById(policy.getId(), tenant)).thenReturn(Mono.just(policy));
        when(findings.findByNaturalKey(tenant, policy.getId(), "KB5030211")).thenReturn(Mono.just(stored));
        ReportPatchFindingUseCase useCase = new ReportPatchFindingUseCase(hash, policies, findings, clock);

        StepVerifier.create(useCase.execute(tenant, report(policy.getId()), "tool", STAFF)).assertNext(reported -> {
            assertFalse(reported.created());
            assertEquals("APPLIED", reported.finding().status());
        }).verifyComplete();
        verify(hash, never()).generateSovereignId(any());
        verify(findings, never()).create(any());
    }

    @Test
    @DisplayName("a finding for a policy that is not the tenant's is a 404, a requester is refused, and a reference that is not an identifier is a 400")
    void reportRefused() {
        UUID unknown = UUID.randomUUID();
        when(policies.findById(unknown, tenant)).thenReturn(Mono.empty());
        PatchPolicy policy = policy();
        when(policies.findById(policy.getId(), tenant)).thenReturn(Mono.just(policy));
        when(findings.findByNaturalKey(any(), any(), any())).thenReturn(Mono.empty());
        when(hash.generateSovereignId(any())).thenReturn(Mono.just(UUID.randomUUID()));
        ReportPatchFindingUseCase useCase = new ReportPatchFindingUseCase(hash, policies, findings, clock);

        StepVerifier.create(useCase.execute(tenant, report(unknown), "tool", STAFF)).expectError(PatchPolicyNotFoundException.class).verify();
        StepVerifier.create(useCase.execute(tenant, report(unknown), "tool", REQUESTER)).expectError(PatchAccessDeniedException.class).verify();
        ReportPatchFindingRequest sentence = new ReportPatchFindingRequest(policy.getId(), "password is hunter2", Severity.LOW, now);
        StepVerifier.create(useCase.execute(tenant, sentence, "tool", STAFF)).expectError(IllegalArgumentException.class).verify();
        verify(findings, never()).create(any());
    }

    @Test
    @DisplayName("apply, waive and unwaive load the finding, change it and save it with the status loaded; unknown is a 404, an illegal move a conflict, a requester is refused")
    void control() {
        PatchPolicy policy = policy();
        PatchFinding finding = finding(policy, Severity.HIGH, 1);
        when(findings.findById(finding.getId(), tenant)).thenReturn(Mono.just(finding));
        when(findings.findById(any(), eq(ELSEWHERE))).thenReturn(Mono.empty());
        when(findings.save(any(), any(), any())).thenReturn(Mono.empty());
        ControlPatchFindingUseCase useCase = new ControlPatchFindingUseCase(new PatchFindingWorkflow(findings), clock);

        StepVerifier.create(useCase.waive(finding.getId(), tenant, new WaivePatchFindingRequest(WaiverReason.ACCEPTED_RISK, now.plus(Duration.ofDays(10))), "staff", STAFF)).verifyComplete();
        assertEquals(FindingStatus.WAIVED, finding.getStatus());
        StepVerifier.create(useCase.unwaive(finding.getId(), tenant, "staff", STAFF)).verifyComplete();
        assertEquals(FindingStatus.PENDING, finding.getStatus());
        StepVerifier.create(useCase.apply(finding.getId(), tenant, new ApplyPatchFindingRequest(null), "tool", STAFF)).verifyComplete();
        assertEquals(FindingStatus.APPLIED, finding.getStatus());
        assertEquals(now, finding.getAppliedAt());
        StepVerifier.create(useCase.apply(finding.getId(), tenant, new ApplyPatchFindingRequest(null), "tool", STAFF)).expectError(InvalidPatchFindingStatusException.class).verify();

        PatchFinding other = finding(policy, Severity.LOW, 3);
        when(findings.findById(other.getId(), tenant)).thenReturn(Mono.just(other));
        Instant when = now.minus(Duration.ofDays(1));
        StepVerifier.create(useCase.apply(other.getId(), tenant, new ApplyPatchFindingRequest(when), "tool", STAFF)).verifyComplete();
        assertEquals(when, other.getAppliedAt());

        ArgumentCaptor<FindingStatus> expected = ArgumentCaptor.forClass(FindingStatus.class);
        verify(findings, times(4)).save(any(), expected.capture(), any());
        assertEquals(FindingStatus.PENDING, expected.getAllValues().get(0));
        assertEquals(FindingStatus.WAIVED, expected.getAllValues().get(1));

        StepVerifier.create(useCase.waive(UUID.randomUUID(), ELSEWHERE, new WaivePatchFindingRequest(WaiverReason.ACCEPTED_RISK, now), "staff", STAFF)).expectError(PatchFindingNotFoundException.class).verify();
        StepVerifier.create(useCase.apply(finding.getId(), tenant, new ApplyPatchFindingRequest(null), "tool", REQUESTER)).expectError(PatchAccessDeniedException.class).verify();
        StepVerifier.create(useCase.waive(finding.getId(), tenant, new WaivePatchFindingRequest(WaiverReason.ACCEPTED_RISK, now), "staff", REQUESTER)).expectError(PatchAccessDeniedException.class).verify();
        StepVerifier.create(useCase.unwaive(finding.getId(), tenant, "staff", REQUESTER)).expectError(PatchAccessDeniedException.class).verify();
    }

    @Test
    @DisplayName("one finding: answered with its deadline, with none when its policy is gone, a 404 otherwise, staff only")
    void retrieveFinding() {
        PatchPolicy policy = policy();
        PatchFinding late = finding(policy, Severity.CRITICAL, 5);
        PatchFinding orphan = PatchFinding.createNew(UUID.randomUUID(), tenant, UUID.randomUUID(), "KB9", Severity.LOW, now, "tool", now);
        when(findings.findById(late.getId(), tenant)).thenReturn(Mono.just(late));
        when(findings.findById(orphan.getId(), tenant)).thenReturn(Mono.just(orphan));
        when(findings.findById(any(), eq(ELSEWHERE))).thenReturn(Mono.empty());
        when(policies.findById(policy.getId(), tenant)).thenReturn(Mono.just(policy));
        when(policies.findById(orphan.getPolicyId(), tenant)).thenReturn(Mono.empty());
        RetrievePatchFindingUseCase useCase = new RetrievePatchFindingUseCase(findings, policies, clock);

        StepVerifier.create(useCase.execute(late.getId(), tenant, STAFF)).assertNext(response -> {
            assertTrue(response.overdue());
            assertEquals(late.getAnnouncedAt().plus(Duration.ofDays(2)), response.dueAt());
        }).verifyComplete();
        StepVerifier.create(useCase.execute(orphan.getId(), tenant, STAFF)).assertNext(response -> {
            assertNull(response.dueAt());
            assertFalse(response.overdue());
        }).verifyComplete();
        PatchFinding waived = finding(policy, Severity.HIGH, 1);
        waived.waive(WaiverReason.VENDOR_NO_FIX, now.plus(Duration.ofDays(5)), now, "staff");
        when(findings.findById(waived.getId(), tenant)).thenReturn(Mono.just(waived));
        StepVerifier.create(useCase.execute(waived.getId(), tenant, STAFF)).assertNext(response -> {
            assertEquals("VENDOR_NO_FIX", response.waiverReason());
            assertFalse(response.overdue());
        }).verifyComplete();
        StepVerifier.create(useCase.execute(late.getId(), ELSEWHERE, STAFF)).expectError(PatchFindingNotFoundException.class).verify();
        StepVerifier.create(useCase.execute(late.getId(), tenant, REQUESTER)).expectError(PatchAccessDeniedException.class).verify();
    }

    @Test
    @DisplayName("the finding list uses 50 unless asked, accepts 1 to 200, refuses anything else and a requester")
    void retrieveFindings() {
        PatchPolicy policy = policy();
        PatchFinding finding = finding(policy, Severity.HIGH, 1);
        Filter filter = new Filter(null, null, null);
        when(policies.findAll(tenant)).thenReturn(Flux.just(policy));
        when(findings.findAll(eq(tenant), eq(filter), any(Integer.class))).thenReturn(Flux.just(finding));
        RetrievePatchFindingsUseCase useCase = new RetrievePatchFindingsUseCase(findings, policies, clock);

        StepVerifier.create(useCase.execute(tenant, filter, null, STAFF)).assertNext(response -> assertEquals(finding.getId(), response.id())).verifyComplete();
        verify(findings).findAll(tenant, filter, 50);
        StepVerifier.create(useCase.execute(tenant, filter, 1, STAFF)).expectNextCount(1).verifyComplete();
        StepVerifier.create(useCase.execute(tenant, filter, 200, STAFF)).expectNextCount(1).verifyComplete();
        verify(findings).findAll(tenant, filter, 200);
        StepVerifier.create(useCase.execute(tenant, filter, 0, STAFF)).expectError(IllegalArgumentException.class).verify();
        StepVerifier.create(useCase.execute(tenant, filter, 201, STAFF)).expectError(IllegalArgumentException.class).verify();
        StepVerifier.create(useCase.execute(tenant, filter, null, REQUESTER)).expectError(PatchAccessDeniedException.class).verify();
    }
}
