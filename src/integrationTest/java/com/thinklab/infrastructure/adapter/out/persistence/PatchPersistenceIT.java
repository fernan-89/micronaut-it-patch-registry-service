package com.thinklab.infrastructure.adapter.out.persistence;

import com.mongodb.reactivestreams.client.MongoClient;
import com.thinklab.domain.exception.DuplicatePatchPolicyException;
import com.thinklab.domain.exception.InvalidPatchFindingStatusException;
import com.thinklab.domain.exception.InvalidPatchPolicyStatusException;
import com.thinklab.domain.model.PatchFinding;
import com.thinklab.domain.model.PatchFinding.FindingStatus;
import com.thinklab.domain.model.PatchFinding.WaiverReason;
import com.thinklab.domain.model.PatchPolicy;
import com.thinklab.domain.model.PatchPolicy.PolicyStatus;
import com.thinklab.domain.model.Severity;
import com.thinklab.domain.repository.PatchFindingRepository;
import com.thinklab.domain.repository.PatchFindingRepository.Filter;
import com.thinklab.domain.repository.PatchPolicyRepository;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The patch registry persistence against a real MongoDB: the unique policy name, the unique finding key that makes a repeated or simultaneous
 * report the same finding, the status-guarded saves (the second writer from the same state loses), the unresolved findings a policy is judged on,
 * and the indexes created at startup.
 */
