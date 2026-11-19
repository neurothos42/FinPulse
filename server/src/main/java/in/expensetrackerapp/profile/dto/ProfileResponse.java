
package in.expensetrackerapp.profile.dto;

import in.expensetrackerapp.profile.model.Profile;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ProfileResponse(

        String id,
        String fullName,
        Integer age,
        String employment,
        BigDecimal expectedMonthlyIncome,
        List<String> incomeSources,
        List<String> banks,
        Map<String, BigDecimal> openingBalances,
        BigDecimal monthlyInvestmentTarget,
        String riskTolerance,
        Instant createdAt,
        Instant updatedAt

) {

    public static ProfileResponse fromProfile(Profile profile) {
        return new ProfileResponse(
                profile.getId(),
                profile.getFullName(),
                profile.getAge(),
                profile.getEmployment(),
                profile.getExpectedMonthlyIncome(),
                profile.getIncomeSources(),
                profile.getBanks(),
                profile.getOpeningBalances(),
                profile.getMonthlyInvestmentTarget(),
                profile.getRiskTolerance(),
                profile.getCreatedAt(),
                profile.getUpdatedAt()
        );
    }
}
