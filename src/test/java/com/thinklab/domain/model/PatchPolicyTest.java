package com.thinklab.domain.model;

import com.thinklab.domain.exception.InvalidPatchPolicyStatusException;
import com.thinklab.domain.model.PatchPolicy.Assessment;
import com.thinklab.domain.model.PatchPolicy.Compliance;
import com.thinklab.domain.model.PatchPolicy.PolicyStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatchPolicyTest {

    private final UUID org = UUID.randomUUID();
    private final UUID asset = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-07T12:00:00Z");

    private PatchPolicy policy() {
        return PatchPolicy.createNew(UUID.randomUUID(), org, "Servers", asset, 2, 7, 30, 90, "op");
    }

    private PatchFinding finding(PatchPolicy policy, Severity severity, Instant announced) {
        return PatchFinding.createNew(UUID.randomUUID(), org, policy.getId(), "KB" + UUID.randomUUID().toString().substring(0, 8), severity, announced, "tool", now);
    }

    @Test
    @DisplayName("the promise is broken (PATCH_OVERDUE) when an open finding is past its deadline, kept otherwise, and not judged when paused (platform ADR-001)")
    void promise() {
        PatchPolicy active = policy();
        PatchFinding overdue = finding(active, Severity.CRITICAL, now.minus(Duration.ofDays(3)));
        PatchFinding inside = finding(active, Severity.LOW, now.minus(Duration.ofDays(3)));

        assertEquals(Promise.broken("PATCH_OVERDUE"), active.promise(now, List.of(overdue, inside)));
        assertEquals(Promise.kept(), active.promise(now, List.of(inside)));
        assertEquals(Promise.kept(), active.promise(now, List.of()));
        active.pause("op");
        assertEquals(Promise.notJudged(), active.promise(now, List.of(overdue)));
        assertEquals(Promise.Verdict.BROKEN, Promise.broken("X").verdict());
        assertEquals("X", Promise.broken("X").code());
    }

    @Test
    @DisplayName("a new policy is ACTIVE with one INITIATED entry; the trail cannot be changed from outside")
    void createNew() {
        PatchPolicy policy = policy();

        assertEquals(PolicyStatus.ACTIVE, policy.getStatus());
        assertEquals("Servers", policy.getName());
        assertEquals(asset, policy.getAssetId());
        assertEquals(org, policy.getOrganisationId());
        assertEquals(2, policy.getCriticalDays());
        assertEquals(7, policy.getHighDays());
        assertEquals(30, policy.getMediumDays());
        assertEquals(90, policy.getLowDays());
        assertEquals(policy.getCreatedAt(), policy.getUpdatedAt());
        assertEquals(1, policy.getAuditTrail().size());
        assertEquals("INITIATED", policy.getAuditTrail().get(0).action());
        assertNull(policy.getAuditTrail().get(0).fromStatus());
        assertThrows(UnsupportedOperationException.class, () -> policy.getAuditTrail().clear());
    }

    @Test
    @DisplayName("creation refuses what makes no sense, and says why")
    void createRefuses() {
        UUID id = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> PatchPolicy.createNew(null, org, "N", asset, 1, 2, 3, 4, "op"));
        assertThrows(IllegalArgumentException.class, () -> PatchPolicy.createNew(id, null, "N", asset, 1, 2, 3, 4, "op"));
        assertThrows(IllegalArgumentException.class, () -> PatchPolicy.createNew(id, org, null, asset, 1, 2, 3, 4, "op"));
        assertThrows(IllegalArgumentException.class, () -> PatchPolicy.createNew(id, org, " ", asset, 1, 2, 3, 4, "op"));
        assertThrows(IllegalArgumentException.class, () -> PatchPolicy.createNew(id, org, "x".repeat(81), asset, 1, 2, 3, 4, "op"));
        assertThrows(IllegalArgumentException.class, () -> PatchPolicy.createNew(id, org, "N", null, 1, 2, 3, 4, "op"));
        assertThrows(IllegalArgumentException.class, () -> PatchPolicy.createNew(id, org, "N", asset, 0, 2, 3, 4, "op"));
        assertThrows(IllegalArgumentException.class, () -> PatchPolicy.createNew(id, org, "N", asset, 1, 2, 3, 731, "op"));
        IllegalArgumentException order = assertThrows(IllegalArgumentException.class, () -> PatchPolicy.createNew(id, org, "N", asset, 5, 2, 3, 4, "op"));
        assertTrue(order.getMessage().contains("cannot be given more time"));
        assertThrows(IllegalArgumentException.class, () -> PatchPolicy.createNew(id, org, "N", asset, 1, 5, 3, 4, "op"));
        assertThrows(IllegalArgumentException.class, () -> PatchPolicy.createNew(id, org, "N", asset, 1, 2, 5, 4, "op"));
        assertThrows(IllegalArgumentException.class, () -> PatchPolicy.createNew(id, org, "N", asset, 1, 2, 3, 4, null));
        assertThrows(IllegalArgumentException.class, () -> PatchPolicy.createNew(id, org, "N", asset, 1, 2, 3, 4, " "));
        assertEquals(730, PatchPolicy.createNew(id, org, "x".repeat(80), asset, 730, 730, 730, 730, "op").getLowDays());
        assertEquals(1, PatchPolicy.createNew(id, org, "N", asset, 1, 1, 1, 1, "op").getCriticalDays());
    }

    @Test
    @DisplayName("update replaces the whole definition in any status")
    void update() {
        PatchPolicy policy = policy();
        UUID other = UUID.randomUUID();

        var entry = policy.update("Renamed", other, 1, 3, 10, 60, "op2");

        assertEquals("UPDATED", entry.action());
        assertEquals(PolicyStatus.ACTIVE, entry.fromStatus());
        assertEquals("Renamed", policy.getName());
        assertEquals(other, policy.getAssetId());
        assertEquals(1, policy.getCriticalDays());
        assertEquals(60, policy.getLowDays());
        assertThrows(IllegalArgumentException.class, () -> policy.update(" ", other, 1, 3, 10, 60, "op"));
        assertThrows(IllegalArgumentException.class, () -> policy.update("N", other, 1, 3, 10, 60, null));
        policy.pause("op");
        assertEquals("UPDATED", policy.update("Again", other, 1, 3, 10, 60, "op").action());
        assertEquals(PolicyStatus.PAUSED, policy.getStatus());
    }

    @Test
    @DisplayName("pause and resume are the two legal transitions, anything else is refused")
    void pauseResume() {
        PatchPolicy policy = policy();

        assertThrows(InvalidPatchPolicyStatusException.class, () -> policy.resume("op"));
        assertEquals("PAUSED", policy.pause("op").action());
        assertThrows(InvalidPatchPolicyStatusException.class, () -> policy.pause("op"));
        assertThrows(IllegalArgumentException.class, () -> policy.resume(null));
        assertEquals("RESUMED", policy.resume("op").action());
        assertEquals(PolicyStatus.ACTIVE, policy.getStatus());
        assertThrows(IllegalArgumentException.class, () -> policy.pause(" "));
    }

    @Test
    @DisplayName("each severity has its own number of days, and a finding is due that many days after it was announced")
    void deadlines() {
        PatchPolicy policy = policy();
        Instant announced = Instant.parse("2026-10-01T00:00:00Z");

        assertEquals(2, policy.daysFor(Severity.CRITICAL));
        assertEquals(7, policy.daysFor(Severity.HIGH));
        assertEquals(30, policy.daysFor(Severity.MEDIUM));
        assertEquals(90, policy.daysFor(Severity.LOW));
        assertEquals(announced.plus(Duration.ofDays(7)), policy.dueAt(finding(policy, Severity.HIGH, announced)));
    }

    @Test
    @DisplayName("a policy with nothing open is COMPLIANT; open and within time is COMPLIANT; open past the deadline is OVERDUE with the oldest deadline")
    void assess() {
        PatchPolicy policy = policy();
        PatchFinding fresh = finding(policy, Severity.MEDIUM, now.minus(Duration.ofDays(1)));
        PatchFinding late = finding(policy, Severity.CRITICAL, now.minus(Duration.ofDays(5)));
        PatchFinding later = finding(policy, Severity.HIGH, now.minus(Duration.ofDays(20)));

        Assessment empty = policy.assess(now, List.of());
        assertEquals(Compliance.COMPLIANT, empty.compliance());
        assertEquals(0, empty.openFindings());
        assertNull(empty.oldestOverdueDueAt());

        Assessment within = policy.assess(now, List.of(fresh));
        assertEquals(Compliance.COMPLIANT, within.compliance());
        assertEquals(1, within.openFindings());
        assertEquals(0, within.overdueFindings());

        Assessment breached = policy.assess(now, List.of(fresh, late, later));
        assertEquals(Compliance.OVERDUE, breached.compliance());
        assertEquals(3, breached.openFindings());
        assertEquals(2, breached.overdueFindings());
        assertEquals(policy.dueAt(later), breached.oldestOverdueDueAt());
        assertEquals(Compliance.OVERDUE, policy.assess(now, List.of(later, late)).compliance());
        assertEquals(policy.dueAt(later), policy.assess(now, List.of(later, late)).oldestOverdueDueAt());
    }

    @Test
    @DisplayName("a waiver in force does not count; one that ran out counts again; an applied finding never counts; a paused policy is not judged")
    void waiversAppliedAndPaused() {
        PatchPolicy policy = policy();
        PatchFinding waivedInForce = finding(policy, Severity.CRITICAL, now.minus(Duration.ofDays(30)));
        waivedInForce.waive(PatchFinding.WaiverReason.ACCEPTED_RISK, now.plus(Duration.ofDays(10)), now, "staff");
        PatchFinding applied = finding(policy, Severity.CRITICAL, now.minus(Duration.ofDays(30)));
        applied.apply(now.minus(Duration.ofDays(3)), now, "tool");

        Assessment judged = policy.assess(now, List.of(waivedInForce, applied));
        assertEquals(Compliance.COMPLIANT, judged.compliance());
        assertEquals(0, judged.openFindings());
        assertEquals(1, judged.waivedFindings());

        Instant afterWaiver = now.plus(Duration.ofDays(11));
        Assessment expired = policy.assess(afterWaiver, List.of(waivedInForce, applied));
        assertEquals(Compliance.OVERDUE, expired.compliance());
        assertEquals(1, expired.overdueFindings());
        assertEquals(0, expired.waivedFindings());

        policy.pause("op");
        Assessment paused = policy.assess(afterWaiver, List.of(waivedInForce));
        assertEquals(Compliance.PAUSED, paused.compliance());
        assertEquals(1, paused.openFindings());
        assertEquals(0, paused.overdueFindings());
        assertNull(paused.oldestOverdueDueAt());
    }

    @Test
    @DisplayName("reconstitute needs the identity, defaults what is missing, and copies the audit trail")
    void reconstitute() {
        UUID id = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> PatchPolicy.reconstitute(null, org, "N", asset, 1, 2, 3, 4, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> PatchPolicy.reconstitute(id, null, "N", asset, 1, 2, 3, 4, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> PatchPolicy.reconstitute(id, org, null, asset, 1, 2, 3, 4, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> PatchPolicy.reconstitute(id, org, "N", null, 1, 2, 3, 4, null, null, null, null));

        PatchPolicy defaults = PatchPolicy.reconstitute(id, org, "N", asset, 1, 2, 3, 4, null, null, null, null);
        assertEquals(PolicyStatus.ACTIVE, defaults.getStatus());
        assertEquals(defaults.getCreatedAt(), defaults.getUpdatedAt());
        assertTrue(defaults.getAuditTrail().isEmpty());

        Instant created = Instant.parse("2026-10-01T00:00:00Z");
        Instant updated = created.plusSeconds(60);
        PatchPolicy full = PatchPolicy.reconstitute(id, org, "N", asset, 1, 2, 3, 4, PolicyStatus.PAUSED, created, updated,
                List.of(new PatchPolicy.PolicyAuditEntry(created, "INITIATED", "op", null, PolicyStatus.ACTIVE, null)));
        assertEquals(PolicyStatus.PAUSED, full.getStatus());
        assertEquals(created, full.getCreatedAt());
        assertEquals(updated, full.getUpdatedAt());
        assertEquals(1, full.getAuditTrail().size());
        assertEquals(id, full.getId());
    }
}
