package in.expensetrackerapp.document.controller;

import in.expensetrackerapp.document.dto.DocumentPageResponse;
import in.expensetrackerapp.document.dto.DocumentResponse;
import in.expensetrackerapp.document.service.DocumentDownload;
import in.expensetrackerapp.document.service.DocumentService;
import in.expensetrackerapp.document.service.FilenameSanitizer;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.util.List;

/** The owner is always taken from the authenticated principal, never from request data. */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentResponse> upload(
            Authentication authentication,
            @RequestPart("file") MultipartFile file) {
        DocumentResponse response = documentService.upload(authentication.getName(), file);
        return ResponseEntity.created(URI.create("/api/documents/" + response.id())).body(response);
    }

    @GetMapping
    public ResponseEntity<List<DocumentResponse>> list(
            Authentication authentication,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(documentService.list(authentication.getName(), page, size));
    }

    /** Same items as GET /api/documents, wrapped as {content,page,size,totalElements,totalPages}. */
    @GetMapping("/page")
    public ResponseEntity<DocumentPageResponse> listPage(
            Authentication authentication,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(documentService.listPage(authentication.getName(), page, size));
    }

    @GetMapping("/{documentId}")
    public ResponseEntity<DocumentResponse> get(Authentication authentication, @PathVariable String documentId) {
        return ResponseEntity.ok(documentService.get(authentication.getName(), documentId));
    }

    @GetMapping("/{documentId}/download")
    public ResponseEntity<Resource> download(Authentication authentication, @PathVariable String documentId) {
        DocumentDownload download = documentService.download(authentication.getName(), documentId);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(FilenameSanitizer.sanitize(download.document().getOriginalFilename()))
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.document().getContentType()))
                .contentLength(download.document().getSizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(download.resource());
    }

    @DeleteMapping("/{documentId}")
    public ResponseEntity<Void> delete(Authentication authentication, @PathVariable String documentId) {
        documentService.delete(authentication.getName(), documentId);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
