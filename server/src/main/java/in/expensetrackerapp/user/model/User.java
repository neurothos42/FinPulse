package in.expensetrackerapp.user.model;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "users")
public class User {

    @Id
    private String id;

    private String name;

    @Indexed(unique = true)
    private String email;

    private String passwordHash;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    /** Tokens issued before this instant are rejected (set by logout / password change). */
    private Instant tokensValidAfter;

    /** Bumped by logout / password change; a token is valid only while its "tv" claim equals this value. */
    private int tokenVersion;

    public User() {
    }

    public User(String name, String email, String passwordHash) {
        this.name = name;
        this.email = email != null ? email.toLowerCase().trim() : null;
        this.passwordHash = passwordHash;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public User(String id, String name, String email, String passwordHash, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.name = name;
        this.email = email != null ? email.toLowerCase().trim() : null;
        this.passwordHash = passwordHash;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email != null ? email.toLowerCase().trim() : null;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
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

    public Instant getTokensValidAfter() { return tokensValidAfter; }

    public void setTokensValidAfter(Instant tokensValidAfter) { this.tokensValidAfter = tokensValidAfter; }

    public int getTokenVersion() { return tokenVersion; }

    public void setTokenVersion(int tokenVersion) { this.tokenVersion = tokenVersion; }
}
