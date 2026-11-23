package in.expensetrackerapp.transaction.service;

import in.expensetrackerapp.transaction.model.TransactionDirection;

import java.time.LocalDate;

/** Optional list filters; null means "no filter". Dates are inclusive. */
public record TransactionFilter(
        LocalDate from,
        LocalDate to,
        String category,
        TransactionDirection direction,
        Boolean needsReview,
        String documentId,
        String jobId,
        String q,
        String accountId,
        String type) {

    /** Original 5-component shape; kept so existing callers keep compiling. */
    public TransactionFilter(LocalDate from, LocalDate to, String category,
                             TransactionDirection direction, Boolean needsReview) {
        this(from, to, category, direction, needsReview, null, null, null, null, null);
    }

    public static TransactionFilter none() {
        return new TransactionFilter(null, null, null, null, null);
    }
}
