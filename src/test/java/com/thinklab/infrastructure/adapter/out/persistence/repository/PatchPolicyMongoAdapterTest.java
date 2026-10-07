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
import com.thinklab.domain.exception.DuplicatePatchPolicyException;
import com.thinklab.domain.exception.InvalidPatchPolicyStatusException;
import com.thinklab.domain.model.PatchPolicy;
import com.thinklab.domain.model.PatchPolicy.PolicyStatus;
import com.thinklab.infrastructure.adapter.out.persistence.entity.PatchPolicyDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.PatchPolicyDocument.PatchPolicyPersistenceMapper;
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
import java.util.ArrayList;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class PatchPolicyMongoAdapterTest {

    private static final String URI = "mongodb://localhost:27017/pch_test";

    private final UUID tenant = UUID.randomUUID();
    private MongoClient client;
    private MongoCollection<PatchPolicyDocument> collection;

    @BeforeEach
    void setUp() {
        client = mock(MongoClient.class);
        MongoDatabase database = mock(MongoDatabase.class);
        collection = mock(MongoCollection.class);
        when(client.getDatabase("pch_test")).thenReturn(database);
        when(client.getDatabase("thinklab_it_patch_registry_db")).thenReturn(database);
        when(database.getCollection("patch_policies", PatchPolicyDocument.class)).thenReturn(collection);
        when(collection.withCodecRegistry(any())).thenReturn(collection);
    }

    private PatchPolicy policy() {
        PatchPolicy policy = PatchPolicy.createNew(UUID.randomUUID(), tenant, "Servers", UUID.randomUUID(), 2, 7, 30, 90, "op");
        policy.pause("op");
        return policy;
    }

    private static MongoWriteException writeError(int code) {
        return new MongoWriteException(new WriteError(code, "write error", new BsonDocument()), new ServerAddress());
    }

    private void finds(PatchPolicy found) {
        FindPublisher<PatchPolicyDocument> publisher = mock(FindPublisher.class);
        when(collection.find(any(Bson.class))).thenReturn(publisher);
        when(publisher.first()).thenReturn(publisher);
        when(publisher.sort(any(Bson.class))).thenReturn(publisher);
        doAnswer(invocation -> {
            org.reactivestreams.Subscriber<PatchPolicyDocument> subscriber = invocation.getArgument(0);
            Flux.just(PatchPolicyPersistenceMapper.toDocument(found)).subscribe(subscriber);
            return null;
        }).when(publisher).subscribe(any());
    }

    @Test
    @DisplayName("a policy survives the round trip, with its days and its audit trail")
    void roundTrip() {
        PatchPolicy policy = policy();

        PatchPolicyDocument document = PatchPolicyPersistenceMapper.toDocument(policy);
        PatchPolicy back = PatchPolicyPersistenceMapper.toDomain(document);

        assertEquals(PolicyStatus.PAUSED, back.getStatus());
        assertEquals(2, back.getCriticalDays());
        assertEquals(7, back.getHighDays());
        assertEquals(30, back.getMediumDays());
        assertEquals(90, back.getLowDays());
        assertEquals(2, back.getAuditTrail().size());
        assertNull(back.getAuditTrail().get(0).fromStatus());
        assertEquals(PolicyStatus.ACTIVE, back.getAuditTrail().get(1).fromStatus());
        assertEquals("Servers", document.getName());
        assertEquals("PAUSED", document.getStatus());
        assertEquals(tenant, document.getOrganisationId());
        assertEquals(policy.getId(), document.getId());
        assertEquals(policy.getAssetId(), document.getAssetId());
        assertEquals(policy.getCreatedAt(), document.getCreatedAt());
        assertEquals(policy.getUpdatedAt(), document.getUpdatedAt());
        assertEquals(2, document.getAuditTrail().size());
    }

    @Test
    @DisplayName("the document setters are plain, and the persistence mapper is a utility class")
    void plainDocument() throws ReflectiveOperationException {
        PatchPolicyDocument document = new PatchPolicyDocument();
        document.setAuditTrail(new ArrayList<>());
        assertTrue(document.getAuditTrail().isEmpty());

        Constructor<PatchPolicyPersistenceMapper> constructor = PatchPolicyPersistenceMapper.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        assertThrows(InvocationTargetException.class, constructor::newInstance);
    }

    @Test
    @DisplayName("create and the lookups are scoped to the organisation, the list oldest first")
    void lookups() {
        PatchPolicy policy = policy();
        when(collection.insertOne(any(PatchPolicyDocument.class))).thenReturn(Mono.just(InsertOneResult.acknowledged(new BsonObjectId(new ObjectId()))));
        finds(policy);
        PatchPolicyMongoRepositoryAdapter adapter = new PatchPolicyMongoRepositoryAdapter(client, URI);

        StepVerifier.create(adapter.create(policy)).expectNext(policy).verifyComplete();
        StepVerifier.create(adapter.findById(policy.getId(), tenant)).assertNext(found -> assertEquals(policy.getId(), found.getId())).verifyComplete();
        StepVerifier.create(adapter.findAll(tenant)).expectNextCount(1).verifyComplete();

        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);
        verify(collection, times(2)).find(filter.capture());
        assertTrue(filter.getAllValues().get(0).toString().contains("organisationId"));
    }

    @Test
    @DisplayName("a duplicate name is a 409 of the domain, on create or on a rename; any other write error passes through; the URI may omit the database")
    void duplicates() {
        PatchPolicy policy = policy();
        when(collection.insertOne(any(PatchPolicyDocument.class))).thenReturn(Mono.error(writeError(11000))).thenReturn(Mono.error(writeError(1)));
        when(collection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(Mono.error(writeError(11000))).thenReturn(Mono.error(writeError(2)));
        PatchPolicyMongoRepositoryAdapter adapter = new PatchPolicyMongoRepositoryAdapter(client, "mongodb://localhost:27017");

        StepVerifier.create(adapter.create(policy)).expectError(DuplicatePatchPolicyException.class).verify();
        StepVerifier.create(adapter.create(policy)).expectError(MongoWriteException.class).verify();
        StepVerifier.create(adapter.save(policy, PolicyStatus.ACTIVE, policy.getAuditTrail().get(1))).expectError(DuplicatePatchPolicyException.class).verify();
        StepVerifier.create(adapter.save(policy, PolicyStatus.ACTIVE, policy.getAuditTrail().get(1))).expectError(MongoWriteException.class).verify();
        assertThrows(NullPointerException.class, () -> new PatchPolicyMongoRepositoryAdapter(client, null));
    }

    @Test
    @DisplayName("save is guarded by the status loaded and pushes the audit entry; a lost race is a conflict")
    void save() {
        PatchPolicy policy = policy();
        when(collection.updateOne(any(Bson.class), any(Bson.class)))
                .thenReturn(Mono.just(UpdateResult.acknowledged(1, 1L, null)))
                .thenReturn(Mono.just(UpdateResult.acknowledged(0, 0L, null)));
        PatchPolicyMongoRepositoryAdapter adapter = new PatchPolicyMongoRepositoryAdapter(client, URI);

        StepVerifier.create(adapter.save(policy, PolicyStatus.ACTIVE, policy.getAuditTrail().get(1))).verifyComplete();
        StepVerifier.create(adapter.save(policy, PolicyStatus.ACTIVE, policy.getAuditTrail().get(1))).expectError(InvalidPatchPolicyStatusException.class).verify();

        ArgumentCaptor<Bson> guard = ArgumentCaptor.forClass(Bson.class);
        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);
        verify(collection, times(2)).updateOne(guard.capture(), update.capture());
        assertTrue(guard.getAllValues().get(0).toString().contains("ACTIVE") && guard.getAllValues().get(0).toString().contains("organisationId"));
        assertTrue(update.getAllValues().get(0).toString().contains("auditTrail"));
    }
}
