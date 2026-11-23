package in.expensetrackerapp.profile.service;

import in.expensetrackerapp.common.exception.BadRequestException;
import in.expensetrackerapp.common.exception.ResourceNotFoundException;
import in.expensetrackerapp.profile.dto.ProfileRequest;
import in.expensetrackerapp.profile.dto.ProfileResponse;
import in.expensetrackerapp.profile.model.UserProfile;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ProfileService {

    private static final Set<String> RISKS = Set.of("Conservative", "Moderate", "Aggressive");
    private static final List<String> ALLOCATION_KEYS = List.of("Essentials", "Savings", "Investments", "Lifestyle");

    private final MongoTemplate mongo;
    private final Clock clock;

    public ProfileService(MongoTemplate mongo, Clock clock) {
        this.mongo = mongo;
        this.clock = clock;
    }

    /** 404 = "not onboarded yet" (API_INTEGRATION_MAPPING section 3). */
    public ProfileResponse get(String userId) {
        UserProfile p = mongo.findById(userId, UserProfile.class);
        if (p == null) {
            throw new ResourceNotFoundException("Profile not found");
        }
        return ProfileResponse.from(p);
    }

    public ProfileResponse put(String userId, ProfileRequest r) {
        UserProfile p = mongo.findById(userId, UserProfile.class);
        if (p == null) {
            p = new UserProfile();
            p.setId(userId);
            p.setCreatedAt(clock.instant());
        }
        if (r.ageRange() != null) p.setAgeRange(r.ageRange());
        if (r.employment() != null) p.setEmployment(r.employment());
        if (r.incomeSources() != null) p.setIncomeSources(new ArrayList<>(r.incomeSources()));
        if (r.banks() != null) p.setBanks(new ArrayList<>(r.banks()));
        if (r.riskAnswer() != null) p.setRiskAnswer(r.riskAnswer());
        if (r.risk() != null) {
            if (!RISKS.contains(r.risk())) {
                throw new BadRequestException("risk must be Conservative, Moderate or Aggressive");
            }
            p.setRisk(r.risk());
        }
        if (r.monthlyIncome() != null) p.setMonthlyIncome(money(r.monthlyIncome(), "monthlyIncome"));
        if (r.salaryDay() != null) p.setSalaryDay(r.salaryDay());
        if (r.sipTarget() != null) p.setSipTarget(money(r.sipTarget(), "sipTarget"));
        if (r.savingsTarget() != null) p.setSavingsTarget(money(r.savingsTarget(), "savingsTarget"));
        if (r.allocation() != null) p.setAllocation(validAllocation(r.allocation()));
        if (r.darkMode() != null) p.setDarkMode(r.darkMode());
        if (r.onboarded() != null) p.setOnboarded(r.onboarded());
        if (r.readNotifications() != null) p.setReadNotifications(new ArrayList<>(r.readNotifications()));
        p.setUpdatedAt(clock.instant());
        return ProfileResponse.from(mongo.save(p));
    }

    /** The four buckets must all be present, 0-100 (decimals allowed, like the client) and total 100 (+-0.001). */
    static Map<String, Double> validAllocation(Map<String, Double> in) {
        if (in.size() != ALLOCATION_KEYS.size() || !in.keySet().containsAll(ALLOCATION_KEYS)) {
            throw new BadRequestException("allocation must contain exactly Essentials, Savings, Investments, Lifestyle");
        }
        double sum = 0;
        Map<String, Double> out = new LinkedHashMap<>();
        for (String key : ALLOCATION_KEYS) {
            Double v = in.get(key);
            if (v == null || v.isNaN() || v < 0 || v > 100) {
                throw new BadRequestException("allocation values must be between 0 and 100");
            }
            sum += v;
            out.put(key, v);
        }
        if (Math.abs(sum - 100) >= 0.001) {
            throw new BadRequestException("allocation must add up to 100");
        }
        return out;
    }

    private static BigDecimal money(BigDecimal v, String field) {
        try {
            return v.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new BadRequestException(field + " must have at most 2 decimal places");
        }
    }
}
