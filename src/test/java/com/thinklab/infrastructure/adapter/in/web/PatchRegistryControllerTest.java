package com.thinklab.infrastructure.adapter.in.web;

import com.thinklab.application.dto.request.ApplyPatchFindingRequest;
import com.thinklab.application.dto.request.InitiatePatchPolicyRequest;
import com.thinklab.application.dto.request.ReportPatchFindingRequest;
import com.thinklab.application.dto.request.UpdatePatchPolicyRequest;
import com.thinklab.application.dto.request.WaivePatchFindingRequest;
import com.thinklab.application.dto.response.AuditEntryResponse;
import com.thinklab.application.dto.response.PatchFindingResponse;
import com.thinklab.application.dto.response.PatchPolicyResponse;
import com.thinklab.application.usecase.ControlPatchFindingUseCase;
import com.thinklab.application.usecase.ControlPatchPolicyUseCase;
import com.thinklab.application.usecase.InitiatePatchPolicyUseCase;
import com.thinklab.application.usecase.ReportPatchFindingUseCase;
import com.thinklab.application.usecase.ReportPatchFindingUseCase.Reported;
import com.thinklab.application.usecase.RetrievePatchFindingAuditLogUseCase;
import com.thinklab.application.usecase.RetrievePatchFindingUseCase;
import com.thinklab.application.usecase.RetrievePatchFindingsUseCase;
import com.thinklab.application.usecase.RetrievePatchPoliciesUseCase;
import com.thinklab.application.usecase.RetrievePatchPolicyAuditLogUseCase;
import com.thinklab.application.usecase.RetrievePatchPolicyUseCase;
import com.thinklab.application.usecase.RetrievePromisesUseCase;
import com.thinklab.application.dto.response.PromiseResponse;
import com.thinklab.application.usecase.UpdatePatchPolicyUseCase;
import com.thinklab.domain.model.PatchFinding.FindingStatus;
import com.thinklab.domain.model.PatchFinding.WaiverReason;
import com.thinklab.domain.model.PatchPolicy.Compliance;
import com.thinklab.domain.model.PatchPolicy.PolicyStatus;
import com.thinklab.domain.model.Severity;
import com.thinklab.domain.repository.PatchFindingRepository.Filter;
import io.micronaut.http.HttpStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/** The controller only reads headers and delegates: tenant, executor and role always travel to the use case. */
@ExtendWith(MockitoExtension.class)
class PatchRegistryControllerTest {

    private static final String EXECUTOR = "op-1";
    private static final String ROLE = "ENGINEER";
    private final UUID tenant = UUID.randomUUID();
    private final String tenantHeader = tenant.toString();
    private final UUID id = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-07T12:00:00Z");

    @Mock private InitiatePatchPolicyUseCase initiatePolicy;
    @Mock private RetrievePatchPolicyUseCase retrievePolicy;
    @Mock private RetrievePatchPoliciesUseCase retrievePolicies;
    @Mock private UpdatePatchPolicyUseCase updatePolicy;
    @Mock private ControlPatchPolicyUseCase controlPolicy;
    @Mock private RetrievePatchPolicyAuditLogUseCase policyAuditLog;
    @Mock private ReportPatchFindingUseCase reportFinding;
    @Mock private RetrievePatchFindingUseCase retrieveFinding;
    @Mock private RetrievePatchFindingsUseCase retrieveFindings;
    @Mock private ControlPatchFindingUseCase controlFinding;
    @Mock private RetrievePatchFindingAuditLogUseCase findingAuditLog;
    @Mock private RetrievePromisesUseCase retrievePromises;

    private PatchRegistryController controller() {
        return new PatchRegistryController(initiatePolicy, retrievePolicy, retrievePolicies, updatePolicy, controlPolicy, policyAuditLog, reportFinding, retrieveFinding,
                retrieveFindings, controlFinding, findingAuditLog, retrievePromises);
    }

