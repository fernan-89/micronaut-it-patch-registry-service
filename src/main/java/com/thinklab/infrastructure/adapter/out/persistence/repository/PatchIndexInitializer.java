package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.ConnectionString;
import com.mongodb.MongoTimeoutException;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.reactivestreams.client.MongoClient;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.StartupEvent;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Objects;

/**
 * Creates the indexes at startup. The UNIQUE ones are the atomic backstops (ADR-033): a policy name per organisation, and one finding per
 * {@code (organisation, policy, patchRef)} (ADR-030: the same report twice is the same finding). The finding list index serves the real query.
 * Fail-open: {@code createIndex} is idempotent, a failure is logged and the application still starts. Turn it off with
 * {@code thinklab.mongo.create-indexes=false}.
 */
@Singleton
@Requires(property = "thinklab.mongo.create-indexes", notEquals = "false")
public class PatchIndexInitializer implements ApplicationEventListener<StartupEvent> {

    static final String POLICY_NAME_INDEX = "organisationId_1_name_1";
    static final String FINDING_KEY_INDEX = "organisationId_1_policyId_1_patchRef_1";
    static final String FINDING_LIST_INDEX = "organisationId_1_policyId_1_status_1";

    private static final Logger log = LoggerFactory.getLogger(PatchIndexInitializer.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final MongoClient mongoClient;
    private final String database;
    private final Duration timeout;

    @Inject
    public PatchIndexInitializer(MongoClient mongoClient, @Property(name = "mongodb.uri") String mongoUri) {
        this(mongoClient, mongoUri, TIMEOUT);
    }

    PatchIndexInitializer(MongoClient mongoClient, String mongoUri, Duration timeout) {
        this.mongoClient = Objects.requireNonNull(mongoClient, "Infrastructure constraint violated: MongoClient cannot be null.");
        String configured = new ConnectionString(Objects.requireNonNull(mongoUri, "mongodb.uri cannot be null.")).getDatabase();
        this.database = configured != null ? configured : PatchPolicyMongoRepositoryAdapter.DEFAULT_DATABASE;
        this.timeout = timeout;
    }

    @Override
    public void onApplicationEvent(StartupEvent event) {
        Objects.requireNonNull(event, "Application constraint violated: StartupEvent cannot be null.");
        ensureIndex(PatchPolicyMongoRepositoryAdapter.COLLECTION_NAME, POLICY_NAME_INDEX, new Document("organisationId", 1).append("name", 1), new IndexOptions().unique(true));
        String findings = PatchFindingMongoRepositoryAdapter.COLLECTION_NAME;
        ensureIndex(findings, FINDING_KEY_INDEX, new Document("organisationId", 1).append("policyId", 1).append("patchRef", 1), new IndexOptions().unique(true));
        ensureIndex(findings, FINDING_LIST_INDEX, new Document("organisationId", 1).append("policyId", 1).append("status", 1), new IndexOptions());
    }

    private void ensureIndex(String collection, String indexName, Document keys, IndexOptions options) {
        try {
            Mono.from(mongoClient.getDatabase(database).getCollection(collection).createIndex(keys, options.name(indexName))).block(timeout);
            log.info("[MONGO_INDEXES] Ensured index [{}] on [{}.{}]", indexName, database, collection);
        } catch (MongoTimeoutException e) {
            log.error("[MONGO_INDEXES] MongoDB unreachable; index [{}] was not created. Reason: {}", indexName, e.getMessage());
        } catch (RuntimeException e) {
            log.error("[MONGO_INDEXES] Could not create index [{}] on [{}.{}]: {}", indexName, database, collection, e.getMessage());
        }
    }
}
