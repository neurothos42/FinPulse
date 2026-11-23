package in.expensetrackerapp.transaction.dto;

import in.expensetrackerapp.transaction.model.TransactionDirection;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** Manual / voice / receipt-draft / import row. Rules mirror TransactionUpdateRequest. */
public record TransactionCreateRequest(
        @NotNull(message = "date is required")
        @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "date must be in yyyy-MM-dd format")
        String date,

        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.01", message = "amount must be positive")
        @Digits(integer = 13, fraction = 2, message = "amount must have at most 13 integer digits and 2 decimals")
        BigDecimal amount,

        TransactionDirection direction,

        @NotBlank(message = "merchant is required")
        @Size(max = 200, message = "merchant must be between 1 and 200 characters")
        String merchant,

        @Size(max = 50, message = "category is too long")
        String category,

        @Size(max = 2000, message = "description must be at most 2000 characters")
        String description,

        @Size(max = 24, message = "accountId is too long")
        String accountId,

        @Size(max = 24, message = "toAccountId is too long")
        String toAccountId,

        @Size(max = 30, message = "method is too long")
        String method,

        @Size(max = 20, message = "origin is too long")
        String origin,

        @Pattern(regexp = "^[A-Za-z]{3}$", message = "currency must be a 3-letter ISO code")
        String currency,

        @Size(max = 40, message = "incomeSource is too long")
        String incomeSource,

        @Size(max = 2000, message = "notes must be at most 2000 characters")
        String notes,

        @Size(max = 200, message = "clientRequestId is too long")
        String clientRequestId,

        /** Marks a CREDIT as refund/cashback so the dashboard reports it separately (DATA_CONSISTENCY M4). */
        Boolean refund,

        /** Client vocabulary: income | expense | investment | transfer. Overrides direction/category when present. */
        @Pattern(regexp = "income|expense|investment|transfer", message = "type must be income, expense, investment or transfer")
        String type) {

    /** Original 15-component shape; kept so existing callers keep compiling. */
    public TransactionCreateRequest(String date, BigDecimal amount, TransactionDirection direction, String merchant,
                                    String category, String description, String accountId, String toAccountId,
                                    String method, String origin, String currency, String incomeSource, String notes,
                                    String clientRequestId, Boolean refund) {
        this(date, amount, direction, merchant, category, description, accountId, toAccountId, method, origin,
                currency, incomeSource, notes, clientRequestId, refund, null);
    }
}
