package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoWriteException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.thinklab.domain.exception.InvalidPatchFindingStatusException;
import com.thinklab.domain.model.PatchFinding;
import com.thinklab.domain.model.PatchFinding.FindingAuditEntry;
import com.thinklab.domain.model.PatchFinding.FindingStatus;
import com.thinklab.domain.repository.PatchFindingRepository;
import com.thinklab.infrastructure.adapter.out.persistence.entity.PatchFindingDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.PatchFindingDocument.AuditEntryDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.PatchFindingDocument.PatchFindingPersistenceMapper;
import io.micronaut.context.annotation.Property;
import jakarta.inject.Singleton;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.bson.conversions.Bson;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * MongoDB Reactive Repository Adapter for the PatchFinding aggregate, raw reactive-streams driver. The unique {@code (organisationId, policyId,
 * patchRef)} index is what makes a repeated report the same finding: the loser of an insert reads the stored one (ADR-030). Every change is one
 * atomic update guarded by the status loaded (ADR-033) that also appends the audit entry.
 */
@Singleton
public class PatchFindingMongoRepositoryAdapter implements PatchFindingRepository {

    static final String COLLECTION_NAME = "patch_findings";
    private static final String FIELD_ID = "_id";
    private static final String FIELD_ORGANISATION = "organisationId";
    private static final String FIELD_POLICY = "policyId";
    private static final String FIELD_STATUS = "status";

    private static final CodecRegistry POJO_CODEC_REGISTRY = CodecRegistries.fromRegistries(
            MongoClientSettings.getDefaultCodecRegistry(),
            CodecRegistries.fromProviders(PojoCodecProvider.builder().automatic(true).build())
    );

    private final MongoClient mongoClient;
    private final String database;

    public PatchFindingMongoRepositoryAdapter(MongoClient mongoClient, @Property(name = "mongodb.uri") String mongoUri) {
        this.mongoClient = mongoClient;
        String configured = new ConnectionString(Objects.requireNonNull(mongoUri, "mongodb.uri cannot be null.")).getDatabase();
        this.database = configured != null ? configured : PatchPolicyMongoRepositoryAdapter.DEFAULT_DATABASE;
    }

    private MongoCollection<PatchFindingDocument> getCollection() {
        return mongoClient.getDatabase(database).getCollection(COLLECTION_NAME, PatchFindingDocument.class).withCodecRegistry(POJO_CODEC_REGISTRY);
    }

    @Override
    public Mono<Stored> create(PatchFinding finding) {
        return Mono.from(getCollection().insertOne(PatchFindingPersistenceMapper.toDocument(finding)))
                .map(result -> new Stored(finding, true))
                .onErrorResume(MongoWriteException.class, error -> error.getError().getCode() == PatchPolicyMongoRepositoryAdapter.DUPLICATE_KEY
                        ? findByNaturalKey(finding.getOrganisationId(), finding.getPolicyId(), finding.getPatchRef()).map(existing -> new Stored(existing, false))
                        : Mono.error(error));
    }

    @Override
    public Mono<PatchFinding> findByNaturalKey(UUID organisationId, UUID policyId, String patchRef) {
        return Mono.from(getCollection().find(Filters.and(Filters.eq(FIELD_ORGANISATION, organisationId), Filters.eq(FIELD_POLICY, policyId), Filters.eq("patchRef", patchRef))).first())
                .map(PatchFindingPersistenceMapper::toDomain);
    }

    @Override
    public Mono<PatchFinding> findById(UUID id, UUID organisationId) {
        return Mono.from(getCollection().find(Filters.and(Filters.eq(FIELD_ID, id), Filters.eq(FIELD_ORGANISATION, organisationId))).first())
                .map(PatchFindingPersistenceMapper::toDomain);
    }

    @Override
    public Flux<PatchFinding> findAll(UUID organisationId, Filter filter, int limit) {
        List<Bson> conditions = new ArrayList<>();
        conditions.add(Filters.eq(FIELD_ORGANISATION, organisationId));
        if (filter.policyId() != null) {
            conditions.add(Filters.eq(FIELD_POLICY, filter.policyId()));
        }
        if (filter.status() != null) {
            conditions.add(Filters.eq(FIELD_STATUS, filter.status().name()));
        }
        if (filter.severity() != null) {
            conditions.add(Filters.eq("severity", filter.severity().name()));
        }
        return Flux.from(getCollection().find(Filters.and(conditions)).sort(Sorts.descending("announcedAt")).limit(limit)).map(PatchFindingPersistenceMapper::toDomain);
    }

    @Override
    public Flux<PatchFinding> findChangedSince(UUID organisationId, Instant since) {
        return Flux.from(getCollection().find(Filters.and(Filters.eq(FIELD_ORGANISATION, organisationId), Filters.gte("updatedAt", since)))).map(PatchFindingPersistenceMapper::toDomain);
    }

    @Override
    public Flux<PatchFinding> findUnresolved(UUID organisationId, UUID policyId) {
        List<Bson> conditions = new ArrayList<>();
        conditions.add(Filters.eq(FIELD_ORGANISATION, organisationId));
        conditions.add(Filters.ne(FIELD_STATUS, FindingStatus.APPLIED.name()));
        if (policyId != null) {
            conditions.add(Filters.eq(FIELD_POLICY, policyId));
        }
        return Flux.from(getCollection().find(Filters.and(conditions))).map(PatchFindingPersistenceMapper::toDomain);
    }

    @Override
    public Mono<Void> save(PatchFinding finding, FindingStatus expectedStatus, FindingAuditEntry auditEntry) {
        Bson guard = Filters.and(Filters.eq(FIELD_ID, finding.getId()), Filters.eq(FIELD_ORGANISATION, finding.getOrganisationId()),
                Filters.eq(FIELD_STATUS, expectedStatus.name()));
        Bson update = Updates.combine(
                Updates.set(FIELD_STATUS, finding.getStatus().name()),
                Updates.set("appliedAt", finding.getAppliedAt()),
                Updates.set("waiverReason", finding.getWaiverReason() != null ? finding.getWaiverReason().name() : null),
                Updates.set("waivedUntil", finding.getWaivedUntil()),
                Updates.set("updatedAt", Instant.now()),
                Updates.push("auditTrail", AuditEntryDocument.fromDomain(auditEntry))
        );
        return Mono.from(getCollection().updateOne(guard, update))
                .flatMap(result -> result.getMatchedCount() == 0
                        ? Mono.error(new InvalidPatchFindingStatusException("PatchFinding was changed by someone else while this change was being recorded; read it again and retry."))
                        : Mono.<Void>empty());
    }
}
