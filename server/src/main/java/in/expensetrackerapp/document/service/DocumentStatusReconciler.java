package in.expensetrackerapp.document.service;

import in.expensetrackerapp.document.model.DocumentStatus;
import in.expensetrackerapp.document.model.UploadedDocument;
import in.expensetrackerapp.job.model.JobStatus;
import in.expensetrackerapp.job.model.ProcessingJob;
import in.expensetrackerapp.job.service.JobService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * M14 / S1: the worker finishes a job and THEN updates the document, non-atomically. If it dies in between,
 * the document stays PROCESSING forever. This sweep derives the document status from its latest job.
 * It also fails jobs whose lease expired with no attempts left (JobService.claimNext is never called by Java).
 */
@Component
public class DocumentStatusReconciler {

    private static final Logger log = LoggerFactory.getLogger(DocumentStatusReconciler.class);
    private static final Duration SETTLE = Duration.ofMinutes(2);

    private final MongoTemplate mongoTemplate;
    private final JobService jobService;
    private final Clock clock;

    public DocumentStatusReconciler(MongoTemplate mongoTemplate, JobService jobService, Clock clock) {
        this.mongoTemplate = mongoTemplate;
        this.jobService = jobService;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${application.documents.reconcile-interval-ms:60000}",
            initialDelayString = "${application.documents.reconcile-initial-delay-ms:30000}")
    public void scheduled() {
        try {
            reconcile();
        } catch (RuntimeException e) {
            log.warn("Document status reconciliation failed: {}", e.getMessage());
        }
    }

    /** Returns the number of documents corrected. */
    public int reconcile() {
        Instant now = clock.instant();
        jobService.failExhaustedExpiredJobs(now);

        Query stuck = new Query(Criteria.where("status").is(DocumentStatus.PROCESSING)
                .and("updatedAt").lte(now.minus(SETTLE)));
        List<UploadedDocument> docs = mongoTemplate.find(stuck, UploadedDocument.class);
        int fixed = 0;
        for (UploadedDocument doc : docs) {
            ProcessingJob job = mongoTemplate.findOne(
                    new Query(Criteria.where("documentId").is(doc.getId()))
                            .with(Sort.by(Sort.Direction.DESC, "createdAt")), ProcessingJob.class);
            DocumentStatus target = target(job);
            if (target == null) {
                continue;
            }
            // Compare-and-set on the old status so a worker that just moved it on is never overwritten.
            long n = mongoTemplate.updateFirst(
                    new Query(Criteria.where("_id").is(doc.getId()).and("status").is(DocumentStatus.PROCESSING)),
                    new Update().set("status", target).set("updatedAt", now),
                    UploadedDocument.class).getModifiedCount();
            fixed += (int) n;
        }
        if (fixed > 0) {
            log.info("Reconciled {} document(s) stuck in PROCESSING", fixed);
        }
        return fixed;
    }

    private static DocumentStatus target(ProcessingJob job) {
        if (job == null || job.getStatus() == null) {
            return null;
        }
        return switch (job.getStatus()) {
            case COMPLETED, PARTIALLY_COMPLETED -> DocumentStatus.PROCESSED;
            case FAILED -> DocumentStatus.FAILED;
            case PENDING -> DocumentStatus.UPLOADED;      // retry scheduled
            case PROCESSING -> null;                      // genuinely running
        };
    }
}