    private PatchPolicyResponse policyResponse() {
        return new PatchPolicyResponse(id, tenant, "Servers", UUID.randomUUID(), 2, 7, 30, 90, "ACTIVE", "COMPLIANT", 0, 0, 0, null, now, now);
    }

    private PatchFindingResponse findingResponse() {
        return new PatchFindingResponse(id, tenant, UUID.randomUUID(), "KB1", "HIGH", now, "PENDING", now, false, null, null, null, "tool", now, now);
    }

    @Test
    @DisplayName("initiate answers 201 with the policy")
    void initiate() {
        InitiatePatchPolicyRequest request = new InitiatePatchPolicyRequest("Servers", UUID.randomUUID(), 2, 7, 30, 90);
        when(initiatePolicy.execute(tenant, request, EXECUTOR, ROLE)).thenReturn(Mono.just(policyResponse()));

        StepVerifier.create(controller().initiate(tenantHeader, EXECUTOR, ROLE, request)).assertNext(response -> assertEquals(HttpStatus.CREATED, response.getStatus())).verifyComplete();
    }

    @Test
    @DisplayName("retrieve and the list answer the use cases' result with the filters")
    void retrieve() {
        when(retrievePolicy.execute(id, tenant, ROLE)).thenReturn(Mono.just(policyResponse()));
        when(retrievePolicies.execute(tenant, PolicyStatus.ACTIVE, null, Compliance.OVERDUE, ROLE)).thenReturn(Flux.just(policyResponse()));

        StepVerifier.create(controller().retrieve(id, tenantHeader, ROLE)).assertNext(response -> assertEquals(HttpStatus.OK, response.getStatus())).verifyComplete();
        StepVerifier.create(controller().retrieveAll(tenantHeader, ROLE, PolicyStatus.ACTIVE, null, Compliance.OVERDUE)).assertNext(list -> assertEquals(1, list.size())).verifyComplete();
    }

    @Test
    @DisplayName("update, pause and resume answer 204 and pass the right action")
    void changes() {
        UpdatePatchPolicyRequest request = new UpdatePatchPolicyRequest("N", UUID.randomUUID(), 2, 7, 30, 90);
        when(updatePolicy.execute(id, tenant, request, EXECUTOR, ROLE)).thenReturn(Mono.empty());
        when(controlPolicy.execute(id, tenant, ControlPatchPolicyUseCase.Action.PAUSE, EXECUTOR, ROLE)).thenReturn(Mono.empty());
        when(controlPolicy.execute(id, tenant, ControlPatchPolicyUseCase.Action.RESUME, EXECUTOR, ROLE)).thenReturn(Mono.empty());

        StepVerifier.create(controller().update(id, tenantHeader, EXECUTOR, ROLE, request)).assertNext(response -> assertEquals(HttpStatus.NO_CONTENT, response.getStatus())).verifyComplete();
        StepVerifier.create(controller().pause(id, tenantHeader, EXECUTOR, ROLE)).assertNext(response -> assertEquals(HttpStatus.NO_CONTENT, response.getStatus())).verifyComplete();
        StepVerifier.create(controller().resume(id, tenantHeader, EXECUTOR, ROLE)).assertNext(response -> assertEquals(HttpStatus.NO_CONTENT, response.getStatus())).verifyComplete();
    }

    @Test
    @DisplayName("the audit logs answer the trails")
    void auditLogs() {
        when(policyAuditLog.execute(id, tenant, ROLE)).thenReturn(Mono.just(List.of(new AuditEntryResponse(now, "INITIATED", EXECUTOR, null, "ACTIVE", null))));
        when(findingAuditLog.execute(id, tenant, ROLE)).thenReturn(Mono.just(List.of(new AuditEntryResponse(now, "REPORTED", EXECUTOR, null, "PENDING", null))));

        StepVerifier.create(controller().auditLog(id, tenantHeader, ROLE)).assertNext(list -> assertEquals(1, list.size())).verifyComplete();
        StepVerifier.create(controller().findingAuditLog(id, tenantHeader, ROLE)).assertNext(list -> assertEquals(1, list.size())).verifyComplete();
    }

