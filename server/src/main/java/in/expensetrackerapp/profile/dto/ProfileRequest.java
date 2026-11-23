package in.expensetrackerapp.profile.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** PUT body. Mirrors the client's profileSchema; null fields keep their current/default value. */
public record ProfileRequest(
        @Size(max = 20) String ageRange,
        @Size(max = 30) String employment,
        @Size(max = 6, message = "at most 6 income sources") List<@Size(max = 40) String> incomeSources,
        @Size(max = 30, message = "at most 30 banks") List<@Size(min = 1, max = 80) String> banks,
        @Size(max = 120) String riskAnswer,
        @Size(max = 20) String risk,
        @DecimalMin(value = "0", message = "monthlyIncome must be >= 0")
        @DecimalMax(value = "1000000000000", message = "monthlyIncome is too large") BigDecimal monthlyIncome,
        @Min(value = 1, message = "salaryDay must be 1-31") @Max(value = 31, message = "salaryDay must be 1-31") Integer salaryDay,
        @DecimalMin("0") @DecimalMax("1000000000000") BigDecimal sipTarget,
        @DecimalMin("0") @DecimalMax("1000000000000") BigDecimal savingsTarget,
        Map<String, Double> allocation,
        Boolean darkMode,
        Boolean onboarded,
        @Size(max = 500, message = "too many read notifications") List<@Size(max = 100) String> readNotifications) {
}
