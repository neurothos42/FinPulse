
package in.expensetrackerapp.profile.controller;

import in.expensetrackerapp.profile.dto.ProfileRequest;
import in.expensetrackerapp.profile.dto.ProfileResponse;
import in.expensetrackerapp.profile.service.ProfileService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

@RestController
@RequestMapping("/api/profiles")
public class ProfileController {

    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    // CREATE PROFILE
    @PostMapping
    public ResponseEntity<ProfileResponse> createProfile(
            Principal principal,
            @Valid @RequestBody ProfileRequest request) {

        ProfileResponse response = profileService.createProfile(
                principal.getName(),
                request
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // GET LOGGED-IN USER PROFILE
    @GetMapping("/me")
    public ResponseEntity<ProfileResponse> getProfile(
            Principal principal) {

        ProfileResponse response = profileService.getProfile(
                principal.getName()
        );

        return ResponseEntity.ok(response);
    }

    // UPDATE PROFILE
    @PutMapping("/me")
    public ResponseEntity<ProfileResponse> updateProfile(
            Principal principal,
            @Valid @RequestBody ProfileRequest request) {

        ProfileResponse response = profileService.updateProfile(
                principal.getName(),
                request
        );

        return ResponseEntity.ok(response);
    }
}
