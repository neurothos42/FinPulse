package in.expensetrackerapp.document.controller;

import in.expensetrackerapp.common.exception.BadRequestException;
import in.expensetrackerapp.common.exception.ResourceNotFoundException;
import in.expensetrackerapp.document.repository.DocumentRepository;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Bulk actions on the rows the Python worker extracted from one document. The client needs an account on every
 * transaction and a quick "looks right" action; doing it here avoids one PATCH per row and needs no worker change.
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentRowsController {

    private final DocumentRepository documents;
    private final MongoTemplate mongo;

    public DocumentRowsController(DocumentRepository documents, MongoTemplate mongo) {
        this.documents = documents;
        this.mongo = mongo;
    }

    /** Body {"accountId": "..."}: sets the account on this document's rows that have none. */
    @PostMapping("/{documentId}/assign-account")
    public ResponseEntity<Map<String, Object>> assignAccount(Authentication authentication, @PathVariable String documentId,
                                                             @RequestBody Map<String, String> body) {
        String userId = authentication.getName();
        owned(userId, documentId);
        String accountId = body == null ? null : body.get("accountId");
        if (accountId == null || !ObjectId.isValid(accountId) || mongo.count(new Query(Criteria.where("userId").is(userId)
                .and("_id").is(new ObjectId(accountId))), "accounts") == 0) {
            throw new BadRequestException("accountId does not refer to one of your accounts");
        }
        long n = mongo.updateMulti(new Query(Criteria.where("userId").is(userId).and("documentId").is(documentId)
                        .orOperator(Criteria.where("accountId").is(null), Criteria.where("accountId").exists(false))),
                new Update().set("accountId", accountId).set("updatedAt", Instant.now()), "transactions").getModifiedCount();
        return ResponseEntity.ok(Map.of("updated", n));
    }

    /**
     * Marks this document's rows as reviewed ("looks right for all"). Rows without a date or direction, and rows
     * flagged possibleDuplicateOf, stay in the review queue because the user must decide on those.
     */
    @PostMapping("/{documentId}/approve")
    public ResponseEntity<Map<String, Object>> approve(Authentication authentication, @PathVariable String documentId) {
        String userId = authentication.getName();
        owned(userId, documentId);
        Query pending = new Query(Criteria.where("userId").is(userId).and("documentId").is(documentId)
                .and("needsReview").is(true).and("date").ne(null)
                .and("direction").in(List.of("DEBIT", "CREDIT")).and("possibleDuplicateOf").is(null));
        long n = mongo.updateMulti(pending, new Update().set("needsReview", false).set("updatedAt", Instant.now()),
                "transactions").getModifiedCount();
        long remaining = mongo.count(new Query(Criteria.where("userId").is(userId).and("documentId").is(documentId)
                .and("needsReview").is(true)), "transactions");
        return ResponseEntity.ok(Map.of("approved", n, "stillNeedReview", remaining));
    }

    private void owned(String userId, String documentId) {
        documents.findByIdAndUserId(documentId, userId).orElseThrow(() -> new ResourceNotFoundException("Document not found"));
    }
}
