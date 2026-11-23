package in.expensetrackerapp.profile.dto;

import in.expensetrackerapp.profile.model.UserProfile;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record ProfileResponse(
        String ageRange, String employment, List<String> incomeSources, List<String> banks, String riskAnswer,
        String risk, BigDecimal monthlyIncome, int salaryDay, BigDecimal sipTarget, BigDecimal savingsTarget,
        Map<String, Double> allocation, boolean darkMode, boolean onboarded, List<String> readNotifications) {

    public static ProfileResponse from(UserProfile p) {
        return new ProfileResponse(p.getAgeRange(), p.getEmployment(), p.getIncomeSources(), p.getBanks(),
                p.getRiskAnswer(), p.getRisk(), p.getMonthlyIncome(), p.getSalaryDay(), p.getSipTarget(),
                p.getSavingsTarget(), p.getAllocation(), p.isDarkMode(), p.isOnboarded(), p.getReadNotifications());
    }
}
