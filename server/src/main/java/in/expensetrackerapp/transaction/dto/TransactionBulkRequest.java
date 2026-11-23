package in.expensetrackerapp.transaction.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record TransactionBulkRequest(
        @NotEmpty(message = "transactions must not be empty")
        @Size(max = 2000, message = "at most 2000 transactions per request")
        List<@Valid TransactionCreateRequest> transactions) {
}
