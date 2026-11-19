
package in.expensetrackerapp.profile.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record ProfileRequest(

        @NotBlank(message = "Full name is required")
        String fullName,

        @NotNull(message = "Age is required")
        @Min(value = 1, message = "Age must be at least 1")
        @Max(value = 120, message = "Age must not exceed 120")
        Integer age,

        @NotBlank(message = "Employment is required")
        String employment,

        @NotNull(message = "Expected monthly income is required")
        @DecimalMin(value = "0.0")
        BigDecimal expectedMonthlyIncome,

        @NotEmpty(message = "Select at least one income source")
        List<@NotBlank String> incomeSources,

        @NotEmpty(message = "Select at least one bank")
        List<@NotBlank String> banks,

        @NotNull(message = "Opening balances are required")
        Map<String, @NotNull @DecimalMin("0.0") BigDecimal> openingBalances,

        @NotNull(message = "Monthly investment target is required")
        @DecimalMin(value = "0.0")
        BigDecimal monthlyInvestmentTarget,

        @NotBlank(message = "Risk tolerance is required")
        String riskTolerance

) {
}
