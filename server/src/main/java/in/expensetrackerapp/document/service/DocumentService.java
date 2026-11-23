package in.expensetrackerapp.document.service;

import in.expensetrackerapp.common.exception.BadRequestException;
import in.expensetrackerapp.common.exception.ConflictException;
import in.expensetrackerapp.common.exception.PayloadTooLargeException;
import in.expensetrackerapp.common.exception.ResourceNotFoundException;
import in.expensetrackerapp.common.exception.UnsupportedFileTypeException;
import in.expensetrackerapp.document.dto.DocumentResponse;
import in.expensetrackerapp.document.model.DocumentStatus;
import in.expensetrackerapp.document.model.DocumentType;
import in.expensetrackerapp.document.model.UploadedDocument;
import in.expensetrackerapp.document.repository.DocumentRepository;
import in.expensetrackerapp.document.dto.DocumentPageResponse;
import in.expensetrackerapp.job.model.JobStatus;
import in.expensetrackerapp.job.model.ProcessingJob;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import in.expensetrackerapp.job.service.JobService;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);
    static final int MAX_PAGE_SIZE = 100;

    private final DocumentRepository documentRepository;
    private final GridFsStorageService storageService;
    private final JobService jobService;
    private final long maxFileSizeBytes;

    /** Optional (field-injected so the original constructor stays unchanged); null in plain unit tests. */
    @Autowired(required = false)
    private MongoTemplate mongoTemplate;

    public DocumentService(
            DocumentRepository documentRepository,
            GridFsStorageService storageService,
            JobService jobService,
            @Value("${spring.servlet.multipart.max-file-size:10MB}") String maxFileSize) {
        this.documentRepository = documentRepository;
        this.storageService = storageService;
        this.jobService = jobService;
        this.maxFileSizeBytes = DataSize.parse(maxFileSize).toBytes();
    }

    public DocumentResponse upload(String userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("A non-empty file is required");
        }
        if (file.getSize() > maxFileSizeBytes) {
            throw new PayloadTooLargeException("Uploaded file exceeds the maximum allowed size");
        }

        DocumentType type = detectType(file);

        String documentId = new ObjectId().toHexString();
        String storedFilename = UUID.randomUUID() + "." + type.getExtension();
        String gridFsFileId = null;
        boolean metadataInserted = false;
        try {
            MessageDigest digest = newSha256();
            try (InputStream in = new DigestInputStream(file.getInputStream(), digest)) {
                gridFsFileId = storageService.store(in, storedFilename, type.getContentType(), userId, documentId);
            }

            Instant now = Instant.now();
            UploadedDocument doc = new UploadedDocument();
            doc.setId(documentId);
            doc.setUserId(userId);
            doc.setOriginalFilename(FilenameSanitizer.sanitize(file.getOriginalFilename()));
            doc.setStoredFilename(storedFilename);
            doc.setContentType(type.getContentType());
            doc.setSizeBytes(file.getSize());
            doc.setSha256(HexFormat.of().formatHex(digest.digest()));
            doc.setGridFsFileId(gridFsFileId);
            doc.setStatus(DocumentStatus.UPLOADED);
            doc.setCreatedAt(now);
            doc.setUpdatedAt(now);

            // insert() (not save()) so an id collision fails instead of silently overwriting.
            UploadedDocument saved = documentRepository.insert(doc);
            metadataInserted = true;

            ProcessingJob job = jobService.createJob(userId, saved.getId(), gridFsFileId);
            return DocumentResponse.fromDocument(saved).withJobId(job.getId());
        } catch (IOException e) {
            rollbackUpload(documentId, metadataInserted, gridFsFileId);
            throw new BadRequestException("Could not read the uploaded file");
        } catch (RuntimeException e) {
            // Never leave an orphaned GridFS file or a job-less document behind.
            rollbackUpload(documentId, metadataInserted, gridFsFileId);
            throw e;
        }
    }

    private void rollbackUpload(String documentId, boolean metadataInserted, String gridFsFileId) {
        if (metadataInserted) {
            try {
                documentRepository.deleteById(documentId);
            } catch (RuntimeException e) {
                log.error("Failed to roll back document metadata {}: {}", documentId, e.getMessage());
            }
        }
        storageService.deleteQuietly(gridFsFileId);
    }

    public List<DocumentResponse> list(String userId, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        List<UploadedDocument> docs = documentRepository
                .findByUserId(userId, PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt")))
                .getContent();
        return enrich(userId, docs);
    }

    /** Same data as list() inside a page envelope with totals (M4). */
    public DocumentPageResponse listPage(String userId, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        var result = documentRepository.findByUserId(userId,
                PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt")));
        return new DocumentPageResponse(enrich(userId, result.getContent()), safePage, safeSize,
                result.getTotalElements(), result.getTotalPages());
    }

    public DocumentResponse get(String userId, String documentId) {
        UploadedDocument doc = findOwned(userId, documentId);
        return enrich(userId, List.of(doc)).get(0);
    }

    /** One query for all jobs of the page (indexed by documentId); newest job per document wins. */
    private List<DocumentResponse> enrich(String userId, List<UploadedDocument> docs) {
        if (mongoTemplate == null || docs.isEmpty()) {
            return docs.stream().map(DocumentResponse::fromDocument).toList();
        }
        List<String> ids = docs.stream().map(UploadedDocument::getId).toList();
        Query q = new Query(Criteria.where("userId").is(userId).and("documentId").in(ids))
                .with(Sort.by(Sort.Direction.DESC, "createdAt"));
        Map<String, ProcessingJob> latest = new HashMap<>();
        for (ProcessingJob job : mongoTemplate.find(q, ProcessingJob.class)) {
            latest.putIfAbsent(job.getDocumentId(), job);
        }
        return docs.stream().map(d -> DocumentResponse.fromDocument(d).withJob(latest.get(d.getId()))).toList();
    }

    public DocumentDownload download(String userId, String documentId) {
        UploadedDocument doc = findOwned(userId, documentId);
        Resource resource = storageService.open(doc.getGridFsFileId())
                .orElseThrow(() -> {
                    log.error("GridFS file missing for document {}", doc.getId());
                    return new ResourceNotFoundException("Document file is not available");
                });
        return new DocumentDownload(doc, resource);
    }

    public void delete(String userId, String documentId) {
        UploadedDocument doc = findOwned(userId, documentId);
        List<ProcessingJob> jobs = jobsOf(userId, doc.getId());
        // S1: a document left at PROCESSING after its job already finished must still be deletable.
        boolean reallyProcessing = doc.getStatus() == DocumentStatus.PROCESSING
                && (jobs.isEmpty() || jobs.stream().anyMatch(j -> j.getStatus() == JobStatus.PROCESSING));
        if (reallyProcessing) {
            throw new ConflictException("Document is currently being processed and cannot be deleted");
        }
        // Metadata first: a leftover file is a harmless orphan, a leftover record pointing at a missing file is not.
        documentRepository.deleteById(doc.getId());
        storageService.deleteQuietly(doc.getGridFsFileId());
        deleteJobsAndReports(userId, jobs);
        // D4: ledger rows (transactions) are intentionally kept; their documentId now points at a removed document.
    }

    private List<ProcessingJob> jobsOf(String userId, String documentId) {
        if (mongoTemplate == null) {
            return List.of();
        }
        return mongoTemplate.find(new Query(Criteria.where("userId").is(userId).and("documentId").is(documentId)),
                ProcessingJob.class);
    }

    /** M13/S3: a removed document must not leave a PENDING job (it would burn attempts) or an orphan report. */
    private void deleteJobsAndReports(String userId, List<ProcessingJob> jobs) {
        if (mongoTemplate == null || jobs.isEmpty()) {
            return;
        }
        try {
            List<String> jobIds = jobs.stream().map(ProcessingJob::getId).toList();
            mongoTemplate.remove(new Query(Criteria.where("userId").is(userId).and("_id").in(jobIds)), ProcessingJob.class);
            mongoTemplate.remove(new Query(Criteria.where("userId").is(userId).and("jobId").in(jobIds)), "reports");
        } catch (RuntimeException e) {
            log.error("Failed to clean up jobs of a deleted document: {}", e.getMessage());
        }
    }

    /** Owner-scoped lookup. Another user's document is reported as 404 so its existence is not revealed. */
    private UploadedDocument findOwned(String userId, String documentId) {
        return documentRepository.findByIdAndUserId(documentId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found"));
    }

    private DocumentType detectType(MultipartFile file) {
        byte[] header;
        try (InputStream in = file.getInputStream()) {
            header = in.readNBytes(FileSignatureDetector.HEADER_LENGTH);
        } catch (IOException e) {
            throw new BadRequestException("Could not read the uploaded file");
        }
        DocumentType detected = FileSignatureDetector.detect(header)
                .orElseThrow(() -> new UnsupportedFileTypeException(
                        "Unsupported file type. Only PDF, JPG and PNG files are accepted"));

        String declared = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT).trim();
        boolean declaredIsSupported = declared.equals("application/pdf") || declared.equals("image/jpeg")
                || declared.equals("image/jpg") || declared.equals("image/png");
        boolean matches = declared.equals(detected.getContentType())
                || (declared.equals("image/jpg") && detected == DocumentType.JPEG);
        if (declaredIsSupported && !matches) {
            throw new UnsupportedFileTypeException("File content does not match its declared type");
        }
        return detected;
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
