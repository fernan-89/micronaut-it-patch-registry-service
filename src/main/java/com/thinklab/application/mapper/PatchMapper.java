package com.thinklab.application.mapper;

import com.thinklab.application.dto.response.AuditEntryResponse;
import com.thinklab.application.dto.response.PatchFindingResponse;
import com.thinklab.application.dto.response.PatchPolicyResponse;
import com.thinklab.domain.model.PatchFinding;
import com.thinklab.domain.model.PatchFinding.FindingAuditEntry;
import com.thinklab.domain.model.PatchPolicy;
import com.thinklab.domain.model.PatchPolicy.Assessment;
import com.thinklab.domain.model.PatchPolicy.PolicyAuditEntry;

import java.time.Instant;
import java.util.Collection;

public final class PatchMapper {

    private PatchMapper() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    /** The policy as it stands, judged at {@code now} over its unresolved findings. */
    public static PatchPolicyResponse toResponse(PatchPolicy policy, Collection<PatchFinding> unresolved, Instant now) {
        Assessment judged = policy.assess(now, unresolved);
        return new PatchPolicyResponse(policy.getId(), policy.getOrganisationId(), policy.getName(), policy.getAssetId(), policy.getCriticalDays(), policy.getHighDays(),
                policy.getMediumDays(), policy.getLowDays(), policy.getStatus().name(), judged.compliance().name(), judged.openFindings(), judged.overdueFindings(),
                judged.waivedFindings(), judged.oldestOverdueDueAt(), policy.getCreatedAt(), policy.getUpdatedAt());
    }

    /** The finding with its deadline taken from its policy; {@code policy} is null when the policy is gone (then there is no deadline). */
    public static PatchFindingResponse toResponse(PatchFinding finding, PatchPolicy policy, Instant now) {
        Instant due = policy != null ? policy.dueAt(finding) : null;
        boolean overdue = due != null && finding.isOpenAt(now) && now.isAfter(due);
        return new PatchFindingResponse(finding.getId(), finding.getOrganisationId(), finding.getPolicyId(), finding.getPatchRef(), finding.getSeverity().name(),
                finding.getAnnouncedAt(), finding.getStatus().name(), due, overdue, finding.getAppliedAt(),
                finding.getWaiverReason() != null ? finding.getWaiverReason().name() : null, finding.getWaivedUntil(), finding.getReportedBy(), finding.getReportedAt(),
                finding.getUpdatedAt());
    }

    public static AuditEntryResponse toResponse(PolicyAuditEntry entry) {
        return new AuditEntryResponse(entry.occurredAt(), entry.action(), entry.executor(), entry.fromStatus() != null ? entry.fromStatus().name() : null,
                entry.toStatus().name(), entry.detail());
    }

    public static AuditEntryResponse toResponse(FindingAuditEntry entry) {
        return new AuditEntryResponse(entry.occurredAt(), entry.action(), entry.executor(), entry.fromStatus() != null ? entry.fromStatus().name() : null,
                entry.toStatus().name(), entry.detail());
    }
}
