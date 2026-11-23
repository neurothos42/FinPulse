package in.expensetrackerapp.account;

import in.expensetrackerapp.common.exception.BadRequestException;
import in.expensetrackerapp.document.model.UploadedDocument;
import in.expensetrackerapp.document.service.GridFsStorageService;
import in.expensetrackerapp.entity.EntitySchemas;
import in.expensetrackerapp.job.model.JobStatus;
import in.expensetrackerapp.job.model.ProcessingJob;
import in.expensetrackerapp.profile.model.UserProfile;
import in.expensetrackerapp.transaction.model.Transaction;
import in.expensetrackerapp.user.dto.UserResponse;
import in.expensetrackerapp.user.model.User;
import in.expensetrackerapp.user.repository.UserRepository;
import in.expensetrackerapp.common.exception.ConflictException;
import in.expensetrackerapp.common.exception.ResourceNotFoundException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import in.expensetrackerapp.auth.security.JwtService;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Name change and privacy operations (M1 PATCH /users/me, M10 delete-my-data). */
@Service
public class AccountService {

    private final UserRepository userRepository;
    private final MongoTemplate mongo;
    private final GridFsStorageService storage;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    private PasswordEncoder passwordEncoder;
    @org.springframework.beans.factory.annotation.Autowired
    private JwtService jwtService;

    public AccountService(UserRepository userRepository, MongoTemplate mongo, GridFsStorageService storage, Clock clock) {
        this.userRepository = userRepository;
        this.mongo = mongo;
        this.storage = storage;
        this.clock = clock;
    }

    public UserResponse updateName(String userId, String name) {
        String trimmed = name == null ? "" : name.strip();
        if (trimmed.length() < 2 || trimmed.length() > 50) {
            throw new BadRequestException("name must be between 2 and 50 characters");
        }
        User user = userRepository.findById(userId).orElseThrow(() -> new ResourceNotFoundException("User not found"));
        user.setName(trimmed);
        user.setUpdatedAt(clock.instant());
        return UserResponse.fromUser(userRepository.save(user));
    }

    /**
     * Deletes everything the user owns (files in GridFS, documents, jobs, reports, transactions, entities,
     * profile) but keeps the login. Refuses while a document is being processed, so the worker is never
     * left writing rows for a user whose data was just erased.
     */
    public Map<String, Long> deleteAllData(String userId) {
        long running = mongo.count(new Query(Criteria.where("userId").is(userId)
                .and("status").is(JobStatus.PROCESSING)), ProcessingJob.class);
        if (running > 0) {
            throw new ConflictException("A document is being processed. Try again when it has finished");
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        Query mine = new Query(Criteria.where("userId").is(userId));

        List<UploadedDocument> docs = mongo.find(mine, UploadedDocument.class);
        docs.forEach(d -> storage.deleteQuietly(d.getGridFsFileId()));
        counts.put("documents", mongo.remove(mine, UploadedDocument.class).getDeletedCount());
        counts.put("jobs", mongo.remove(mine, ProcessingJob.class).getDeletedCount());
        counts.put("voiceEntries", mongo.remove(mine, "voice_entries").getDeletedCount());
        counts.put("reports", mongo.remove(mine, "reports").getDeletedCount());
        counts.put("transactions", mongo.remove(mine, Transaction.class).getDeletedCount());
        for (EntitySchemas.Schema s : EntitySchemas.all()) {
            counts.put(s.collection(), mongo.remove(mine, s.collection()).getDeletedCount());
        }
        counts.put("profile", mongo.remove(new Query(Criteria.where("_id").is(userId)), UserProfile.class).getDeletedCount());
        return counts;
    }

    public void deleteAccount(String userId) {
        deleteAllData(userId);
        userRepository.deleteById(userId);
    }

    /** Verifies the current password, stores the new one, revokes older tokens and returns a fresh token. */
    public Map<String, Object> changePassword(String userId, String current, String next) {
        if (next == null || next.length() < 8 || next.length() > 100
                || next.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BadRequestException("New password must be 8-100 characters (at most 72 bytes)");
        }
        User user = userRepository.findById(userId).orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (current == null || !passwordEncoder.matches(current, user.getPasswordHash())) {
            throw new in.expensetrackerapp.common.exception.InvalidCredentialsException("Current password is incorrect");
        }
        Instant now = clock.instant();
        user.setPasswordHash(passwordEncoder.encode(next));
        user.setTokenVersion(user.getTokenVersion() + 1);   // revokes every older token
        user.setUpdatedAt(now);
        userRepository.save(user);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accessToken", jwtService.generateToken(user));
        body.put("tokenType", "Bearer");
        body.put("expiresIn", jwtService.getExpirationInSeconds());
        return body;
    }

    /** Revokes every token issued so far (the JWT itself is stateless; the user's tokenVersion is bumped). */
    public void logout(String userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> new ResourceNotFoundException("User not found"));
        user.setTokenVersion(user.getTokenVersion() + 1);
        userRepository.save(user);
    }
}