@MicronautTest(packages = "com.thinklab", transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PatchPersistenceIT implements TestPropertyProvider {

    private static final String DATABASE = "patch_registry_it";

    @Override
    public Map<String, String> getProperties() {
        return Map.of("mongodb.uri", MongoContainer.uri(DATABASE));
    }

    @Inject PatchPolicyRepository policies;
    @Inject PatchFindingRepository findings;
    @Inject MongoClient mongoClient;

    private PatchPolicy newPolicy(UUID organisation, String name) {
        return PatchPolicy.createNew(UUID.randomUUID(), organisation, name, UUID.randomUUID(), 2, 7, 30, 90, "op-1");
    }

    private PatchFinding newFinding(UUID organisation, UUID policy, String ref, Severity severity, Instant announced) {
        return PatchFinding.createNew(UUID.randomUUID(), organisation, policy, ref, severity, announced, "tool", Instant.now());
    }

    @Test
    @DisplayName("a policy is read back whole, only for its own organisation, oldest first; the name is unique per organisation")
    void policyRoundTrip() {
        UUID organisation = UUID.randomUUID();
        PatchPolicy first = policies.create(newPolicy(organisation, "First")).block();
        policies.create(newPolicy(organisation, "Second")).block();

        PatchPolicy found = policies.findById(first.getId(), organisation).block();

        assertEquals("First", found.getName());
        assertEquals(7, found.getHighDays());
        assertEquals(1, found.getAuditTrail().size());
        assertNull(policies.findById(first.getId(), UUID.randomUUID()).block());
        assertEquals(List.of("First", "Second"), policies.findAll(organisation).map(PatchPolicy::getName).collectList().block());
        assertThrows(DuplicatePatchPolicyException.class, () -> policies.create(newPolicy(organisation, "First")).block());
        assertEquals("First", policies.create(newPolicy(UUID.randomUUID(), "First")).block().getName());
    }

    @Test
    @DisplayName("a change is guarded by the status loaded: the second writer from the same state loses, and the audit entry is kept")
    void guardedPolicySave() {
        UUID organisation = UUID.randomUUID();
        PatchPolicy created = policies.create(newPolicy(organisation, "Guarded")).block();
        PatchPolicy writerOne = policies.findById(created.getId(), organisation).block();
        PatchPolicy writerTwo = policies.findById(created.getId(), organisation).block();

        var entry = writerOne.pause("op-1");
        policies.save(writerOne, PolicyStatus.ACTIVE, entry).block();
        var other = writerTwo.pause("op-2");

        assertThrows(InvalidPatchPolicyStatusException.class, () -> policies.save(writerTwo, PolicyStatus.ACTIVE, other).block());
        PatchPolicy stored = policies.findById(created.getId(), organisation).block();
        assertEquals(PolicyStatus.PAUSED, stored.getStatus());
        assertEquals(2, stored.getAuditTrail().size());
    }

    @Test
    @DisplayName("a rename to a name already in use is a duplicate")
    void renameDuplicate() {
        UUID organisation = UUID.randomUUID();
        policies.create(newPolicy(organisation, "Taken")).block();
        PatchPolicy other = policies.create(newPolicy(organisation, "Free")).block();
        var entry = other.update("Taken", other.getAssetId(), 2, 7, 30, 90, "op-1");

        assertThrows(DuplicatePatchPolicyException.class, () -> policies.save(other, PolicyStatus.ACTIVE, entry).block());
    }

    @Test
    @DisplayName("the same patch reported twenty times at once is stored once and the others get the stored one back")
    void sameFinding() {
        UUID organisation = UUID.randomUUID();
        UUID policy = policies.create(newPolicy(organisation, "Race")).block().getId();
        Instant announced = Instant.now().minus(Duration.ofDays(1));

        List<PatchFindingRepository.Stored> results = Flux.range(0, 20).flatMap(i -> findings.create(newFinding(organisation, policy, "KB5030211", Severity.HIGH, announced))).collectList().block();

        assertEquals(1, results.stream().filter(PatchFindingRepository.Stored::created).count());
        assertEquals(1, results.stream().map(stored -> stored.finding().getId()).distinct().count());
        assertEquals(1, findings.findAll(organisation, new Filter(policy, null, null), 50).count().block());
        assertNotNull(findings.findByNaturalKey(organisation, policy, "KB5030211").block());
        assertNull(findings.findByNaturalKey(organisation, policy, "KB1").block());
        assertTrue(findings.create(newFinding(organisation, policy, "KB1", Severity.LOW, announced)).block().created());
    }

    @Test
    @DisplayName("findings are listed newest announcement first, filtered, tenant-scoped and limited")
    void listing() {
        UUID organisation = UUID.randomUUID();
        UUID policy = policies.create(newPolicy(organisation, "Listing")).block().getId();
        Instant base = Instant.now().minus(Duration.ofDays(30));
        PatchFinding old = findings.create(newFinding(organisation, policy, "KB1", Severity.LOW, base)).block().finding();
        PatchFinding middle = findings.create(newFinding(organisation, policy, "KB2", Severity.HIGH, base.plus(Duration.ofDays(5)))).block().finding();
        PatchFinding recent = findings.create(newFinding(organisation, policy, "KB3", Severity.CRITICAL, base.plus(Duration.ofDays(10)))).block().finding();

        assertEquals(List.of(recent.getId(), middle.getId(), old.getId()), findings.findAll(organisation, new Filter(null, null, null), 50).map(PatchFinding::getId).collectList().block());
        assertEquals(List.of(middle.getId()), findings.findAll(organisation, new Filter(policy, FindingStatus.PENDING, Severity.HIGH), 50).map(PatchFinding::getId).collectList().block());
        assertEquals(2, findings.findAll(organisation, new Filter(null, null, null), 2).count().block());
        assertEquals(0, findings.findAll(UUID.randomUUID(), new Filter(null, null, null), 50).count().block());
        assertEquals(Severity.CRITICAL, findings.findById(recent.getId(), organisation).block().getSeverity());
        assertNull(findings.findById(recent.getId(), UUID.randomUUID()).block());
    }

    @Test
    @DisplayName("a finding changes only from the status loaded: the second writer loses; apply and waive are kept with their audit entries; applied ones are not unresolved")
    void guardedFindingSave() {
        UUID organisation = UUID.randomUUID();
        UUID policy = policies.create(newPolicy(organisation, "Findings")).block().getId();
        PatchFinding created = findings.create(newFinding(organisation, policy, "KB10", Severity.HIGH, Instant.now().minus(Duration.ofDays(1)))).block().finding();
        PatchFinding other = findings.create(newFinding(organisation, policy, "KB11", Severity.HIGH, Instant.now().minus(Duration.ofDays(1)))).block().finding();
        Instant now = Instant.now();

        PatchFinding writerOne = findings.findById(created.getId(), organisation).block();
        PatchFinding writerTwo = findings.findById(created.getId(), organisation).block();
        var waive = writerOne.waive(WaiverReason.COMPENSATING_CONTROL, now.plus(Duration.ofDays(5)), now, "staff");
        findings.save(writerOne, FindingStatus.PENDING, waive).block();
        var apply = writerTwo.apply(now, now, "tool");

        assertThrows(InvalidPatchFindingStatusException.class, () -> findings.save(writerTwo, FindingStatus.PENDING, apply).block());
        PatchFinding stored = findings.findById(created.getId(), organisation).block();
        assertEquals(FindingStatus.WAIVED, stored.getStatus());
        assertEquals(WaiverReason.COMPENSATING_CONTROL, stored.getWaiverReason());
        assertEquals(2, stored.getAuditTrail().size());

        var applied = stored.apply(now, now, "tool");
        findings.save(stored, FindingStatus.WAIVED, applied).block();
        PatchFinding after = findings.findById(created.getId(), organisation).block();
        assertEquals(FindingStatus.APPLIED, after.getStatus());
        assertNull(after.getWaiverReason());
        assertNull(after.getWaivedUntil());
        assertNotNull(after.getAppliedAt());
        assertEquals(3, after.getAuditTrail().size());

        assertEquals(List.of(other.getId()), findings.findUnresolved(organisation, policy).map(PatchFinding::getId).collectList().block());
        assertEquals(List.of(other.getId()), findings.findUnresolved(organisation, null).map(PatchFinding::getId).collectList().block());
        assertEquals(0, findings.findUnresolved(UUID.randomUUID(), null).count().block());
    }

    @Test
    @DisplayName("a policy judged over the stored findings: overdue until the patch is applied")
    void judgedOverStoredFindings() {
        UUID organisation = UUID.randomUUID();
        PatchPolicy policy = policies.create(newPolicy(organisation, "Judged")).block();
        PatchFinding late = findings.create(newFinding(organisation, policy.getId(), "KB20", Severity.CRITICAL, Instant.now().minus(Duration.ofDays(5)))).block().finding();

        var before = policy.assess(Instant.now(), findings.findUnresolved(organisation, policy.getId()).collectList().block());
        assertEquals(PatchPolicy.Compliance.OVERDUE, before.compliance());

        Instant now = Instant.now();
        findings.save(late, FindingStatus.PENDING, late.apply(now, now, "tool")).block();

        var after = policy.assess(Instant.now(), findings.findUnresolved(organisation, policy.getId()).collectList().block());
        assertEquals(PatchPolicy.Compliance.COMPLIANT, after.compliance());
    }

    @Test
    @DisplayName("startup created the unique policy name and the unique finding key")
    void indexesExist() {
        List<Document> policyIndexes = Flux.from(mongoClient.getDatabase(DATABASE).getCollection("patch_policies").listIndexes()).collectList().block();
        List<Document> findingIndexes = Flux.from(mongoClient.getDatabase(DATABASE).getCollection("patch_findings").listIndexes()).collectList().block();

        assertTrue(policyIndexes.stream().anyMatch(index -> "organisationId_1_name_1".equals(index.getString("name")) && Boolean.TRUE.equals(index.getBoolean("unique"))));
        assertTrue(findingIndexes.stream().anyMatch(index -> "organisationId_1_policyId_1_patchRef_1".equals(index.getString("name")) && Boolean.TRUE.equals(index.getBoolean("unique"))));
        assertTrue(findingIndexes.stream().anyMatch(index -> "organisationId_1_policyId_1_status_1".equals(index.getString("name"))));
    }
}
