package in.expensetrackerapp.account;

import in.expensetrackerapp.user.dto.UserResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/users/me")
public class AccountController {

    public record NameRequest(@NotBlank(message = "name is required")
                              @Size(min = 2, max = 50, message = "name must be between 2 and 50 characters") String name) {
    }

    public record PasswordRequest(@NotBlank(message = "currentPassword is required") String currentPassword,
                                  @NotBlank(message = "newPassword is required")
                                  @Size(min = 8, max = 100, message = "newPassword must be between 8 and 100 characters") String newPassword) {
    }

    private final AccountService accountService;
    private final MigrationService migrationService;
    private final in.expensetrackerapp.user.repository.UserRepository userRepository;

    public AccountController(AccountService accountService, MigrationService migrationService,
                             in.expensetrackerapp.user.repository.UserRepository userRepository) {
        this.accountService = accountService;
        this.migrationService = migrationService;
        this.userRepository = userRepository;
    }

    /** Everything the user owns as JSON (Privacy "export"). */
    @org.springframework.web.bind.annotation.GetMapping("/export")
    public ResponseEntity<Map<String, Object>> export(Authentication authentication) {
        var user = userRepository.findById(authentication.getName()).orElseThrow();
        Map<String, Object> u = new java.util.LinkedHashMap<>();
        u.put("id", user.getId());
        u.put("name", user.getName());
        u.put("email", user.getEmail());
        return ResponseEntity.ok(migrationService.export(authentication.getName(), u));
    }

    /** One-shot import of the app's local backup (AppState v1). Safe to repeat. */
    @org.springframework.web.bind.annotation.PostMapping("/import")
    public ResponseEntity<Map<String, Object>> importLocal(Authentication authentication,
                                                           @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(migrationService.importLocal(authentication.getName(), body));
    }

    @PatchMapping
    public ResponseEntity<UserResponse> rename(Authentication authentication, @Valid @RequestBody NameRequest request) {
        return ResponseEntity.ok(accountService.updateName(authentication.getName(), request.name()));
    }

    @org.springframework.web.bind.annotation.PutMapping("/password")
    public ResponseEntity<Map<String, Object>> changePassword(Authentication authentication,
                                                              @Valid @RequestBody PasswordRequest request) {
        return ResponseEntity.ok(accountService.changePassword(authentication.getName(),
                request.currentPassword(), request.newPassword()));
    }

    @org.springframework.web.bind.annotation.PostMapping("/logout")
    public ResponseEntity<Void> logout(Authentication authentication) {
        accountService.logout(authentication.getName());
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    @DeleteMapping("/data")
    public ResponseEntity<Map<String, Long>> deleteData(Authentication authentication) {
        return ResponseEntity.ok(accountService.deleteAllData(authentication.getName()));
    }

    @DeleteMapping
    public ResponseEntity<Void> deleteAccount(Authentication authentication) {
        accountService.deleteAccount(authentication.getName());
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
