package in.expensetrackerapp.document.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import in.expensetrackerapp.document.model.DocumentStatus;
import in.expensetrackerapp.document.model.UploadedDocument;
import in.expensetrackerapp.job.model.JobStatus;
import in.expensetrackerapp.job.model.ProcessingJob;

import java.time.Instant;

/**
 * API view of a document. userId, GridFS ids and stored filenames are intentionally not exposed.
 * The job summary fields (jobId .. errorMessage) are additive and omitted when null (NON_NULL), so
 * clients written against the original shape keep working.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DocumentResponse(
        String id,
        String originalFilename,
        String contentType,
        long sizeBytes,
        DocumentStatus status,
        Instant createdAt,
        Instant updatedAt,
        String jobId,
        JobStatus jobStatus,
        Integer extractedCount,
        Integer duplicateCount,
        String errorMessage) {

    /** Original 8-component shape; kept so existing callers keep compiling. */
    public DocumentResponse(String id, String originalFilename, String contentType, long sizeBytes,
                            DocumentStatus status, Instant createdAt, Instant updatedAt, String jobId) {
        this(id, originalFilename, contentType, sizeBytes, status, createdAt, updatedAt, jobId,
                null, null, null, null);
    }

    public static DocumentResponse fromDocument(UploadedDocument d) {
        return new DocumentResponse(
                d.getId(),
                d.getOriginalFilename(),
                d.getContentType(),
                d.getSizeBytes(),
                d.getStatus(),
                d.getCreatedAt(),
                d.getUpdatedAt(),
                null);
    }

    /** Upload responses carry the id of the processing job so clients can poll GET /api/jobs/{id}. */
    public DocumentResponse withJobId(String jobId) {
        return new DocumentResponse(id, originalFilename, contentType, sizeBytes, status, createdAt, updatedAt, jobId);
    }

    /** Adds the latest job's id, status and counts (M4). A null job leaves the response unchanged. */
    public DocumentResponse withJob(ProcessingJob job) {
        if (job == null) {
            return this;
        }
        return new DocumentResponse(id, originalFilename, contentType, sizeBytes, status, createdAt, updatedAt,
                job.getId(), job.getStatus(), job.getExtractedCount(), job.getDuplicateCount(), job.getErrorMessage());
    }
}
