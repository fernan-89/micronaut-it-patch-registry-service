package com.thinklab.infrastructure.adapter.out.persistence.entity;

import com.thinklab.domain.model.PatchPolicy;
import com.thinklab.domain.model.PatchPolicy.PolicyAuditEntry;
import com.thinklab.domain.model.PatchPolicy.PolicyStatus;
import io.micronaut.core.annotation.Introspected;
import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** Infrastructure-specific representation of the PatchPolicy Aggregate for MongoDB. */
@Introspected
public class PatchPolicyDocument {

    @BsonId
    private UUID id;

    private UUID organisationId;
    private String name;
    private UUID assetId;
    private int criticalDays;
    private int highDays;
    private int mediumDays;
    private int lowDays;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
    private List<AuditEntryDocument> auditTrail = new ArrayList<>();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getOrganisationId() { return organisationId; }
    public void setOrganisationId(UUID organisationId) { this.organisationId = organisationId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public UUID getAssetId() { return assetId; }
    public void setAssetId(UUID assetId) { this.assetId = assetId; }
    public int getCriticalDays() { return criticalDays; }
    public void setCriticalDays(int criticalDays) { this.criticalDays = criticalDays; }
    public int getHighDays() { return highDays; }
    public void setHighDays(int highDays) { this.highDays = highDays; }
    public int getMediumDays() { return mediumDays; }
    public void setMediumDays(int mediumDays) { this.mediumDays = mediumDays; }
    public int getLowDays() { return lowDays; }
    public void setLowDays(int lowDays) { this.lowDays = lowDays; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public List<AuditEntryDocument> getAuditTrail() { return auditTrail; }
    public void setAuditTrail(List<AuditEntryDocument> auditTrail) { this.auditTrail = auditTrail; }

    @Introspected
    public record AuditEntryDocument(Instant occurredAt, String action, String executor, String fromStatus, String toStatus, String detail) {

        public static AuditEntryDocument fromDomain(PolicyAuditEntry entry) {
            return new AuditEntryDocument(entry.occurredAt(), entry.action(), entry.executor(), entry.fromStatus() != null ? entry.fromStatus().name() : null,
                    entry.toStatus().name(), entry.detail());
        }

        // toStatus has no null branch: fromDomain always writes entry.toStatus().name().
        PolicyAuditEntry toDomain() {
            return new PolicyAuditEntry(occurredAt, action, executor, fromStatus != null ? PolicyStatus.valueOf(fromStatus) : null, PolicyStatus.valueOf(toStatus), detail);
        }
    }

    public static final class PatchPolicyPersistenceMapper {

        private PatchPolicyPersistenceMapper() { throw new UnsupportedOperationException(); }

        public static PatchPolicyDocument toDocument(PatchPolicy policy) {
            PatchPolicyDocument doc = new PatchPolicyDocument();
            doc.setId(policy.getId());
            doc.setOrganisationId(policy.getOrganisationId());
            doc.setName(policy.getName());
            doc.setAssetId(policy.getAssetId());
            doc.setCriticalDays(policy.getCriticalDays());
            doc.setHighDays(policy.getHighDays());
            doc.setMediumDays(policy.getMediumDays());
            doc.setLowDays(policy.getLowDays());
            doc.setStatus(policy.getStatus().name());
            doc.setCreatedAt(policy.getCreatedAt());
            doc.setUpdatedAt(policy.getUpdatedAt());
            doc.setAuditTrail(policy.getAuditTrail().stream().map(AuditEntryDocument::fromDomain).collect(Collectors.toCollection(ArrayList::new)));
            return doc;
        }

        public static PatchPolicy toDomain(PatchPolicyDocument doc) {
            return PatchPolicy.reconstitute(doc.getId(), doc.getOrganisationId(), doc.getName(), doc.getAssetId(), doc.getCriticalDays(), doc.getHighDays(),
                    doc.getMediumDays(), doc.getLowDays(), PolicyStatus.valueOf(doc.getStatus()), doc.getCreatedAt(), doc.getUpdatedAt(),
                    doc.getAuditTrail().stream().map(AuditEntryDocument::toDomain).collect(Collectors.toList()));
        }
    }
}
