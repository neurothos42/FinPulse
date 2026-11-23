package in.expensetrackerapp.transaction.dto;

import in.expensetrackerapp.transaction.model.Transaction;
import in.expensetrackerapp.transaction.model.TransactionDirection;

import java.math.BigDecimal;
import java.time.Instant;

/** API view. userId and sourceHash are internal and never returned. New fields are nullable. */
public record TransactionResponse(
        String id,
        String documentId,
        String jobId,
        String date,
        BigDecimal amount,
        String currency,
        TransactionDirection direction,
        String merchant,
        String category,
        String description,
        String source,
        String referenceNumber,
        BigDecimal balance,
        boolean needsReview,
        String possibleDuplicateOf,
        Instant createdAt,
        Instant updatedAt,
        Double categoryConfidence,
        String categorySource,
        Double ocrConfidence,
        String accountId,
        String toAccountId,
        String method,
        String origin,
        String incomeSource,
        String notes,
        String type,
        String clientCategory,
        String billId,
        String billDueDate,
        String clientRequestId) {

    /** Original 17-component shape; kept so existing callers keep compiling. */
    public TransactionResponse(String id, String documentId, String jobId, String date, BigDecimal amount,
                               String currency, TransactionDirection direction, String merchant, String category,
                               String description, String source, String referenceNumber, BigDecimal balance,
                               boolean needsReview, String possibleDuplicateOf, Instant createdAt, Instant updatedAt) {
        this(id, documentId, jobId, date, amount, currency, direction, merchant, category, description, source,
                referenceNumber, balance, needsReview, possibleDuplicateOf, createdAt, updatedAt,
                null, null, null, null, null, null, null, null, null,
                deriveType(direction, category), null, null, null, null);
    }

    public static TransactionResponse fromTransaction(Transaction t) {
        return new TransactionResponse(
                t.getId(), t.getDocumentId(), t.getJobId(), t.getDate(), t.getAmount(), t.getCurrency(),
                t.getDirection(), t.getMerchant(), t.getCategory(), t.getDescription(), t.getSource(),
                t.getReferenceNumber(), t.getBalance(), t.isNeedsReview(), t.getPossibleDuplicateOf(),
                t.getCreatedAt(), t.getUpdatedAt(),
                t.getCategoryConfidence(), t.getCategorySource(), t.getOcrConfidence(),
                t.getAccountId(), t.getToAccountId(), t.getMethod(), originOf(t), t.getIncomeSource(),
                t.getNotes(), deriveType(t.getDirection(), t.getCategory()), t.getClientCategory(),
                t.getBillId(), t.getBillDueDate(), t.getClientRequestId());
    }

    /** Worker rows carry no origin: report DOCUMENT for them; migrated rows (no documentId) stay null. */
    private static String originOf(Transaction t) {
        if (t.getOrigin() != null) return t.getOrigin();
        return t.getDocumentId() != null ? "DOCUMENT" : null;
    }

    /** Derived, never stored (DATA_CONSISTENCY N2): transfer | investment | income | expense | null (UNKNOWN). */
    public static String deriveType(TransactionDirection direction, String category) {
        if ("Transfer".equals(category)) return "transfer";
        if (direction == null || direction == TransactionDirection.UNKNOWN) return null;
        if (direction == TransactionDirection.DEBIT) return "Investment".equals(category) ? "investment" : "expense";
        return "income";
    }
}