    @Test
    @DisplayName("a new finding is 201 and one already reported is 200")
    void reportStatus() {
        ReportPatchFindingRequest request = new ReportPatchFindingRequest(UUID.randomUUID(), "KB1", Severity.HIGH, now);
        when(reportFinding.execute(tenant, request, EXECUTOR, ROLE)).thenReturn(Mono.just(new Reported(findingResponse(), true))).thenReturn(Mono.just(new Reported(findingResponse(), false)));

        StepVerifier.create(controller().reportFinding(tenantHeader, EXECUTOR, ROLE, request)).assertNext(response -> assertEquals(HttpStatus.CREATED, response.getStatus())).verifyComplete();
        StepVerifier.create(controller().reportFinding(tenantHeader, EXECUTOR, ROLE, request)).assertNext(response -> assertEquals(HttpStatus.OK, response.getStatus())).verifyComplete();
    }

    @Test
    @DisplayName("one finding and the finding list, with the filter and the limit")
    void findings() {
        UUID policyId = UUID.randomUUID();
        when(retrieveFinding.execute(id, tenant, ROLE)).thenReturn(Mono.just(findingResponse()));
        when(retrieveFindings.execute(tenant, new Filter(policyId, FindingStatus.PENDING, Severity.HIGH), 10, ROLE)).thenReturn(Flux.just(findingResponse()));

        StepVerifier.create(controller().retrieveFinding(id, tenantHeader, ROLE)).assertNext(response -> assertEquals(HttpStatus.OK, response.getStatus())).verifyComplete();
        StepVerifier.create(controller().retrieveFindings(tenantHeader, ROLE, policyId, FindingStatus.PENDING, Severity.HIGH, 10)).assertNext(list -> assertEquals(1, list.size())).verifyComplete();
    }

    @Test
    @DisplayName("apply, waive and unwaive answer 204; apply without a body means now")
    void control() {
        ApplyPatchFindingRequest apply = new ApplyPatchFindingRequest(now);
        WaivePatchFindingRequest waive = new WaivePatchFindingRequest(WaiverReason.ACCEPTED_RISK, now);
        when(controlFinding.apply(id, tenant, apply, EXECUTOR, ROLE)).thenReturn(Mono.empty());
        when(controlFinding.apply(id, tenant, new ApplyPatchFindingRequest(null), EXECUTOR, ROLE)).thenReturn(Mono.empty());
        when(controlFinding.waive(id, tenant, waive, EXECUTOR, ROLE)).thenReturn(Mono.empty());
        when(controlFinding.unwaive(id, tenant, EXECUTOR, ROLE)).thenReturn(Mono.empty());

        StepVerifier.create(controller().apply(id, tenantHeader, EXECUTOR, ROLE, apply)).assertNext(response -> assertEquals(HttpStatus.NO_CONTENT, response.getStatus())).verifyComplete();
        StepVerifier.create(controller().apply(id, tenantHeader, EXECUTOR, ROLE, null)).assertNext(response -> assertEquals(HttpStatus.NO_CONTENT, response.getStatus())).verifyComplete();
        StepVerifier.create(controller().waive(id, tenantHeader, EXECUTOR, ROLE, waive)).assertNext(response -> assertEquals(HttpStatus.NO_CONTENT, response.getStatus())).verifyComplete();
        StepVerifier.create(controller().unwaive(id, tenantHeader, EXECUTOR, ROLE)).assertNext(response -> assertEquals(HttpStatus.NO_CONTENT, response.getStatus())).verifyComplete();
    }

    @Test
    @DisplayName("the promises route passes the tenant, the role and the two parameters (platform ADR-001)")
    void promises() {
        when(retrievePromises.execute(tenant, 7, 50, ROLE)).thenReturn(Mono.just(List.of(new PromiseResponse(UUID.randomUUID(), "P", null, "KEPT", null, "ACTIVE", now))));

        StepVerifier.create(controller().promises(tenantHeader, ROLE, 7, 50)).assertNext(promises -> assertEquals(1, promises.size())).verifyComplete();
    }
}
