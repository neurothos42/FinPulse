package in.expensetrackerapp.config;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * M7: additional indexes. They are created here instead of on the entity annotations because the
 * Transaction annotations are pinned (by name, exactly) to contracts/transaction_indexes.json, which the
 * Python worker also checks. These extra indexes use different names/keys, so they never conflict with it.
 * A failure is logged, not fatal (the app works without them, only slower).
 */
@Component
public class IndexInitializer {

    private static final Logger log = LoggerFactory.getLogger(IndexInitializer.class);
    private final MongoTemplate mongo;

    public IndexInitializer(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ensureIndexes() {
        // documents <-> jobs <-> transactions lookups
        ensure("processing_jobs", new Index().on("documentId", Sort.Direction.ASC)
                .on("createdAt", Sort.Direction.DESC).named("documentId_createdAt"));
        ensure("uploaded_documents", new Index().on("userId", Sort.Direction.ASC)
                .on("sha256", Sort.Direction.ASC).named("userId_sha256"));
        ensure("transactions", new Index().on("userId", Sort.Direction.ASC).on("documentId", Sort.Direction.ASC)
                .on("date", Sort.Direction.DESC).named("userId_documentId_date"));
        ensure("transactions", new Index().on("userId", Sort.Direction.ASC).on("jobId", Sort.Direction.ASC)
                .named("userId_jobId"));
        ensure("transactions", new Index().on("userId", Sort.Direction.ASC).on("accountId", Sort.Direction.ASC)
                .named("userId_accountId"));
        // idempotent create: only rows that carry a clientRequestId take part (partial index)
        ensure("transactions", new Index().on("userId", Sort.Direction.ASC).on("clientRequestId", Sort.Direction.ASC)
                .unique().named("userId_clientRequestId_unique")
                .partial(PartialIndexFilter.of(new Document("clientRequestId", new Document("$type", "string")))));
        // one payment per (bill, due date)
        ensure("transactions", new Index().on("userId", Sort.Direction.ASC).on("billId", Sort.Direction.ASC)
                .on("billDueDate", Sort.Direction.ASC).unique().named("userId_billId_billDueDate_unique")
                .partial(PartialIndexFilter.of(new Document("billId", new Document("$type", "string")))));

        ensure("voice_entries", new Index().on("userId", Sort.Direction.ASC).on("createdAt", Sort.Direction.DESC)
                .named("userId_createdAt"));

        // financial entities
        ensure("accounts", new Index().on("userId", Sort.Direction.ASC).on("name", Sort.Direction.ASC)
                .unique().named("userId_name_unique"));
        ensure("budgets", new Index().on("userId", Sort.Direction.ASC).on("category", Sort.Direction.ASC)
                .unique().named("userId_category_unique"));
        ensure("goals", new Index().on("userId", Sort.Direction.ASC).on("targetDate", Sort.Direction.ASC)
                .named("userId_targetDate"));
        ensure("holdings", new Index().on("userId", Sort.Direction.ASC).on("assetClass", Sort.Direction.ASC)
                .named("userId_assetClass"));
        ensure("bills", new Index().on("userId", Sort.Direction.ASC).on("paid", Sort.Direction.ASC)
                .on("dueDate", Sort.Direction.ASC).named("userId_paid_dueDate"));
        for (String c : new String[]{"loans", "splits", "festivals"}) {
            ensure(c, new Index().on("userId", Sort.Direction.ASC).named("userId_idx"));
        }
    }

    private void ensure(String collection, Index index) {
        try {
            mongo.indexOps(collection).createIndex(index);
        } catch (RuntimeException e) {
            log.warn("Could not create index on {}: {}", collection, e.getMessage());
        }
    }
}
