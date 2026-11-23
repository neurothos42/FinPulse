package in.expensetrackerapp.document.controller;

import in.expensetrackerapp.common.exception.ConflictException;
import in.expensetrackerapp.common.exception.ResourceNotFoundException;
import in.expensetrackerapp.document.dto.DocumentResponse;
import in.expensetrackerapp.document.model.DocumentStatus;
import in.expensetrackerapp.document.model.UploadedDocument;
import in.expensetrackerapp.document.repository.DocumentRepository;
import in.expensetrackerapp.document.service.DocumentService;
import in.expensetrackerapp.job.model.JobStatus;
import in.expensetrackerapp.job.model.ProcessingJob;
import in.expensetrackerapp.job.service.JobService;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/** D6 / S4: POST /api/documents/{id}/reprocess queues a NEW job for a document whose latest job has finished. */
@RestController
@RequestMapping("/api/documents")
public class DocumentReprocessController {

    private final DocumentRepository documents;
    private final DocumentService documentService;
    private final JobService jobService;
    private final MongoTemplate mongo;

    public DocumentReprocessController(DocumentRepository documents, DocumentService documentService,
                                       JobService jobService, MongoTemplate mongo) {
        this.documents = documents;
        this.documentService = documentService;
        this.jobService = jobService;
        this.mongo = mongo;
    }

    @PostMapping("/{documentId}/reprocess")
    public ResponseEntity<DocumentResponse> reprocess(Authentication authentication, @PathVariable String documentId) {
        String userId = authentication.getName();
        UploadedDocument doc = documents.findByIdAndUserId(documentId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found"));
        ProcessingJob latest = mongo.findOne(new Query(Criteria.where("userId").is(userId).and("documentId").is(documentId))
                .with(Sort.by(Sort.Direction.DESC, "createdAt")), ProcessingJob.class);
        if (latest != null && (latest.getStatus() == JobStatus.PENDING || latest.getStatus() == JobStatus.PROCESSING)) {
            throw new ConflictException("This document is already queued or being processed");
        }
        // Rows already extracted are not duplicated: the worker dedupes on (userId, sourceHash).
        jobService.createJob(userId, doc.getId(), doc.getGridFsFileId());
        mongo.updateFirst(new Query(Criteria.where("_id").is(doc.getId())),
                new Update().set("status", DocumentStatus.UPLOADED).set("updatedAt", Instant.now()), UploadedDocument.class);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(documentService.get(userId, documentId));
    }
}
