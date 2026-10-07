package com.thinklab.domain.model;

import com.thinklab.domain.exception.InvalidPatchFindingStatusException;
import com.thinklab.domain.model.PatchFinding.FindingStatus;
import com.thinklab.domain.model.PatchFinding.WaiverReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatchFindingTest {

    private final UUID id = UUID.randomUUID();
    private final UUID org = UUID.randomUUID();
    private final UUID policy = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-07T12:00:00Z");
    private final Instant announced = Instant.parse("2026-10-01T00:00:00.123456Z");

    private PatchFinding finding() {
        return PatchFinding.createNew(id, org, policy, "KB5030211", Severity.HIGH, announced, "tool", now);
    }

    @Test
    @DisplayName("a reported finding is PENDING, the announcement is cut to the millisecond, and the trail starts with REPORTED")
    void createNew() {
        PatchFinding finding = finding();

        assertEquals(FindingStatus.PENDING, finding.getStatus());
        assertEquals(Instant.parse("2026-10-01T00:00:00.123Z"), finding.getAnnouncedAt());
        assertEquals(id, finding.getId());
        assertEquals(org, finding.getOrganisationId());
        assertEquals(policy, finding.getPolicyId());
        assertEquals("KB5030211", finding.getPatchRef());
        assertEquals(Severity.HIGH, finding.getSeverity());
        assertEquals("tool", finding.getReportedBy());
        assertEquals(now, finding.getReportedAt());
        assertEquals(now, finding.getUpdatedAt());
        assertNull(finding.getAppliedAt());
        assertNull(finding.getWaiverReason());
        assertNull(finding.getWaivedUntil());
        assertEquals("REPORTED", finding.getAuditTrail().get(0).action());
        assertThrows(UnsupportedOperationException.class, () -> finding.getAuditTrail().clear());
        assertTrue(finding.isOpenAt(now));
        assertFalse(finding.isWaivedAt(now));
    }

    @Test
    @DisplayName("creation refuses a reference that is not an identifier, missing parts, an announcement in the future and no executor")
    void createRefuses() {
        assertThrows(IllegalArgumentException.class, () -> PatchFinding.createNew(null, org, policy, "KB1", Severity.LOW, announced, "t", now));
        assertThrows(IllegalArgumentException.class, () -> PatchFinding.createNew(id, null, policy, "KB1", Severity.LOW, announced, "t", now));
        assertThrows(IllegalArgumentException.class, () -> PatchFinding.createNew(id, org, null, "KB1", Severity.LOW, announced, "t", now));
        assertThrows(IllegalArgumentException.class, () -> PatchFinding.createNew(id, org, policy, null, Severity.LOW, announced, "t", now));
        assertThrows(IllegalArgumentException.class, () -> PatchFinding.createNew(id, org, policy, "a".repeat(65), Severity.LOW, announced, "t", now));
        assertThrows(IllegalArgumentException.class, () -> PatchFinding.createNew(id, org, policy, "disk /dev/sda1 is full", Severity.LOW, announced, "t", now));
        assertThrows(IllegalArgumentException.class, () -> PatchFinding.createNew(id, org, policy, "-leading", Severity.LOW, announced, "t", now));
        assertThrows(IllegalArgumentException.class, () -> PatchFinding.createNew(id, org, policy, "KB1", null, announced, "t", now));
        assertThrows(IllegalArgumentException.class, () -> PatchFinding.createNew(id, org, policy, "KB1", Severity.LOW, null, "t", now));
        assertThrows(IllegalArgumentException.class, () -> PatchFinding.createNew(id, org, policy, "KB1", Severity.LOW, now.plus(PatchFinding.CLOCK_SKEW).plusSeconds(1), "t", now));
        assertThrows(IllegalArgumentException.class, () -> PatchFinding.createNew(id, org, policy, "KB1", Severity.LOW, announced, null, now));
        assertThrows(IllegalArgumentException.class, () -> PatchFinding.createNew(id, org, policy, "KB1", Severity.LOW, announced, " ", now));
        assertEquals("CVE-2026-1234", PatchFinding.createNew(id, org, policy, "CVE-2026-1234", Severity.LOW, now.plus(PatchFinding.CLOCK_SKEW), "t", now).getPatchRef());
        assertEquals("openssl-3.0.13_1+deb12u1", PatchFinding.createNew(id, org, policy, "openssl-3.0.13_1+deb12u1", Severity.LOW, announced, "t", now).getPatchRef());
        assertEquals(64, PatchFinding.createNew(id, org, policy, "a".repeat(64), Severity.LOW, announced, "t", now).getPatchRef().length());
    }

    @Test
    @DisplayName("apply closes a pending or a waived finding for good, clears the waiver, and refuses a time before the announcement or in the future")
    void apply() {
        PatchFinding pending = finding();
        Instant when = now.minus(Duration.ofDays(1));

        var entry = pending.apply(when, now, "tool");

        assertEquals("APPLIED", entry.action());
        assertEquals(FindingStatus.PENDING, entry.fromStatus());
        assertEquals(FindingStatus.APPLIED, pending.getStatus());
        assertEquals(when, pending.getAppliedAt());
        assertFalse(pending.isOpenAt(now));
        assertFalse(pending.isWaivedAt(now));
        assertThrows(InvalidPatchFindingStatusException.class, () -> pending.apply(when, now, "tool"));
        assertThrows(InvalidPatchFindingStatusException.class, () -> pending.waive(WaiverReason.ACCEPTED_RISK, now.plusSeconds(60), now, "staff"));
        assertThrows(InvalidPatchFindingStatusException.class, () -> pending.unwaive(now, "staff"));

        PatchFinding waived = finding();
        waived.waive(WaiverReason.VENDOR_NO_FIX, now.plus(Duration.ofDays(5)), now, "staff");
        waived.apply(now, now, "tool");
        assertEquals(FindingStatus.APPLIED, waived.getStatus());
        assertNull(waived.getWaiverReason());
        assertNull(waived.getWaivedUntil());

        PatchFinding other = finding();
        assertThrows(IllegalArgumentException.class, () -> other.apply(null, now, "tool"));
        assertThrows(IllegalArgumentException.class, () -> other.apply(announced.minusSeconds(1), now, "tool"));
        assertThrows(IllegalArgumentException.class, () -> other.apply(now.plus(PatchFinding.CLOCK_SKEW).plusSeconds(1), now, "tool"));
        assertThrows(IllegalArgumentException.class, () -> other.apply(when, now, null));
        assertEquals(FindingStatus.PENDING, other.getStatus());
        other.apply(now.plus(PatchFinding.CLOCK_SKEW), now, "tool");
        assertEquals(FindingStatus.APPLIED, other.getStatus());
    }

    @Test
    @DisplayName("waive needs a reason and an end in the future, at most a year ahead; it keeps the patch out of the count only while it lasts")
    void waive() {
        PatchFinding finding = finding();
        Instant until = now.plus(Duration.ofDays(30));

        var entry = finding.waive(WaiverReason.COMPENSATING_CONTROL, until, now, "staff");

        assertEquals("WAIVED", entry.action());
        assertEquals(FindingStatus.WAIVED, finding.getStatus());
        assertEquals(WaiverReason.COMPENSATING_CONTROL, finding.getWaiverReason());
        assertEquals(until, finding.getWaivedUntil());
        assertTrue(finding.isWaivedAt(now));
        assertFalse(finding.isOpenAt(now));
        assertFalse(finding.isWaivedAt(until));
        assertTrue(finding.isOpenAt(until));
        assertThrows(InvalidPatchFindingStatusException.class, () -> finding.waive(WaiverReason.ACCEPTED_RISK, until, now, "staff"));

        PatchFinding other = finding();
        assertThrows(IllegalArgumentException.class, () -> other.waive(null, until, now, "staff"));
        assertThrows(IllegalArgumentException.class, () -> other.waive(WaiverReason.NOT_APPLICABLE, null, now, "staff"));
        assertThrows(IllegalArgumentException.class, () -> other.waive(WaiverReason.NOT_APPLICABLE, now, now, "staff"));
        assertThrows(IllegalArgumentException.class, () -> other.waive(WaiverReason.NOT_APPLICABLE, now.plus(Duration.ofDays(366)), now, "staff"));
        assertThrows(IllegalArgumentException.class, () -> other.waive(WaiverReason.NOT_APPLICABLE, until, now, null));
        other.waive(WaiverReason.NOT_APPLICABLE, now.plus(Duration.ofDays(365)), now, "staff");
        assertEquals(FindingStatus.WAIVED, other.getStatus());
    }

    @Test
    @DisplayName("unwaive makes a waived finding pending at once")
    void unwaive() {
        PatchFinding finding = finding();
        assertThrows(InvalidPatchFindingStatusException.class, () -> finding.unwaive(now, "staff"));
        finding.waive(WaiverReason.ACCEPTED_RISK, now.plus(Duration.ofDays(3)), now, "staff");
        assertThrows(IllegalArgumentException.class, () -> finding.unwaive(now, null));

        var entry = finding.unwaive(now, "staff");

        assertEquals("UNWAIVED", entry.action());
        assertEquals(FindingStatus.PENDING, finding.getStatus());
        assertNull(finding.getWaiverReason());
        assertNull(finding.getWaivedUntil());
        assertTrue(finding.isOpenAt(now));
        assertEquals(3, finding.getAuditTrail().size());
    }

    @Test
    @DisplayName("reconstitute needs the identity and the facts of the finding and trusts the rest")
    void reconstitute() {
        Instant at = Instant.parse("2026-10-02T00:00:00Z");
        assertThrows(IllegalArgumentException.class, () -> PatchFinding.reconstitute(null, org, policy, "KB1", Severity.LOW, announced, FindingStatus.PENDING, null, null, null, "t", at, at, null));
        assertThrows(IllegalArgumentException.class, () -> PatchFinding.reconstitute(id, null, policy, "KB1", Severity.LOW, announced, FindingStatus.PENDING, null, null, null, "t", at, at, null));
        assertThrows(IllegalArgumentException.class, () -> PatchFinding.reconstitute(id, org, null, "KB1", Severity.LOW, announced, FindingStatus.PENDING, null, null, null, "t", at, at, null));
        assertThrows(IllegalArgumentException.class, () -> PatchFinding.reconstitute(id, org, policy, null, Severity.LOW, announced, FindingStatus.PENDING, null, null, null, "t", at, at, null));
        assertThrows(IllegalArgumentException.class, () -> PatchFinding.reconstitute(id, org, policy, "KB1", null, announced, FindingStatus.PENDING, null, null, null, "t", at, at, null));
        assertThrows(IllegalArgumentException.class, () -> PatchFinding.reconstitute(id, org, policy, "KB1", Severity.LOW, null, FindingStatus.PENDING, null, null, null, "t", at, at, null));
        assertThrows(IllegalArgumentException.class, () -> PatchFinding.reconstitute(id, org, policy, "KB1", Severity.LOW, announced, null, null, null, null, "t", at, at, null));

        PatchFinding defaults = PatchFinding.reconstitute(id, org, policy, "KB1", Severity.LOW, announced, FindingStatus.APPLIED, at, null, null, "t", at, null, null);
        assertEquals(at, defaults.getUpdatedAt());
        assertTrue(defaults.getAuditTrail().isEmpty());
        assertEquals(at, defaults.getAppliedAt());

        PatchFinding full = PatchFinding.reconstitute(id, org, policy, "KB1", Severity.LOW, announced, FindingStatus.WAIVED, null, WaiverReason.ACCEPTED_RISK, at.plusSeconds(60), "t", at,
                at.plusSeconds(1), List.of(new PatchFinding.FindingAuditEntry(at, "REPORTED", "t", null, FindingStatus.PENDING, null)));
        assertEquals(at.plusSeconds(1), full.getUpdatedAt());
        assertEquals(1, full.getAuditTrail().size());
        assertEquals(WaiverReason.ACCEPTED_RISK, full.getWaiverReason());
    }
}
