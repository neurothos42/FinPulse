package in.expensetrackerapp.transaction.dto;

import in.expensetrackerapp.transaction.model.TransactionDirection;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Partial update (PATCH): a null field means "leave unchanged". Only these fields are editable;
 * ownership, source hash, document and job links cannot be changed (unknown JSON fields are ignored).
 */
public record TransactionUpdateRequest(
        @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "date must be in yyyy-MM-dd format")
        String date,

        @DecimalMin(value = "0.01", message = "amount must be positive")
        @Digits(integer = 13, fraction = 2, message = "amount must have at most 13 integer digits and 2 decimals")
        BigDecimal amount,

        TransactionDirection direction,

        @Size(min = 1, max = 200, message = "merchant must be between 1 and 200 characters")
        String merchant,

        @Size(max = 50, message = "category is too long")
        String category,

        @Size(max = 2000, message = "description must be at most 2000 characters")
        String description,

        Boolean needsReview,

        @Size(max = 24, message = "accountId is too long")
        String accountId,

        @Size(max = 30, message = "method is too long")
        String method,

        @Size(max = 40, message = "incomeSource is too long")
        String incomeSource,

        @Size(max = 2000, message = "notes must be at most 2000 characters")
        String notes) {

    /** Original 7-component shape; kept so existing callers keep compiling. */
    public TransactionUpdateRequest(String date, BigDecimal amount, TransactionDirection direction, String merchant,
                                    String category, String description, Boolean needsReview) {
        this(date, amount, direction, merchant, category, description, needsReview, null, null, null, null);
    }

    public boolean hasNoChanges() {
        return date == null && amount == null && direction == null && merchant == null
                && category == null && description == null && needsReview == null
                && accountId == null && method == null && incomeSource == null && notes == null;
    }
}
