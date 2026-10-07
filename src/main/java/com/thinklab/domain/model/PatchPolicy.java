package com.thinklab.domain.model;

import com.thinklab.domain.exception.InvalidPatchPolicyStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Aggregate Root (BIAN Control Record) of the IT Patch Registry Service Domain: the promise made about one asset. How many days a patch of each
 * severity may stay unapplied after it was announced. The scanners and tools report the patches that are available ({@link PatchFinding}) and
 * when they were applied; the policy judges itself when read (ADR-031). Nothing here scans an asset or applies a patch.
 */
public class PatchPolicy {

    public static final int MAX_DAYS = 730;
    public static final int MAX_NAME_LENGTH = 80;

    private final UUID id;
    private final UUID organisationId;
    private String name;
    private UUID assetId;
    private int criticalDays;
    private int highDays;
    private int mediumDays;
    private int lowDays;
    private PolicyStatus status;
    private final Instant createdAt;
    private Instant updatedAt;
    private final List<PolicyAuditEntry> auditTrail;

    private PatchPolicy(UUID id, UUID organisationId, String name, UUID assetId, int criticalDays, int highDays, int mediumDays, int lowDays, String executor) {
        this.id = id;
        this.organisationId = organisationId;
        this.name = name;
        this.assetId = assetId;
        this.criticalDays = criticalDays;
        this.highDays = highDays;
        this.mediumDays = mediumDays;
        this.lowDays = lowDays;
        this.status = PolicyStatus.ACTIVE;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        this.auditTrail = new ArrayList<>();
        this.auditTrail.add(new PolicyAuditEntry(this.createdAt, "INITIATED", executor, null, PolicyStatus.ACTIVE, "Patch policy for asset " + assetId + "."));
    }

    private PatchPolicy(UUID id, UUID organisationId, String name, UUID assetId, int criticalDays, int highDays, int mediumDays, int lowDays, PolicyStatus status,
                        Instant createdAt, Instant updatedAt, List<PolicyAuditEntry> auditTrail) {
        this.id = id;
        this.organisationId = organisationId;
        this.name = name;
        this.assetId = assetId;
        this.criticalDays = criticalDays;
        this.highDays = highDays;
        this.mediumDays = mediumDays;
        this.lowDays = lowDays;
        this.status = status != null ? status : PolicyStatus.ACTIVE;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.updatedAt = updatedAt != null ? updatedAt : this.createdAt;
        this.auditTrail = auditTrail != null ? new ArrayList<>(auditTrail) : new ArrayList<>();
    }

    public static PatchPolicy createNew(UUID id, UUID organisationId, String name, UUID assetId, int criticalDays, int highDays, int mediumDays, int lowDays, String executor) {
        if (id == null || organisationId == null) {
            throw new IllegalArgumentException("ID and Organisation ID are mandatory for PatchPolicy creation.");
        }
        validate(name, assetId, criticalDays, highDays, mediumDays, lowDays);
        requireExecutor(executor);
        return new PatchPolicy(id, organisationId, name, assetId, criticalDays, highDays, mediumDays, lowDays, executor);
    }

    public static PatchPolicy reconstitute(UUID id, UUID organisationId, String name, UUID assetId, int criticalDays, int highDays, int mediumDays, int lowDays,
                                           PolicyStatus status, Instant createdAt, Instant updatedAt, List<PolicyAuditEntry> auditTrail) {
        if (id == null || organisationId == null || name == null || assetId == null) {
            throw new IllegalArgumentException("ID, Organisation ID, Name and Asset ID are mandatory to reconstitute a PatchPolicy.");
        }
        return new PatchPolicy(id, organisationId, name, assetId, criticalDays, highDays, mediumDays, lowDays, status, createdAt, updatedAt, auditTrail);
    }

    // --- Domain Behaviors ---

    /** Behavior Qualifier: {@code update}. The whole definition again, in any status; the findings are not touched. */
    public PolicyAuditEntry update(String newName, UUID newAssetId, int newCriticalDays, int newHighDays, int newMediumDays, int newLowDays, String executor) {
        validate(newName, newAssetId, newCriticalDays, newHighDays, newMediumDays, newLowDays);
        requireExecutor(executor);
        this.name = newName;
        this.assetId = newAssetId;
        this.criticalDays = newCriticalDays;
        this.highDays = newHighDays;
        this.mediumDays = newMediumDays;
        this.lowDays = newLowDays;
        return record("UPDATED", executor, this.status, this.status, "Policy updated.");
    }

    /** Behavior Qualifier: {@code control/pause}. ACTIVE -&gt; PAUSED: the policy is no longer judged. */
    public PolicyAuditEntry pause(String executor) {
        requireStatus(PolicyStatus.ACTIVE);
        requireExecutor(executor);
        this.status = PolicyStatus.PAUSED;
        return record("PAUSED", executor, PolicyStatus.ACTIVE, PolicyStatus.PAUSED, "Paused: the policy is no longer judged.");
    }

