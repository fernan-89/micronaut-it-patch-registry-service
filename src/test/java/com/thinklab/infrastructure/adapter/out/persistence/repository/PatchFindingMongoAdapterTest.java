package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.MongoWriteException;
import com.mongodb.ServerAddress;
import com.mongodb.WriteError;
import com.mongodb.client.result.InsertOneResult;
import com.mongodb.client.result.UpdateResult;
import com.mongodb.reactivestreams.client.FindPublisher;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import com.thinklab.domain.exception.InvalidPatchFindingStatusException;
import com.thinklab.domain.model.PatchFinding;
import com.thinklab.domain.model.PatchFinding.FindingStatus;
import com.thinklab.domain.model.PatchFinding.WaiverReason;
import com.thinklab.domain.model.Severity;
import com.thinklab.domain.repository.PatchFindingRepository.Filter;
import com.thinklab.infrastructure.adapter.out.persistence.entity.PatchFindingDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.PatchFindingDocument.PatchFindingPersistenceMapper;
import org.bson.BsonDocument;
import org.bson.BsonObjectId;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class PatchFindingMongoAdapterTest {

    private static final String URI = "mongodb://localhost:27017/pch_test";

    private final UUID tenant = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-07T12:00:00Z");
    private MongoClient client;
    private MongoCollection<PatchFindingDocument> collection;

    @BeforeEach
    void setUp() {
        client = mock(MongoClient.class);
        MongoDatabase database = mock(MongoDatabase.class);
        collection = mock(MongoCollection.class);
        when(client.getDatabase("pch_test")).thenReturn(database);
        when(client.getDatabase("thinklab_it_patch_registry_db")).thenReturn(database);
        when(database.getCollection("patch_findings", PatchFindingDocument.class)).thenReturn(collection);
        when(collection.withCodecRegistry(any())).thenReturn(collection);
    }

    private PatchFinding finding() {
        return PatchFinding.createNew(UUID.randomUUID(), tenant, UUID.randomUUID(), "KB5030211", Severity.HIGH, now.minus(Duration.ofDays(2)), "tool", now);
    }

    private static MongoWriteException writeError(int code) {
        return new MongoWriteException(new WriteError(code, "write error", new BsonDocument()), new ServerAddress());
    }

    private FindPublisher<PatchFindingDocument> finds(PatchFinding found) {
        FindPublisher<PatchFindingDocument> publisher = mock(FindPublisher.class);
        when(collection.find(any(Bson.class))).thenReturn(publisher);
        when(publisher.first()).thenReturn(publisher);
        when(publisher.sort(any(Bson.class))).thenReturn(publisher);
        when(publisher.limit(anyInt())).thenReturn(publisher);
        doAnswer(invocation -> {
            org.reactivestreams.Subscriber<PatchFindingDocument> subscriber = invocation.getArgument(0);
            Flux.just(PatchFindingPersistenceMapper.toDocument(found)).subscribe(subscriber);
            return null;
        }).when(publisher).subscribe(any());
        return publisher;
    }

    @Test
    @DisplayName("a finding survives the round trip, pending or waived")
    void roundTrip() {
        PatchFinding pending = finding();
        PatchFindingDocument document = PatchFindingPersistenceMapper.toDocument(pending);
        PatchFinding back = PatchFindingPersistenceMapper.toDomain(document);

        assertEquals(FindingStatus.PENDING, back.getStatus());
        assertNull(back.getWaiverReason());
        assertEquals("KB5030211", document.getPatchRef());
        assertEquals("HIGH", document.getSeverity());
        assertEquals("PENDING", document.getStatus());
        assertEquals(tenant, document.getOrganisationId());
        assertEquals(pending.getPolicyId(), document.getPolicyId());
        assertEquals(pending.getId(), document.getId());
        assertEquals(pending.getAnnouncedAt(), document.getAnnouncedAt());
        assertEquals("tool", document.getReportedBy());
        assertEquals(now, document.getReportedAt());
        assertEquals(now, document.getUpdatedAt());
        assertNull(document.getAppliedAt());
        assertNull(document.getWaiverReason());
        assertNull(document.getWaivedUntil());
        assertEquals(1, document.getAuditTrail().size());
        assertNull(back.getAuditTrail().get(0).fromStatus());

        PatchFinding waived = finding();
        waived.waive(WaiverReason.ACCEPTED_RISK, now.plus(Duration.ofDays(3)), now, "staff");
        PatchFindingDocument waivedDocument = PatchFindingPersistenceMapper.toDocument(waived);
        PatchFinding waivedBack = PatchFindingPersistenceMapper.toDomain(waivedDocument);
        assertEquals(WaiverReason.ACCEPTED_RISK, waivedBack.getWaiverReason());
        assertEquals("ACCEPTED_RISK", waivedDocument.getWaiverReason());
        assertEquals(now.plus(Duration.ofDays(3)), waivedBack.getWaivedUntil());
        assertEquals(FindingStatus.PENDING, waivedBack.getAuditTrail().get(1).fromStatus());
    }

    @Test
    @DisplayName("the document setters are plain, and the persistence mapper is a utility class")
    void plainDocument() throws ReflectiveOperationException {
        PatchFindingDocument document = new PatchFindingDocument();
        document.setAuditTrail(new ArrayList<>());
        document.setAppliedAt(now);
        assertTrue(document.getAuditTrail().isEmpty());
        assertEquals(now, document.getAppliedAt());

        Constructor<PatchFindingPersistenceMapper> constructor = PatchFindingPersistenceMapper.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        assertThrows(InvocationTargetException.class, constructor::newInstance);
    }

    @Test
    @DisplayName("create answers the finding as created; the lookups are scoped to the organisation")
    void lookups() {
        PatchFinding finding = finding();
        when(collection.insertOne(any(PatchFindingDocument.class))).thenReturn(Mono.just(InsertOneResult.acknowledged(new BsonObjectId(new ObjectId()))));
        finds(finding);
        PatchFindingMongoRepositoryAdapter adapter = new PatchFindingMongoRepositoryAdapter(client, "mongodb://localhost:27017");

        StepVerifier.create(adapter.create(finding)).assertNext(stored -> assertTrue(stored.created())).verifyComplete();
        StepVerifier.create(adapter.findById(finding.getId(), tenant)).assertNext(found -> assertEquals(finding.getId(), found.getId())).verifyComplete();
        StepVerifier.create(adapter.findByNaturalKey(tenant, finding.getPolicyId(), "KB5030211")).expectNextCount(1).verifyComplete();
        assertThrows(NullPointerException.class, () -> new PatchFindingMongoRepositoryAdapter(client, null));
    }

    @Test
    @DisplayName("a duplicate key means the finding was already reported: the stored one comes back, not created; any other error passes through")
    void duplicate() {
        PatchFinding finding = finding();
        when(collection.insertOne(any(PatchFindingDocument.class))).thenReturn(Mono.error(writeError(11000))).thenReturn(Mono.error(writeError(1)));
        finds(finding);
        PatchFindingMongoRepositoryAdapter adapter = new PatchFindingMongoRepositoryAdapter(client, URI);

        StepVerifier.create(adapter.create(finding)).assertNext(stored -> {
            assertFalse(stored.created());
            assertEquals(finding.getId(), stored.finding().getId());
        }).verifyComplete();
        StepVerifier.create(adapter.create(finding)).expectError(MongoWriteException.class).verify();
    }

    @Test
    @DisplayName("the list is filtered only by what is given, newest announcement first, and limited")
    void list() {
        PatchFinding finding = finding();
        FindPublisher<PatchFindingDocument> publisher = finds(finding);
        PatchFindingMongoRepositoryAdapter adapter = new PatchFindingMongoRepositoryAdapter(client, URI);

        StepVerifier.create(adapter.findAll(tenant, new Filter(null, null, null), 5)).expectNextCount(1).verifyComplete();
        StepVerifier.create(adapter.findAll(tenant, new Filter(finding.getPolicyId(), FindingStatus.WAIVED, Severity.CRITICAL), 7)).expectNextCount(1).verifyComplete();

        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);
        verify(collection, times(2)).find(filter.capture());
        String bare = filter.getAllValues().get(0).toString();
        String full = filter.getAllValues().get(1).toString();
        assertTrue(bare.contains("organisationId") && !bare.contains("policyId") && !bare.contains("severity"));
        assertTrue(full.contains("policyId") && full.contains("WAIVED") && full.contains("CRITICAL"));
        verify(publisher).limit(5);
        verify(publisher).limit(7);
    }

    @Test
    @DisplayName("the findings changed since a moment are the tenant's, of any status, updated at or after it")
    void changedSince() {
        finds(finding());
        PatchFindingMongoRepositoryAdapter adapter = new PatchFindingMongoRepositoryAdapter(client, URI);

        StepVerifier.create(adapter.findChangedSince(tenant, Instant.parse("2026-10-01T00:00:00Z"))).expectNextCount(1).verifyComplete();

        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);
        verify(collection).find(filter.capture());
        String text = filter.getValue().toString();
        assertTrue(text.contains("organisationId") && text.contains("updatedAt") && text.contains("$gte") && !text.contains("status"));
    }

    @Test
    @DisplayName("the unresolved findings are everything not applied, for the tenant or for one policy")
    void unresolved() {
        PatchFinding finding = finding();
        finds(finding);
        PatchFindingMongoRepositoryAdapter adapter = new PatchFindingMongoRepositoryAdapter(client, URI);

        StepVerifier.create(adapter.findUnresolved(tenant, null)).expectNextCount(1).verifyComplete();
        StepVerifier.create(adapter.findUnresolved(tenant, finding.getPolicyId())).expectNextCount(1).verifyComplete();

        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);
        verify(collection, times(2)).find(filter.capture());
        String all = filter.getAllValues().get(0).toString();
        String one = filter.getAllValues().get(1).toString();
        assertTrue(all.contains("APPLIED") && !all.contains("policyId"));
        assertTrue(one.contains("APPLIED") && one.contains("policyId"));
    }

    @Test
    @DisplayName("save is guarded by the status loaded and pushes the audit entry; a lost race is a conflict")
    void save() {
        PatchFinding finding = finding();
        finding.waive(WaiverReason.NOT_APPLICABLE, now.plus(Duration.ofDays(3)), now, "staff");
        when(collection.updateOne(any(Bson.class), any(Bson.class)))
                .thenReturn(Mono.just(UpdateResult.acknowledged(1, 1L, null)))
                .thenReturn(Mono.just(UpdateResult.acknowledged(0, 0L, null)));
        PatchFindingMongoRepositoryAdapter adapter = new PatchFindingMongoRepositoryAdapter(client, URI);

        StepVerifier.create(adapter.save(finding, FindingStatus.PENDING, finding.getAuditTrail().get(1))).verifyComplete();
        StepVerifier.create(adapter.save(finding, FindingStatus.PENDING, finding.getAuditTrail().get(1))).expectError(InvalidPatchFindingStatusException.class).verify();

        PatchFinding applied = finding();
        applied.apply(now, now, "tool");
        when(collection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(Mono.just(UpdateResult.acknowledged(1, 1L, null)));
        StepVerifier.create(adapter.save(applied, FindingStatus.PENDING, applied.getAuditTrail().get(1))).verifyComplete();

        ArgumentCaptor<Bson> guard = ArgumentCaptor.forClass(Bson.class);
        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);
        verify(collection, times(3)).updateOne(guard.capture(), update.capture());
        assertTrue(guard.getAllValues().get(0).toString().contains("PENDING") && guard.getAllValues().get(0).toString().contains("organisationId"));
        assertTrue(update.getAllValues().get(0).toString().contains("auditTrail") && update.getAllValues().get(0).toString().contains("WAIVED"));
    }
}
