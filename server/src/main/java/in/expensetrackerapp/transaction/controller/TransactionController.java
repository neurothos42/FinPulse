package in.expensetrackerapp.transaction.controller;

import in.expensetrackerapp.transaction.dto.TransactionBulkRequest;
import in.expensetrackerapp.transaction.dto.TransactionBulkResponse;
import in.expensetrackerapp.transaction.dto.TransactionCreateRequest;
import in.expensetrackerapp.transaction.dto.TransactionPageResponse;
import in.expensetrackerapp.transaction.dto.TransactionResponse;
import in.expensetrackerapp.transaction.dto.TransactionUpdateRequest;
import in.expensetrackerapp.transaction.model.TransactionDirection;
import in.expensetrackerapp.transaction.service.TransactionFilter;
import in.expensetrackerapp.transaction.service.TransactionService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** The owner is always taken from the authenticated principal, never from request data. */
@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @GetMapping
    public ResponseEntity<TransactionPageResponse> list(
            Authentication authentication,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) TransactionDirection direction,
            @RequestParam(required = false) Boolean needsReview,
            @RequestParam(required = false) String documentId,
            @RequestParam(required = false) String jobId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) String type) {
        TransactionFilter filter = new TransactionFilter(from, to, category, direction, needsReview,
                documentId, jobId, q, accountId, type);
        return ResponseEntity.ok(transactionService.list(authentication.getName(), filter, page, size));
    }

    @PostMapping
    public ResponseEntity<TransactionResponse> create(
            Authentication authentication, @Valid @RequestBody TransactionCreateRequest request) {
        TransactionResponse created = transactionService.create(authentication.getName(), request);
        return ResponseEntity.created(java.net.URI.create("/api/transactions/" + created.id())).body(created);
    }

    @PostMapping("/bulk")
    public ResponseEntity<TransactionBulkResponse> createBulk(
            Authentication authentication, @Valid @RequestBody TransactionBulkRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(transactionService.createBulk(authentication.getName(), request));
    }

    /** CSV of the filtered rows (max 50,000) for the Reports screen. */
    @GetMapping("/export")
    public ResponseEntity<String> export(
            Authentication authentication,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) TransactionDirection direction,
            @RequestParam(required = false) Boolean needsReview,
            @RequestParam(required = false) String documentId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) String type) {
        TransactionFilter filter = new TransactionFilter(from, to, category, direction, needsReview,
                documentId, null, q, accountId, type);
        return ResponseEntity.ok()
                .header("Content-Type", "text/csv; charset=UTF-8")
                .header("Content-Disposition", "attachment; filename=\"transactions.csv\"")
                .header("X-Content-Type-Options", "nosniff")
                .header("Cache-Control", "private, no-store")
                .body(transactionService.exportCsv(authentication.getName(), filter));
    }

    @GetMapping("/{id}")
    public ResponseEntity<TransactionResponse> get(Authentication authentication, @PathVariable String id) {
        return ResponseEntity.ok(transactionService.get(authentication.getName(), id));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<TransactionResponse> update(
            Authentication authentication,
            @PathVariable String id,
            @Valid @RequestBody TransactionUpdateRequest request) {
        return ResponseEntity.ok(transactionService.update(authentication.getName(), id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(Authentication authentication, @PathVariable String id) {
        transactionService.delete(authentication.getName(), id);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
