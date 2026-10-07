package com.thinklab.domain.model;

import com.thinklab.domain.exception.InvalidPatchFindingStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Secondary aggregate of the IT Patch Registry Service Domain: one patch that is available for the asset a policy covers, as the scanner or tool
 * reported it (ADR-030). It is identified by the patch reference (a KB number, a CVE, a package version) and is reported once per policy. It is
 * PENDING until the tool reports it APPLIED, or staff WAIVE it for a fixed reason and a limited time; a waiver that runs out makes it open again.
 * The reference is held to a plain character set so that it can only be an identifier, never a sentence from the tool.
 */
public class PatchFinding {

    public static final int MAX_REF_LENGTH = 64;
    public static final Pattern REF_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/+-]*");
    public static final int MAX_WAIVER_DAYS = 365;
    /** How far ahead of the registry's clock a time may be: the tool's clock is allowed to be a little off. */
    public static final Duration CLOCK_SKEW = Duration.ofMinutes(5);

    private final UUID id;
    private final UUID organisationId;
    private final UUID policyId;
    private final String patchRef;
    private final Severity severity;
    private final Instant announcedAt;
    private FindingStatus status;
    private Instant appliedAt;
    private WaiverReason waiverReason;
    private Instant waivedUntil;
    private final String reportedBy;
    private final Instant reportedAt;
    private Instant updatedAt;
    private final List<FindingAuditEntry> auditTrail;

    private PatchFinding(UUID id, UUID organisationId, UUID policyId, String patchRef, Severity severity, Instant announcedAt, FindingStatus status, Instant appliedAt,
                         WaiverReason waiverReason, Instant waivedUntil, String reportedBy, Instant reportedAt, Instant updatedAt, List<FindingAuditEntry> auditTrail) {
        this.id = id;
        this.organisationId = organisationId;
        this.policyId = policyId;
        this.patchRef = patchRef;
        this.severity = severity;
        this.announcedAt = announcedAt;
        this.status = status;
        this.appliedAt = appliedAt;
        this.waiverReason = waiverReason;
        this.waivedUntil = waivedUntil;
        this.reportedBy = reportedBy;
        this.reportedAt = reportedAt;
        this.updatedAt = updatedAt;
        this.auditTrail = auditTrail;
    }

    public static PatchFinding createNew(UUID id, UUID organisationId, UUID policyId, String patchRef, Severity severity, Instant announcedAt, String executor, Instant now) {
        if (id == null || organisationId == null || policyId == null) {
            throw new IllegalArgumentException("ID, Organisation ID and Policy ID are mandatory for a PatchFinding.");
        }
        if (patchRef == null || patchRef.length() > MAX_REF_LENGTH || !REF_PATTERN.matcher(patchRef).matches()) {
            throw new IllegalArgumentException("The patch reference is mandatory, up to " + MAX_REF_LENGTH + " characters, letters, digits and . _ : / + - only.");
        }
        if (severity == null || announcedAt == null) {
            throw new IllegalArgumentException("The severity and when the patch was announced are mandatory.");
        }
        Instant announced = truncate(announcedAt);
        if (announced.isAfter(now.plus(CLOCK_SKEW))) {
            throw new IllegalArgumentException("A patch cannot be announced in the future.");
        }
        requireExecutor(executor);
        List<FindingAuditEntry> trail = new ArrayList<>();
        trail.add(new FindingAuditEntry(now, "REPORTED", executor, null, FindingStatus.PENDING, "Patch " + patchRef + " reported as available."));
        return new PatchFinding(id, organisationId, policyId, patchRef, severity, announced, FindingStatus.PENDING, null, null, null, executor, now, now, trail);
    }

    public static PatchFinding reconstitute(UUID id, UUID organisationId, UUID policyId, String patchRef, Severity severity, Instant announcedAt, FindingStatus status,
                                            Instant appliedAt, WaiverReason waiverReason, Instant waivedUntil, String reportedBy, Instant reportedAt, Instant updatedAt,
                                            List<FindingAuditEntry> auditTrail) {
        if (id == null || organisationId == null || policyId == null || patchRef == null || severity == null || announcedAt == null || status == null) {
            throw new IllegalArgumentException("ID, Organisation ID, Policy ID, reference, severity, announcement and status are mandatory to reconstitute a PatchFinding.");
        }
        return new PatchFinding(id, organisationId, policyId, patchRef, severity, announcedAt, status, appliedAt, waiverReason, waivedUntil, reportedBy, reportedAt,
                updatedAt != null ? updatedAt : reportedAt, auditTrail != null ? new ArrayList<>(auditTrail) : new ArrayList<>());
    }

    /** The store keeps milliseconds: every time is cut to the millisecond before it is compared or looked up. */
    public static Instant truncate(Instant moment) {
        return moment.truncatedTo(ChronoUnit.MILLIS);
    }

    // --- Domain Behaviors ---

    /** Behavior Qualifier: {@code control/apply}. PENDING or WAIVED -&gt; APPLIED (terminal). The patch cannot have been applied before it was announced. */
    public FindingAuditEntry apply(Instant when, Instant now, String executor) {
        requireOneOf("apply", FindingStatus.PENDING, FindingStatus.WAIVED);
        requireExecutor(executor);
        if (when == null) {
            throw new IllegalArgumentException("When the patch was applied is mandatory.");
        }
        Instant applied = truncate(when);
        if (applied.isBefore(announcedAt)) {
            throw new IllegalArgumentException("A patch cannot be applied before it was announced.");
        }
        if (applied.isAfter(now.plus(CLOCK_SKEW))) {
            throw new IllegalArgumentException("A patch cannot be applied in the future.");
        }
        FindingStatus before = this.status;
        this.status = FindingStatus.APPLIED;
        this.appliedAt = applied;
        this.waiverReason = null;
        this.waivedUntil = null;
        return record("APPLIED", executor, before, FindingStatus.APPLIED, "Applied.", now);
    }

    /** Behavior Qualifier: {@code control/waive}. PENDING -&gt; WAIVED for a fixed reason and until a moment in the future, at most a year ahead. */
    public FindingAuditEntry waive(WaiverReason reason, Instant until, Instant now, String executor) {
        requireOneOf("waive", FindingStatus.PENDING);
        requireExecutor(executor);
        if (reason == null || until == null) {
            throw new IllegalArgumentException("The reason and the end of a waiver are mandatory.");
        }
        Instant end = truncate(until);
        if (!end.isAfter(now)) {
            throw new IllegalArgumentException("A waiver must end in the future.");
        }
        if (end.isAfter(now.plus(Duration.ofDays(MAX_WAIVER_DAYS)))) {
            throw new IllegalArgumentException("A waiver cannot last more than " + MAX_WAIVER_DAYS + " days.");
        }
        this.status = FindingStatus.WAIVED;
        this.waiverReason = reason;
        this.waivedUntil = end;
        return record("WAIVED", executor, FindingStatus.PENDING, FindingStatus.WAIVED, "Waived (" + reason + ") until " + end + ".", now);
    }

    /** Behavior Qualifier: {@code control/unwaive}. WAIVED -&gt; PENDING at once, before the waiver ran out. */
    public FindingAuditEntry unwaive(Instant now, String executor) {
        requireOneOf("unwaive", FindingStatus.WAIVED);
        requireExecutor(executor);
        this.status = FindingStatus.PENDING;
        this.waiverReason = null;
        this.waivedUntil = null;
        return record("UNWAIVED", executor, FindingStatus.WAIVED, FindingStatus.PENDING, "Waiver withdrawn.", now);
    }

    /** True while a waiver is in force at that moment. */
    public boolean isWaivedAt(Instant moment) {
        return status == FindingStatus.WAIVED && waivedUntil.isAfter(moment);
    }

    /** True while the patch still has to be applied: PENDING, or WAIVED with a waiver that has run out. */
    public boolean isOpenAt(Instant moment) {
        return status == FindingStatus.PENDING || (status == FindingStatus.WAIVED && !waivedUntil.isAfter(moment));
    }

    // --- Internal helpers ---

    private FindingAuditEntry record(String action, String executor, FindingStatus from, FindingStatus to, String detail, Instant now) {
        this.updatedAt = now;
        FindingAuditEntry entry = new FindingAuditEntry(now, action, executor, from, to, detail);
        this.auditTrail.add(entry);
        return entry;
    }

    private void requireOneOf(String operation, FindingStatus... allowed) {
        for (FindingStatus candidate : allowed) {
            if (this.status == candidate) {
                return;
            }
        }
        throw new InvalidPatchFindingStatusException(String.format("Illegal transition: cannot %s a PatchFinding that is [%s].", operation, this.status));
    }

    private static void requireExecutor(String executor) {
        if (executor == null || executor.isBlank()) {
            throw new IllegalArgumentException("Executor is mandatory for auditable PatchFinding mutations.");
        }
    }

    // --- Getters ---

    public UUID getId() { return id; }
    public UUID getOrganisationId() { return organisationId; }
    public UUID getPolicyId() { return policyId; }
    public String getPatchRef() { return patchRef; }
    public Severity getSeverity() { return severity; }
    public Instant getAnnouncedAt() { return announcedAt; }
    public FindingStatus getStatus() { return status; }
    public Instant getAppliedAt() { return appliedAt; }
    public WaiverReason getWaiverReason() { return waiverReason; }
    public Instant getWaivedUntil() { return waivedUntil; }
    public String getReportedBy() { return reportedBy; }
    public Instant getReportedAt() { return reportedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<FindingAuditEntry> getAuditTrail() { return Collections.unmodifiableList(auditTrail); }

    // --- Nested Value Objects ---

    public enum FindingStatus { PENDING, APPLIED, WAIVED }

    /** The fixed vocabulary of reasons to leave a patch unapplied: never staff's own words. */
    public enum WaiverReason { NOT_APPLICABLE, COMPENSATING_CONTROL, ACCEPTED_RISK, VENDOR_NO_FIX }

    public record FindingAuditEntry(Instant occurredAt, String action, String executor, FindingStatus fromStatus, FindingStatus toStatus, String detail) {}
}
