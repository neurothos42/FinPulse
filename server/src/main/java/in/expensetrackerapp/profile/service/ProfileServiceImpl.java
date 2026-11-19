
package in.expensetrackerapp.profile.service;

import in.expensetrackerapp.common.exception.BadRequestException;
import in.expensetrackerapp.common.exception.ConflictException;
import in.expensetrackerapp.common.exception.ResourceNotFoundException;
import in.expensetrackerapp.profile.dto.ProfileRequest;
import in.expensetrackerapp.profile.dto.ProfileResponse;
import in.expensetrackerapp.profile.model.Profile;
import in.expensetrackerapp.profile.repository.ProfileRepository;

import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.List;

@Service
public class ProfileServiceImpl implements ProfileService {

    private final ProfileRepository profileRepository;

    public ProfileServiceImpl(ProfileRepository profileRepository) {
        this.profileRepository = profileRepository;
    }

    private static final Set<String> EMPLOYMENTS = Set.of(
            "Student",
            "Salaried",
            "Self-employed",
            "Business owner",
            "Homemaker",
            "Other"
    );

    private static final Set<String> INCOME_SOURCES = Set.of(
            "Salary",
            "Freelance",
            "Business",
            "Rental",
            "Family support",
            "Other"
    );

    private static final Set<String> BANKS = Set.of(
            "State Bank of India",
            "HDFC Bank",
            "ICICI Bank",
            "Axis Bank",
            "Punjab National Bank",
            "Bank of Baroda",
            "Canara Bank",
            "Union Bank of India",
            "Bank of India",
            "Indian Bank",
            "Central Bank of India",
            "UCO Bank",
            "IDBI Bank",
            "Kotak Mahindra Bank",
            "IndusInd Bank",
            "IDFC FIRST Bank",
            "Yes Bank",
            "Federal Bank",
            "RBL Bank",
            "Bandhan Bank",
            "Karnataka Bank",
            "South Indian Bank",
            "DCB Bank",
            "AU Small Finance Bank",
            "Equitas Small Finance Bank",
            "Jana Small Finance Bank",
            "Ujjivan Small Finance Bank"
    );

    private static final Set<String> RISK_TOLERANCES = Set.of(
            "Sell immediately",
            "Wait and watch",
            "Invest more"
    );

    @Override
    public ProfileResponse createProfile(
            String userId,
            ProfileRequest request) {

        if (profileRepository.existsByUserId(userId)) {
            throw new ConflictException("Profile already exists for this user");
        }

        validateRequest(request);

        Profile profile = new Profile();
        profile.setUserId(userId);

        mapRequestToProfile(request, profile);

        return ProfileResponse.fromProfile(
                profileRepository.save(profile)
        );
    }

    @Override
    public ProfileResponse getProfile(String userId) {

        Profile profile = profileRepository.findByUserId(userId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Profile not found"));

        return ProfileResponse.fromProfile(profile);
    }

    @Override
    public ProfileResponse updateProfile(
            String userId,
            ProfileRequest request) {

        Profile profile = profileRepository.findByUserId(userId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Profile not found"));

        validateRequest(request);

        mapRequestToProfile(request, profile);

        // Keep the existing profile ID, user ID and createdAt.
        return ProfileResponse.fromProfile(
                profileRepository.save(profile)
        );
    }

    private void mapRequestToProfile(
            ProfileRequest request,
            Profile profile) {

        profile.setFullName(request.fullName().trim());
        profile.setAge(request.age());
        profile.setEmployment(request.employment());
        profile.setExpectedMonthlyIncome(request.expectedMonthlyIncome());

        profile.setIncomeSources(List.copyOf(request.incomeSources()));
        profile.setBanks(List.copyOf(request.banks()));

        profile.setOpeningBalances(Map.copyOf(request.openingBalances()));

        profile.setMonthlyInvestmentTarget(
                request.monthlyInvestmentTarget()
        );

        profile.setRiskTolerance(request.riskTolerance());
    }

    private void validateRequest(ProfileRequest request) {

        if (!EMPLOYMENTS.contains(request.employment())) {
            throw new BadRequestException("Invalid employment selection");
        }

        if (!INCOME_SOURCES.containsAll(request.incomeSources())) {
            throw new BadRequestException("Invalid income source selection");
        }

        if (!BANKS.containsAll(request.banks())) {
            throw new BadRequestException("Invalid bank selection");
        }

        if (!RISK_TOLERANCES.contains(request.riskTolerance())) {
            throw new BadRequestException("Invalid risk tolerance selection");
        }

        Set<String> selectedBanks = new HashSet<>(request.banks());

        if (selectedBanks.size() != request.banks().size()) {
            throw new BadRequestException("Duplicate banks are not allowed");
        }

        if (!request.openingBalances().keySet().equals(selectedBanks)) {
            throw new BadRequestException(
                    "Opening balance must be provided for every selected bank only"
            );
        }

        if (request.incomeSources().stream().distinct().count()
                != request.incomeSources().size()) {
            throw new BadRequestException("Duplicate income sources are not allowed");
        }
    }
}