    /** Behavior Qualifier: {@code control/resume}. PAUSED -&gt; ACTIVE. */
    public PolicyAuditEntry resume(String executor) {
        requireStatus(PolicyStatus.PAUSED);
        requireExecutor(executor);
        this.status = PolicyStatus.ACTIVE;
        return record("RESUMED", executor, PolicyStatus.PAUSED, PolicyStatus.ACTIVE, "Resumed.");
    }

    /** How many days a patch of that severity may stay unapplied after it was announced. */
    public int daysFor(Severity severity) {
        return switch (severity) {
            case CRITICAL -> criticalDays;
            case HIGH -> highDays;
            case MEDIUM -> mediumDays;
            case LOW -> lowDays;
        };
    }

    /** When a finding of this policy must be applied by. */
    public Instant dueAt(PatchFinding finding) {
        return finding.getAnnouncedAt().plus(Duration.ofDays(daysFor(finding.getSeverity())));
    }

    /**
     * How well the promise holds at that moment (ADR-031), from the policy's own findings. A paused policy is not judged. A finding is open while it
     * is PENDING, or WAIVED with a waiver that has run out; it is overdue when it is open past its deadline. Applied findings and findings under a
     * waiver still in force do not count.
     */
    public Assessment assess(Instant now, Collection<PatchFinding> findings) {
        int open = 0;
        int overdue = 0;
        int waived = 0;
        Instant oldestOverdue = null;
        for (PatchFinding finding : findings) {
            if (finding.isWaivedAt(now)) {
                waived++;
            } else if (finding.isOpenAt(now)) {
                open++;
                Instant due = dueAt(finding);
                if (now.isAfter(due)) {
                    overdue++;
                    if (oldestOverdue == null || due.isBefore(oldestOverdue)) {
                        oldestOverdue = due;
                    }
                }
            }
        }
        if (status == PolicyStatus.PAUSED) {
            return new Assessment(Compliance.PAUSED, open, 0, waived, null);
        }
        return new Assessment(overdue > 0 ? Compliance.OVERDUE : Compliance.COMPLIANT, open, overdue, waived, oldestOverdue);
    }

    // --- Internal helpers ---

    private PolicyAuditEntry record(String action, String executor, PolicyStatus from, PolicyStatus to, String detail) {
        this.updatedAt = Instant.now();
        PolicyAuditEntry entry = new PolicyAuditEntry(this.updatedAt, action, executor, from, to, detail);
        this.auditTrail.add(entry);
        return entry;
    }

    private void requireStatus(PolicyStatus expected) {
        if (this.status != expected) {
            throw new InvalidPatchPolicyStatusException(String.format("Illegal transition: PatchPolicy is [%s], expected [%s].", this.status, expected));
        }
    }

    private static void requireExecutor(String executor) {
        if (executor == null || executor.isBlank()) {
            throw new IllegalArgumentException("Executor is mandatory for auditable PatchPolicy mutations.");
        }
    }

    private static void validate(String name, UUID assetId, int criticalDays, int highDays, int mediumDays, int lowDays) {
        if (name == null || name.isBlank() || name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("Name is mandatory for a PatchPolicy (up to " + MAX_NAME_LENGTH + " characters).");
        }
        if (assetId == null) {
            throw new IllegalArgumentException("The asset the policy covers is mandatory.");
        }
        for (int days : new int[]{criticalDays, highDays, mediumDays, lowDays}) {
            if (days < 1 || days > MAX_DAYS) {
                throw new IllegalArgumentException("The days allowed for a patch must be between 1 and " + MAX_DAYS + ".");
            }
        }
        if (criticalDays > highDays || highDays > mediumDays || mediumDays > lowDays) {
            throw new IllegalArgumentException("A more severe patch cannot be given more time than a less severe one: critical <= high <= medium <= low.");
        }
    }

    // --- Getters ---

    public UUID getId() { return id; }
    public UUID getOrganisationId() { return organisationId; }
    public String getName() { return name; }
    public UUID getAssetId() { return assetId; }
    public int getCriticalDays() { return criticalDays; }
    public int getHighDays() { return highDays; }
    public int getMediumDays() { return mediumDays; }
    public int getLowDays() { return lowDays; }
    public PolicyStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<PolicyAuditEntry> getAuditTrail() { return Collections.unmodifiableList(auditTrail); }

    // --- Nested Value Objects ---

    public enum PolicyStatus { ACTIVE, PAUSED }

    /** PAUSED is not judged; COMPLIANT has no open finding past its deadline; OVERDUE has at least one. */
    public enum Compliance { PAUSED, COMPLIANT, OVERDUE }

    public record PolicyAuditEntry(Instant occurredAt, String action, String executor, PolicyStatus fromStatus, PolicyStatus toStatus, String detail) {}

    /** The judgment of one moment: nothing of it is stored. {@code oldestOverdueDueAt} is null unless something is overdue. */
    public record Assessment(Compliance compliance, int openFindings, int overdueFindings, int waivedFindings, Instant oldestOverdueDueAt) {}
}
