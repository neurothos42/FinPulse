package in.expensetrackerapp.profile.controller;

import in.expensetrackerapp.profile.dto.ProfileRequest;
import in.expensetrackerapp.profile.dto.ProfileResponse;
import in.expensetrackerapp.profile.service.ProfileService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping
    public ResponseEntity<ProfileResponse> get(Authentication authentication) {
        return ResponseEntity.ok(profileService.get(authentication.getName()));
    }

    @PutMapping
    public ResponseEntity<ProfileResponse> put(Authentication authentication, @Valid @RequestBody ProfileRequest request) {
        return ResponseEntity.ok(profileService.put(authentication.getName(), request));
    }
}
