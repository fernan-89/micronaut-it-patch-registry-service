package com.thinklab.infrastructure.adapter.out.persistence.entity;

import com.thinklab.domain.model.PatchFinding;
import com.thinklab.domain.model.PatchFinding.FindingAuditEntry;
import com.thinklab.domain.model.PatchFinding.FindingStatus;
import com.thinklab.domain.model.PatchFinding.WaiverReason;
import com.thinklab.domain.model.Severity;
import io.micronaut.core.annotation.Introspected;
import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** Infrastructure-specific representation of the PatchFinding Aggregate for MongoDB. */
@Introspected
public class PatchFindingDocument {

    @BsonId
    private UUID id;

    private UUID organisationId;
    private UUID policyId;
    private String patchRef;
    private String severity;
    private Instant announcedAt;
    private String status;
    private Instant appliedAt;
    private String waiverReason;
    private Instant waivedUntil;
    private String reportedBy;
    private Instant reportedAt;
    private Instant updatedAt;
    private List<AuditEntryDocument> auditTrail = new ArrayList<>();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getOrganisationId() { return organisationId; }
    public void setOrganisationId(UUID organisationId) { this.organisationId = organisationId; }
    public UUID getPolicyId() { return policyId; }
    public void setPolicyId(UUID policyId) { this.policyId = policyId; }
    public String getPatchRef() { return patchRef; }
    public void setPatchRef(String patchRef) { this.patchRef = patchRef; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public Instant getAnnouncedAt() { return announcedAt; }
    public void setAnnouncedAt(Instant announcedAt) { this.announcedAt = announcedAt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getAppliedAt() { return appliedAt; }
    public void setAppliedAt(Instant appliedAt) { this.appliedAt = appliedAt; }
    public String getWaiverReason() { return waiverReason; }
    public void setWaiverReason(String waiverReason) { this.waiverReason = waiverReason; }
    public Instant getWaivedUntil() { return waivedUntil; }
    public void setWaivedUntil(Instant waivedUntil) { this.waivedUntil = waivedUntil; }
    public String getReportedBy() { return reportedBy; }
    public void setReportedBy(String reportedBy) { this.reportedBy = reportedBy; }
    public Instant getReportedAt() { return reportedAt; }
    public void setReportedAt(Instant reportedAt) { this.reportedAt = reportedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public List<AuditEntryDocument> getAuditTrail() { return auditTrail; }
    public void setAuditTrail(List<AuditEntryDocument> auditTrail) { this.auditTrail = auditTrail; }

    @Introspected
    public record AuditEntryDocument(Instant occurredAt, String action, String executor, String fromStatus, String toStatus, String detail) {

        public static AuditEntryDocument fromDomain(FindingAuditEntry entry) {
            return new AuditEntryDocument(entry.occurredAt(), entry.action(), entry.executor(), entry.fromStatus() != null ? entry.fromStatus().name() : null,
                    entry.toStatus().name(), entry.detail());
        }

        // toStatus has no null branch: fromDomain always writes entry.toStatus().name().
        FindingAuditEntry toDomain() {
            return new FindingAuditEntry(occurredAt, action, executor, fromStatus != null ? FindingStatus.valueOf(fromStatus) : null, FindingStatus.valueOf(toStatus), detail);
        }
    }

    public static final class PatchFindingPersistenceMapper {

        private PatchFindingPersistenceMapper() { throw new UnsupportedOperationException(); }

        public static PatchFindingDocument toDocument(PatchFinding finding) {
            PatchFindingDocument doc = new PatchFindingDocument();
            doc.setId(finding.getId());
            doc.setOrganisationId(finding.getOrganisationId());
            doc.setPolicyId(finding.getPolicyId());
            doc.setPatchRef(finding.getPatchRef());
            doc.setSeverity(finding.getSeverity().name());
            doc.setAnnouncedAt(finding.getAnnouncedAt());
            doc.setStatus(finding.getStatus().name());
            doc.setAppliedAt(finding.getAppliedAt());
            doc.setWaiverReason(finding.getWaiverReason() != null ? finding.getWaiverReason().name() : null);
            doc.setWaivedUntil(finding.getWaivedUntil());
            doc.setReportedBy(finding.getReportedBy());
            doc.setReportedAt(finding.getReportedAt());
            doc.setUpdatedAt(finding.getUpdatedAt());
            doc.setAuditTrail(finding.getAuditTrail().stream().map(AuditEntryDocument::fromDomain).collect(Collectors.toCollection(ArrayList::new)));
            return doc;
        }

        public static PatchFinding toDomain(PatchFindingDocument doc) {
            return PatchFinding.reconstitute(doc.getId(), doc.getOrganisationId(), doc.getPolicyId(), doc.getPatchRef(), Severity.valueOf(doc.getSeverity()),
                    doc.getAnnouncedAt(), FindingStatus.valueOf(doc.getStatus()), doc.getAppliedAt(),
                    doc.getWaiverReason() != null ? WaiverReason.valueOf(doc.getWaiverReason()) : null, doc.getWaivedUntil(), doc.getReportedBy(), doc.getReportedAt(),
                    doc.getUpdatedAt(), doc.getAuditTrail().stream().map(AuditEntryDocument::toDomain).collect(Collectors.toList()));
        }
    }
}
