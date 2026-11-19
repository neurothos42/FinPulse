
package in.expensetrackerapp.profile.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Document(collection = "profiles")
public class Profile {

    @Id
    private String id;

    @Indexed(unique = true)
    private String userId;

    private String fullName;
    private Integer age;
    private String employment;

    private BigDecimal expectedMonthlyIncome;

    private List<String> incomeSources;

    private List<String> banks;

    // Bank name -> opening balance
    private Map<String, BigDecimal> openingBalances;

    private BigDecimal monthlyInvestmentTarget;

    private String riskTolerance;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    public Profile() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public Integer getAge() {
        return age;
    }

    public void setAge(Integer age) {
        this.age = age;
    }

    public String getEmployment() {
        return employment;
    }

    public void setEmployment(String employment) {
        this.employment = employment;
    }

    public BigDecimal getExpectedMonthlyIncome() {
        return expectedMonthlyIncome;
    }

    public void setExpectedMonthlyIncome(BigDecimal expectedMonthlyIncome) {
        this.expectedMonthlyIncome = expectedMonthlyIncome;
    }

    public List<String> getIncomeSources() {
        return incomeSources;
    }

    public void setIncomeSources(List<String> incomeSources) {
        this.incomeSources = incomeSources;
    }

    public List<String> getBanks() {
        return banks;
    }

    public void setBanks(List<String> banks) {
        this.banks = banks;
    }

    public Map<String, BigDecimal> getOpeningBalances() {
        return openingBalances;
    }

    public void setOpeningBalances(Map<String, BigDecimal> openingBalances) {
        this.openingBalances = openingBalances;
    }

    public BigDecimal getMonthlyInvestmentTarget() {
        return monthlyInvestmentTarget;
    }

    public void setMonthlyInvestmentTarget(BigDecimal monthlyInvestmentTarget) {
        this.monthlyInvestmentTarget = monthlyInvestmentTarget;
    }

    public String getRiskTolerance() {
        return riskTolerance;
    }

    public void setRiskTolerance(String riskTolerance) {
        this.riskTolerance = riskTolerance;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
