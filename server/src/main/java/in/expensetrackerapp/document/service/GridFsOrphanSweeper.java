package in.expensetrackerapp.document.service;

import in.expensetrackerapp.config.GridFsProperties;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Phase 6: removes GridFS files whose document no longer exists (failed deletes / rollbacks). Files < 1 day old are never touched. */
@Component
public class GridFsOrphanSweeper {

    private static final Logger log = LoggerFactory.getLogger(GridFsOrphanSweeper.class);
    private static final int BATCH = 200;

    private final MongoTemplate mongo;
    private final GridFsProperties props;
    private final GridFsStorageService storage;
    private final Clock clock;

    public GridFsOrphanSweeper(MongoTemplate mongo, GridFsProperties props, GridFsStorageService storage, Clock clock) {
        this.mongo = mongo;
        this.props = props;
        this.storage = storage;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${application.sweeper.gridfs-interval-ms:3600000}",
            initialDelayString = "${application.sweeper.gridfs-initial-delay-ms:120000}")
    public void scheduled() {
        try {
            sweep();
        } catch (RuntimeException e) {
            log.warn("GridFS orphan sweep failed: {}", e.getMessage());
        }
    }

    public int sweep() {
        Date cutoff = Date.from(clock.instant().minus(Duration.ofDays(1)));
        Query q = new Query(Criteria.where("uploadDate").lt(cutoff).and("metadata.documentId").exists(true)).limit(BATCH);
        List<Document> files = mongo.find(q, Document.class, props.getBucket() + ".files");
        List<String> orphans = new ArrayList<>();
        for (Document f : files) {
            String documentId = f.get("metadata", Document.class).getString("documentId");
            if (documentId == null || !ObjectId.isValid(documentId)) continue;
            boolean exists = mongo.exists(new Query(Criteria.where("_id").is(new ObjectId(documentId))), "uploaded_documents");
            if (!exists) orphans.add(f.getObjectId("_id").toHexString());
        }
        orphans.forEach(storage::deleteQuietly);
        if (!orphans.isEmpty()) log.info("Removed {} orphaned GridFS file(s)", orphans.size());
        return orphans.size();
    }
}
