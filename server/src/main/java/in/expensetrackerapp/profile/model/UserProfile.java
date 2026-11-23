package in.expensetrackerapp.profile.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 1:1 with users; {@code _id} is the userId. Name is NOT stored here (users.name is the single source). */
@Document(collection = "user_profiles")
public class UserProfile {

    @Id
    private String id;
    private String ageRange;
    private String employment;
    private List<String> incomeSources = new ArrayList<>();
    private List<String> banks = new ArrayList<>();
    private String riskAnswer;
    private String risk = "Moderate";

    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal monthlyIncome = BigDecimal.ZERO.setScale(2);
    private int salaryDay = 1;
    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal sipTarget = BigDecimal.ZERO.setScale(2);
    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal savingsTarget;

    private Map<String, Double> allocation = defaultAllocation();
    private boolean darkMode;
    private boolean onboarded;
    private List<String> readNotifications = new ArrayList<>();
    private Instant createdAt;
    private Instant updatedAt;

    public static Map<String, Double> defaultAllocation() {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("Essentials", 50.0);
        m.put("Savings", 20.0);
        m.put("Investments", 20.0);
        m.put("Lifestyle", 10.0);
        return m;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getAgeRange() { return ageRange; }
    public void setAgeRange(String ageRange) { this.ageRange = ageRange; }
    public String getEmployment() { return employment; }
    public void setEmployment(String employment) { this.employment = employment; }
    public List<String> getIncomeSources() { return incomeSources; }
    public void setIncomeSources(List<String> incomeSources) { this.incomeSources = incomeSources; }
    public List<String> getBanks() { return banks; }
    public void setBanks(List<String> banks) { this.banks = banks; }
    public String getRiskAnswer() { return riskAnswer; }
    public void setRiskAnswer(String riskAnswer) { this.riskAnswer = riskAnswer; }
    public String getRisk() { return risk; }
    public void setRisk(String risk) { this.risk = risk; }
    public BigDecimal getMonthlyIncome() { return monthlyIncome; }
    public void setMonthlyIncome(BigDecimal monthlyIncome) { this.monthlyIncome = monthlyIncome; }
    public int getSalaryDay() { return salaryDay; }
    public void setSalaryDay(int salaryDay) { this.salaryDay = salaryDay; }
    public BigDecimal getSipTarget() { return sipTarget; }
    public void setSipTarget(BigDecimal sipTarget) { this.sipTarget = sipTarget; }
    public BigDecimal getSavingsTarget() { return savingsTarget; }
    public void setSavingsTarget(BigDecimal savingsTarget) { this.savingsTarget = savingsTarget; }
    public Map<String, Double> getAllocation() { return allocation; }
    public void setAllocation(Map<String, Double> allocation) { this.allocation = allocation; }
    public boolean isDarkMode() { return darkMode; }
    public void setDarkMode(boolean darkMode) { this.darkMode = darkMode; }
    public boolean isOnboarded() { return onboarded; }
    public void setOnboarded(boolean onboarded) { this.onboarded = onboarded; }
    public List<String> getReadNotifications() { return readNotifications; }
    public void setReadNotifications(List<String> readNotifications) { this.readNotifications = readNotifications; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
