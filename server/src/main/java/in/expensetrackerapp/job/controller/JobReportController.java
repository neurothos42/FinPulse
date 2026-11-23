package in.expensetrackerapp.job.controller;

import in.expensetrackerapp.common.exception.ResourceNotFoundException;
import in.expensetrackerapp.job.service.JobService;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Read-only view of the extraction summary the Python worker writes to {@code reports} (M12).
 * Contains counts only (no merchants or amounts). Ownership is checked through the job first.
 */
@RestController
@RequestMapping("/api/jobs")
public class JobReportController {

    private static final String[] FIELDS = {
            "attempt", "extracted", "duplicates", "pipelineDuplicates", "databaseDuplicates", "discarded",
            "mappingErrors", "needsReview", "unknownDirection", "ocrConfidence", "mlModel", "mlRowsConsidered",
            "partial", "createdAt", "updatedAt"};

    private final JobService jobService;
    private final MongoTemplate mongoTemplate;

    public JobReportController(JobService jobService, MongoTemplate mongoTemplate) {
        this.jobService = jobService;
        this.mongoTemplate = mongoTemplate;
    }

    @GetMapping("/{jobId}/report")
    public ResponseEntity<Map<String, Object>> report(Authentication authentication, @PathVariable String jobId) {
        String userId = authentication.getName();
        jobService.getJob(userId, jobId);                       // 404 unless the caller owns the job
        Document doc = mongoTemplate.findOne(
                new Query(Criteria.where("userId").is(userId).and("jobId").is(jobId)), Document.class, "reports");
        if (doc == null) {
            throw new ResourceNotFoundException("No report is available for this job yet");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", jobId);
        body.put("documentId", doc.get("documentId"));
        for (String f : FIELDS) {
            if (doc.containsKey(f)) {
                body.put(f, doc.get(f));
            }
        }
        return ResponseEntity.ok(body);
    }
}
