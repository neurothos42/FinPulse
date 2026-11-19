
package in.expensetrackerapp.profile.service;

import in.expensetrackerapp.profile.dto.ProfileRequest;
import in.expensetrackerapp.profile.dto.ProfileResponse;

public interface ProfileService {

    ProfileResponse createProfile(String userId, ProfileRequest request);

    ProfileResponse getProfile(String userId);

    ProfileResponse updateProfile(String userId, ProfileRequest request);
}
