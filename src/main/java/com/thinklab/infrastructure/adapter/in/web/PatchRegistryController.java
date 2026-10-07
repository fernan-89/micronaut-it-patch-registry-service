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
import com.thinklab.application.usecase.RetrievePatchFindingAuditLogUseCase;
import com.thinklab.application.usecase.RetrievePatchFindingUseCase;
import com.thinklab.application.usecase.RetrievePatchFindingsUseCase;
import com.thinklab.application.usecase.RetrievePatchPoliciesUseCase;
import com.thinklab.application.usecase.RetrievePatchPolicyAuditLogUseCase;
import com.thinklab.application.usecase.RetrievePatchPolicyUseCase;
import com.thinklab.application.usecase.UpdatePatchPolicyUseCase;
import com.thinklab.domain.model.PatchFinding.FindingStatus;
import com.thinklab.domain.model.PatchPolicy.Compliance;
import com.thinklab.domain.model.PatchPolicy.PolicyStatus;
import com.thinklab.domain.model.Severity;
import com.thinklab.domain.repository.PatchFindingRepository;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.annotation.QueryValue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * Inbound Web Adapter for the {@code it-patch-registry} Service Domain.
 *
 * <p><b>BIAN-Aligned Resource Model (ADR-013):</b> {@link com.thinklab.domain.model.PatchPolicy} is the Control Record, at the root;
 * {@link com.thinklab.domain.model.PatchFinding} is the secondary aggregate, under {@code /finding}: a patch the scanner reported as available,
 * that a tool later reports applied or that staff waive for a fixed reason and a limited time. There is no {@code DELETE}: a policy is paused.
 *
 * <p><b>Tenant on every route, staff only (ADR-032):</b> {@code X-Tenant-Id} scopes every lookup (another tenant's policy is a 404) and
 * {@code X-Role} makes every route refuse a {@code REQUESTER} with 403 {@code ERR-PCH-00403}.
 */
@Controller("/it-patch-registry/v1")
public class PatchRegistryController {

    private static final Logger log = LoggerFactory.getLogger(PatchRegistryController.class);
    static final String TENANT_HEADER = "X-Tenant-Id";
    static final String EXECUTOR_HEADER = "X-Executor";
    static final String ROLE_HEADER = "X-Role";

    private final InitiatePatchPolicyUseCase initiatePolicy;
    private final RetrievePatchPolicyUseCase retrievePolicy;
    private final RetrievePatchPoliciesUseCase retrievePolicies;
    private final UpdatePatchPolicyUseCase updatePolicy;
    private final ControlPatchPolicyUseCase controlPolicy;
    private final RetrievePatchPolicyAuditLogUseCase policyAuditLog;
    private final ReportPatchFindingUseCase reportFinding;
    private final RetrievePatchFindingUseCase retrieveFinding;
    private final RetrievePatchFindingsUseCase retrieveFindings;
    private final ControlPatchFindingUseCase controlFinding;
    private final RetrievePatchFindingAuditLogUseCase findingAuditLog;

    public PatchRegistryController(InitiatePatchPolicyUseCase initiatePolicy, RetrievePatchPolicyUseCase retrievePolicy, RetrievePatchPoliciesUseCase retrievePolicies,
                                   UpdatePatchPolicyUseCase updatePolicy, ControlPatchPolicyUseCase controlPolicy, RetrievePatchPolicyAuditLogUseCase policyAuditLog,
                                   ReportPatchFindingUseCase reportFinding, RetrievePatchFindingUseCase retrieveFinding, RetrievePatchFindingsUseCase retrieveFindings,
                                   ControlPatchFindingUseCase controlFinding, RetrievePatchFindingAuditLogUseCase findingAuditLog) {
        this.initiatePolicy = initiatePolicy;
        this.retrievePolicy = retrievePolicy;
        this.retrievePolicies = retrievePolicies;
        this.updatePolicy = updatePolicy;
        this.controlPolicy = controlPolicy;
        this.policyAuditLog = policyAuditLog;
        this.reportFinding = reportFinding;
        this.retrieveFinding = retrieveFinding;
        this.retrieveFindings = retrieveFindings;
        this.controlFinding = controlFinding;
        this.findingAuditLog = findingAuditLog;
    }

    // ------------------------------------------------------------------ Policies

    /** Behavior Qualifier: {@code initiate}. How long a patch of each severity may wait on an asset. */
    @Post("/initiate")
    public Mono<HttpResponse<PatchPolicyResponse>> initiate(
            @Header(TENANT_HEADER) @NotBlank String tenantId, @Header(EXECUTOR_HEADER) @NotBlank String executor,
            @Header(ROLE_HEADER) @Nullable String role, @Body @Valid InitiatePatchPolicyRequest request
    ) {
        log.info("[ACTION: INITIATE_PATCH_POLICY] [EXECUTOR: {}] Received request for organisation: {}", executor, tenantId);

        return initiatePolicy.execute(UUID.fromString(tenantId), request, executor, role).map(HttpResponse::created);
    }

    /** Behavior Qualifier: {@code retrieve}. One policy of the tenant, judged now. */
    @Get("/{id}/retrieve")
    public Mono<HttpResponse<PatchPolicyResponse>> retrieve(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId, @Header(ROLE_HEADER) @Nullable String role) {
        return Mono.defer(() -> retrievePolicy.execute(id, UUID.fromString(tenantId), role)).map(HttpResponse::ok);
    }

    /** Behavior Qualifier: {@code retrieve} (collection), oldest first, filterable by status, asset and compliance. */
    @Get("/retrieve")
    public Mono<List<PatchPolicyResponse>> retrieveAll(@Header(TENANT_HEADER) @NotBlank String tenantId, @Header(ROLE_HEADER) @Nullable String role,
                                                       @QueryValue @Nullable PolicyStatus status, @QueryValue @Nullable UUID assetId, @QueryValue @Nullable Compliance compliance) {
        return Mono.defer(() -> retrievePolicies.execute(UUID.fromString(tenantId), status, assetId, compliance, role).collectList());
    }

    /** Behavior Qualifier: {@code update}. The whole definition again. */
    @Put("/{id}/update")
    public Mono<HttpResponse<Void>> update(
            @PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId, @Header(EXECUTOR_HEADER) @NotBlank String executor,
            @Header(ROLE_HEADER) @Nullable String role, @Body @Valid UpdatePatchPolicyRequest request
    ) {
        return Mono.defer(() -> updatePolicy.execute(id, UUID.fromString(tenantId), request, executor, role)).thenReturn(HttpResponse.noContent());
    }

    /** Behavior Qualifier: {@code control/pause}. ACTIVE -&gt; PAUSED: the policy is no longer judged. */
    @Put("/{id}/control/pause")
    public Mono<HttpResponse<Void>> pause(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId,
                                          @Header(EXECUTOR_HEADER) @NotBlank String executor, @Header(ROLE_HEADER) @Nullable String role) {
        return control(id, tenantId, ControlPatchPolicyUseCase.Action.PAUSE, executor, role);
    }

    /** Behavior Qualifier: {@code control/resume}. PAUSED -&gt; ACTIVE. */
    @Put("/{id}/control/resume")
    public Mono<HttpResponse<Void>> resume(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId,
                                           @Header(EXECUTOR_HEADER) @NotBlank String executor, @Header(ROLE_HEADER) @Nullable String role) {
        return control(id, tenantId, ControlPatchPolicyUseCase.Action.RESUME, executor, role);
    }

    /** Behavior Qualifier: {@code audit-log/retrieve}. */
    @Get("/{id}/audit-log/retrieve")
    public Mono<List<AuditEntryResponse>> auditLog(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId, @Header(ROLE_HEADER) @Nullable String role) {
        return Mono.defer(() -> policyAuditLog.execute(id, UUID.fromString(tenantId), role));
    }

    // ------------------------------------------------------------------ Findings

    /** Behavior Qualifier: {@code finding/initiate}. What the scanner reports when a patch becomes available: 201 for a new finding, 200 for one already reported. */
    @Post("/finding/initiate")
    public Mono<HttpResponse<PatchFindingResponse>> reportFinding(
            @Header(TENANT_HEADER) @NotBlank String tenantId, @Header(EXECUTOR_HEADER) @NotBlank String executor,
            @Header(ROLE_HEADER) @Nullable String role, @Body @Valid ReportPatchFindingRequest request
    ) {
        log.info("[ACTION: REPORT_PATCH_FINDING] [EXECUTOR: {}] Received request for organisation: {}", executor, tenantId);

        return reportFinding.execute(UUID.fromString(tenantId), request, executor, role)
                .map(reported -> reported.created() ? HttpResponse.created(reported.finding()) : HttpResponse.ok(reported.finding()));
    }

    /** Behavior Qualifier: {@code finding/retrieve}. One finding of the tenant, with its deadline. */
    @Get("/finding/{id}/retrieve")
    public Mono<HttpResponse<PatchFindingResponse>> retrieveFinding(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId, @Header(ROLE_HEADER) @Nullable String role) {
        return Mono.defer(() -> retrieveFinding.execute(id, UUID.fromString(tenantId), role)).map(HttpResponse::ok);
    }

    /** Behavior Qualifier: {@code finding/retrieve} (collection), newest announcement first, at most 200 (50 unless asked). */
    @Get("/finding/retrieve")
    public Mono<List<PatchFindingResponse>> retrieveFindings(@Header(TENANT_HEADER) @NotBlank String tenantId, @Header(ROLE_HEADER) @Nullable String role,
                                                             @QueryValue @Nullable UUID policyId, @QueryValue @Nullable FindingStatus status, @QueryValue @Nullable Severity severity,
                                                             @QueryValue @Nullable Integer limit) {
        return Mono.defer(() -> retrieveFindings.execute(UUID.fromString(tenantId), new PatchFindingRepository.Filter(policyId, status, severity), limit, role).collectList());
    }

    /** Behavior Qualifier: {@code finding/control/apply}. The tool reports the patch applied (when it was, or now). */
    @Put("/finding/{id}/control/apply")
    public Mono<HttpResponse<Void>> apply(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId, @Header(EXECUTOR_HEADER) @NotBlank String executor,
                                          @Header(ROLE_HEADER) @Nullable String role, @Body @Nullable ApplyPatchFindingRequest request) {
        ApplyPatchFindingRequest effective = request != null ? request : new ApplyPatchFindingRequest(null);
        return Mono.defer(() -> controlFinding.apply(id, UUID.fromString(tenantId), effective, executor, role)).thenReturn(HttpResponse.noContent());
    }

    /** Behavior Qualifier: {@code finding/control/waive}. Staff leave the patch unapplied for a fixed reason and a limited time. */
    @Put("/finding/{id}/control/waive")
    public Mono<HttpResponse<Void>> waive(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId, @Header(EXECUTOR_HEADER) @NotBlank String executor,
                                          @Header(ROLE_HEADER) @Nullable String role, @Body @Valid WaivePatchFindingRequest request) {
        return Mono.defer(() -> controlFinding.waive(id, UUID.fromString(tenantId), request, executor, role)).thenReturn(HttpResponse.noContent());
    }

    /** Behavior Qualifier: {@code finding/control/unwaive}. WAIVED -&gt; PENDING at once. */
    @Put("/finding/{id}/control/unwaive")
    public Mono<HttpResponse<Void>> unwaive(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId, @Header(EXECUTOR_HEADER) @NotBlank String executor,
                                            @Header(ROLE_HEADER) @Nullable String role) {
        return Mono.defer(() -> controlFinding.unwaive(id, UUID.fromString(tenantId), executor, role)).thenReturn(HttpResponse.noContent());
    }

    /** Behavior Qualifier: {@code finding/audit-log/retrieve}. */
    @Get("/finding/{id}/audit-log/retrieve")
    public Mono<List<AuditEntryResponse>> findingAuditLog(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId, @Header(ROLE_HEADER) @Nullable String role) {
        return Mono.defer(() -> findingAuditLog.execute(id, UUID.fromString(tenantId), role));
    }

    private Mono<HttpResponse<Void>> control(UUID id, String tenantId, ControlPatchPolicyUseCase.Action action, String executor, String role) {
        log.info("[ACTION: {}_PATCH_POLICY] [EXECUTOR: {}] for ID: {}", action, executor, id);

        return Mono.defer(() -> controlPolicy.execute(id, UUID.fromString(tenantId), action, executor, role)).thenReturn(HttpResponse.noContent());
    }
}
