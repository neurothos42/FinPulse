package in.expensetrackerapp.transaction.dto;

import java.util.List;

public record TransactionBulkResponse(
        List<TransactionResponse> created,
        int skippedDuplicates,
        List<RowError> errors) {

    public record RowError(int index, String message) {
    }
}
