package in.expensetrackerapp.integration;

import com.jayway.jsonpath.JsonPath;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import in.expensetrackerapp.testsupport.IntegrationTestSupport;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The real application (security filter chain, controllers, services, Spring Data, GridFS, auto-created
 * indexes) against a real MongoDB, driven over MockMvc. Also inspects the raw documents Spring writes, which
 * is what the Python worker reads.
 *
 * Runs only when TEST_MONGODB_URI is set (see IntegrationTestSupport for the production-safety guard) and uses
 * a random throw-away database that is dropped afterwards.
 */
@EnabledIfEnvironmentVariable(named = IntegrationTestSupport.ENV, matches = ".+")
@SpringBootTest(properties = {
        "spring.servlet.multipart.max-file-size=2KB",
        "spring.servlet.multipart.max-request-size=6KB",
        "spring.data.mongodb.auto-index-creation=true",
        "application.jwt.expiration-ms=3600000",
        "application.jwt.issuer=expensetrackerapp"
})
@AutoConfigureMockMvc
class FullStackIntegrationTest {

    private static final String DATABASE = IntegrationTestSupport.randomDatabaseName();
    private static final byte[] JWT_KEY = randomBytes(48);
    private static final String PASSWORD = "Str0ng-Password!";
    private static final byte[] PDF = "%PDF-1.4\n1 0 obj<<>>endobj\n".getBytes(StandardCharsets.US_ASCII);
    private static final Path WORKER_FIXTURE = Path.of("..", "pipelines", "financial_transaction_extraction",
            "contracts", "worker_transaction.json");

    private static MongoClient mongo;

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", IntegrationTestSupport::uri);
        registry.add("spring.mongodb.database", () -> DATABASE);
        registry.add("application.jwt.secret", () -> Base64.getEncoder().encodeToString(JWT_KEY));
    }

    @BeforeAll
    static void connect() {
        mongo = IntegrationTestSupport.client();
    }

    @AfterAll
    static void cleanUp() {
        mongo.getDatabase(DATABASE).drop();
        mongo.close();
    }

    private static byte[] randomBytes(int n) {
        byte[] bytes = new byte[n];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private static MongoDatabase db() {
        return mongo.getDatabase(DATABASE);
    }

    private static MongoCollection<Document> collection(String name) {
        return db().getCollection(name);
    }

    // ------------------------------------------------------------------ helpers
    private record Account(String id, String email, String token) {
        String bearer() {
            return "Bearer " + token;
        }
    }

    private record Upload(String documentId, String jobId) {
    }

    private Account newAccount() throws Exception {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        String registered = mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("Test User", email)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String loggedIn = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new Account(JsonPath.read(registered, "$.id"), email, JsonPath.read(loggedIn, "$.accessToken"));
    }

    private static String credentials(String name, String email) {
        return "{\"name\":\"" + name + "\",\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}";
    }

    private Upload uploadPdf(Account account) throws Exception {
        String body = mockMvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", "statement.pdf", "application/pdf", PDF))
                        .header("Authorization", account.bearer()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new Upload(JsonPath.read(body, "$.id"), JsonPath.read(body, "$.jobId"));
    }

    private static String forgeToken(String userId, Date expiresAt, byte[] key) {
        return Jwts.builder().subject(userId).issuer("expensetrackerapp")
                .issuedAt(new Date(System.currentTimeMillis() - 7_200_000)).expiration(expiresAt)
                .signWith(Keys.hmacShaKeyFor(key)).compact();
    }

    /** Documents exactly as the Python worker writes them (shared contract fixture), owned by `account`. */
    private List<Document> seedWorkerTransactions(Account account, Upload upload) throws Exception {
        List<Document> docs = new ArrayList<>(Document.parse(Files.readString(WORKER_FIXTURE))
                .getList("transactions", Document.class));
        for (int i = 0; i < docs.size(); i++) {
            Document d = docs.get(i);
            d.put("_id", new ObjectId());
            d.put("userId", account.id());
            d.put("documentId", upload.documentId());
            d.put("jobId", upload.jobId());
            d.put("sourceHash", "hash-" + i + "-" + UUID.randomUUID());
        }
        collection("transactions").insertMany(docs);
        return docs;
    }

    private static long count(String collection) {
        return collection(collection).countDocuments();
    }

    // ------------------------------------------------------------------ platform
    @Test
    void healthEndpointIsPublicAndReportsUp() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void indexesDeclaredInCodeExistInTheDatabase() {
        List<String> users = indexNames("users");
        assertThat(collection("users").listIndexes().into(new ArrayList<>()))
                .anySatisfy(index -> {
                    assertThat(((Document) index.get("key")).keySet()).containsExactly("email");
                    assertThat(index.getBoolean("unique")).isTrue();
                });
        assertThat(users).isNotEmpty();
        assertThat(indexNames("uploaded_documents")).contains("userId_createdAt");
        assertThat(indexNames("processing_jobs")).contains("status_availableAt", "userId_createdAt", "leaseUntil_idx");
        assertThat(indexNames("transactions")).contains("userId_sourceHash_unique", "userId_date",
                "userId_category_date", "userId_direction_date", "userId_needsReview");
        assertThat(collection("transactions").listIndexes().into(new ArrayList<>()))
                .filteredOn(i -> "userId_sourceHash_unique".equals(i.getString("name")))
                .singleElement().satisfies(i -> assertThat(i.getBoolean("unique")).isTrue());
    }

    private static List<String> indexNames(String collection) {
        List<String> names = new ArrayList<>();
        collection(collection).listIndexes().forEach(index -> names.add(index.getString("name")));
        return names;
    }

    // ------------------------------------------------------------------ authentication
    @Test
    void registrationHashesThePasswordAndTreatsEmailCaseAsTheSameAccount() throws Exception {
        String local = "Mixed.Case-" + UUID.randomUUID();
        String body = mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("Mixed Case", local + "@Example.com")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("password").doesNotContain("$2");
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("Other", local.toLowerCase() + "@example.com")))
                .andExpect(status().isConflict());

        Document stored = collection("users").find(new Document("email", local.toLowerCase() + "@example.com")).first();
        assertThat(stored).as("email is stored normalised").isNotNull();
        assertThat(stored.getString("passwordHash")).startsWith("$2").doesNotContain(PASSWORD);
    }

    @Test
    void wrongPasswordAndUnknownUserGetTheSameResponse() throws Exception {
        Account account = newAccount();
        String wrong = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + account.email() + "\",\"password\":\"Wrong-Password1\"}"))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        String unknown = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody-" + UUID.randomUUID() + "@example.com\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();

        assertThat((String) JsonPath.read(wrong, "$.message")).isEqualTo(JsonPath.read(unknown, "$.message"));
    }

    @Test
    void everyProtectedEndpointRejectsMissingTamperedForgedAndExpiredTokens() throws Exception {
        Account account = newAccount();
        String tampered = account.token().substring(0, account.token().length() - 3)
                + (account.token().endsWith("AAA") ? "BBB" : "AAA");
        String wrongKey = forgeToken(account.id(), new Date(System.currentTimeMillis() + 3_600_000), randomBytes(48));
        String expired = forgeToken(account.id(), new Date(System.currentTimeMillis() - 3_600_000), JWT_KEY);
        String valid = forgeToken(account.id(), new Date(System.currentTimeMillis() + 3_600_000), JWT_KEY);

        for (String path : List.of("/api/users/me", "/api/documents", "/api/transactions", "/api/dashboard/summary",
                "/api/jobs/" + new ObjectId().toHexString())) {
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
            for (String bad : List.of("garbage", tampered, wrongKey, expired)) {
                mockMvc.perform(get(path).header("Authorization", "Bearer " + bad)).andExpect(status().isUnauthorized());
            }
        }
        // sanity: a correctly signed, unexpired token built the same way IS accepted
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + valid)).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ documents, GridFS, jobs
    @Test
    void uploadStoresTheFileInGridFsCreatesTheJobAndWritesTheTypesTheWorkerReads() throws Exception {
        Account account = newAccount();
        long files = count("fs.files");

        Upload upload = uploadPdf(account);

        assertThat(count("fs.files")).isEqualTo(files + 1);
        Document gridFile = collection("fs.files").find().sort(new Document("uploadDate", -1)).first();
        assertThat(gridFile.get("metadata", Document.class).getString("userId")).isEqualTo(account.id());

        Document stored = collection("uploaded_documents").find(new Document("_id", new ObjectId(upload.documentId()))).first();
        assertThat(stored).as("document _id must be an ObjectId (the worker looks it up that way)").isNotNull();
        assertThat(stored.getString("userId")).isEqualTo(account.id());
        assertThat(stored.get("gridFsFileId")).isInstanceOf(String.class);
        assertThat(stored.getString("gridFsFileId")).isEqualTo(gridFile.getObjectId("_id").toHexString());
        assertThat(stored.getString("status")).isEqualTo("UPLOADED");
        assertThat(stored.getString("sha256")).hasSize(64);

        Document job = collection("processing_jobs").find(new Document("_id", new ObjectId(upload.jobId()))).first();
        assertThat(job).as("job _id must be an ObjectId").isNotNull();
        assertThat(job.getString("status")).isEqualTo("PENDING");
        assertThat(job.get("documentId")).isInstanceOf(String.class).isEqualTo(upload.documentId());
        assertThat(job.getString("gridFsFileId")).isEqualTo(stored.getString("gridFsFileId"));
        assertThat(job.get("attempts")).isEqualTo(0);
        assertThat(job.get("remainingAttempts")).isEqualTo(3);
        assertThat(job.get("availableAt")).isInstanceOf(Date.class);

        mockMvc.perform(get("/api/jobs/" + upload.jobId()).header("Authorization", account.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.documentId").value(upload.documentId()));
    }

    @Test
    void spoofedOversizedAndEmptyUploadsAreRejectedAndLeaveNothingBehind() throws Exception {
        Account account = newAccount();
        long files = count("fs.files"), docs = count("uploaded_documents"), jobs = count("processing_jobs");

        mockMvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", "invoice.pdf", "application/pdf", "MZ\u0090\u0000\u0003".getBytes(StandardCharsets.ISO_8859_1)))
                        .header("Authorization", account.bearer()))
                .andExpect(status().isUnsupportedMediaType());
        byte[] big = new byte[5 * 1024];
        System.arraycopy(PDF, 0, big, 0, PDF.length);
        mockMvc.perform(multipart("/api/documents").file(new MockMultipartFile("file", "big.pdf", "application/pdf", big))
                        .header("Authorization", account.bearer()))
                .andExpect(status().is(413));
        mockMvc.perform(multipart("/api/documents").file(new MockMultipartFile("file", "e.pdf", "application/pdf", new byte[0]))
                        .header("Authorization", account.bearer()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(multipart("/api/documents").header("Authorization", account.bearer()))
                .andExpect(status().isBadRequest());

        assertThat(count("fs.files")).isEqualTo(files);
        assertThat(count("uploaded_documents")).isEqualTo(docs);
        assertThat(count("processing_jobs")).isEqualTo(jobs);
    }

    @Test
    void usersCannotSeeDownloadOrDeleteEachOthersDocumentsAndJobs() throws Exception {
        Account owner = newAccount(), other = newAccount();
        Upload upload = uploadPdf(owner);
        String base = "/api/documents/" + upload.documentId();

        mockMvc.perform(get(base).header("Authorization", other.bearer())).andExpect(status().isNotFound());
        mockMvc.perform(get(base + "/download").header("Authorization", other.bearer())).andExpect(status().isNotFound());
        mockMvc.perform(delete(base).header("Authorization", other.bearer())).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/jobs/" + upload.jobId()).header("Authorization", other.bearer())).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/documents").header("Authorization", other.bearer()))
                .andExpect(status().isOk()).andExpect(content().json("[]"));

        mockMvc.perform(get(base + "/download").header("Authorization", owner.bearer()))
                .andExpect(status().isOk()).andExpect(content().bytes(PDF))
                .andExpect(content().contentType("application/pdf"));
    }

    @Test
    void deletingADocumentRemovesItsGridFsFile() throws Exception {
        Account account = newAccount();
        Upload upload = uploadPdf(account);
        long files = count("fs.files");

        mockMvc.perform(delete("/api/documents/" + upload.documentId()).header("Authorization", account.bearer()))
                .andExpect(status().isNoContent());

        assertThat(count("fs.files")).isEqualTo(files - 1);
        assertThat(collection("uploaded_documents").countDocuments(new Document("_id", new ObjectId(upload.documentId())))).isZero();
        mockMvc.perform(get("/api/documents/" + upload.documentId() + "/download").header("Authorization", account.bearer()))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ transactions and dashboard (worker output)
    @Test
    void transactionsWrittenByThePythonWorkerAreReadableEditableAndIsolated() throws Exception {
        Account owner = newAccount(), other = newAccount();
        Upload upload = uploadPdf(owner);
        List<Document> seeded = seedWorkerTransactions(owner, upload);
        String datedId = seeded.get(0).getObjectId("_id").toHexString();
        String undatedId = seeded.get(1).getObjectId("_id").toHexString();

        String list = mockMvc.perform(get("/api/transactions").header("Authorization", owner.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(list, "$.content[0].id")).isEqualTo(datedId);            // newest first
        assertThat((String) JsonPath.read(list, "$.content[1].id")).isEqualTo(undatedId);
        assertThat(list).doesNotContain("sourceHash").doesNotContain(owner.id());

        mockMvc.perform(get("/api/transactions/" + datedId).header("Authorization", owner.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.amount").value(450.00))
                .andExpect(jsonPath("$.direction").value("DEBIT")).andExpect(jsonPath("$.category").value("Food"))
                .andExpect(jsonPath("$.date").value("2026-10-03")).andExpect(jsonPath("$.balance").value(10000.50));

        mockMvc.perform(get("/api/transactions").param("category", "food").param("direction", "DEBIT")
                        .header("Authorization", owner.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(get("/api/transactions").param("needsReview", "true").header("Authorization", owner.bearer()))
                .andExpect(jsonPath("$.content[0].id").value(undatedId));
        mockMvc.perform(get("/api/transactions").param("from", "03/10/2026").header("Authorization", owner.bearer()))
                .andExpect(status().isBadRequest());

        // another user sees nothing and can change nothing
        mockMvc.perform(get("/api/transactions").header("Authorization", other.bearer()))
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/transactions/" + datedId).header("Authorization", other.bearer())).andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/transactions/" + datedId).header("Authorization", other.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"merchant\":\"Hacked\"}")).andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/transactions/" + datedId).header("Authorization", other.bearer())).andExpect(status().isNotFound());

        // resolve the UNKNOWN row; money must stay Decimal128 in the database
        mockMvc.perform(patch("/api/transactions/" + undatedId).header("Authorization", owner.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"direction\":\"CREDIT\",\"date\":\"2026-10-04\",\"needsReview\":false,\"userId\":\"attacker\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.direction").value("CREDIT"))
                .andExpect(jsonPath("$.needsReview").value(false));
        Document raw = collection("transactions").find(new Document("_id", new ObjectId(undatedId))).first();
        assertThat(raw.get("amount")).isInstanceOf(Decimal128.class);
        assertThat(raw.getString("userId")).isEqualTo(owner.id());
        assertThat(raw.get("updatedAt")).isInstanceOf(Date.class);

        // an edit must not drop fields only the worker knows about (it $sets, it does not replace the document)
        mockMvc.perform(patch("/api/transactions/" + datedId).header("Authorization", owner.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"merchant\":\"Swiggy Instamart\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.merchant").value("Swiggy Instamart"));
        Document edited = collection("transactions").find(new Document("_id", new ObjectId(datedId))).first();
        assertThat(edited.getString("categorySource")).isEqualTo("ml:cat-test");
        assertThat(edited.getDouble("ocrConfidence")).isEqualTo(88.5);
        assertThat(edited.getString("pipelineCategory")).isEqualTo("Food & Dining");

        mockMvc.perform(delete("/api/transactions/" + datedId).header("Authorization", owner.bearer())).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/transactions/" + datedId).header("Authorization", owner.bearer())).andExpect(status().isNotFound());
    }

    @Test
    void dashboardAggregatesWorkerWrittenDecimalsAndKeepsUsersApart() throws Exception {
        Account owner = newAccount(), other = newAccount();
        Upload upload = uploadPdf(owner);
        List<Document> seeded = seedWorkerTransactions(owner, upload);
        String undatedId = seeded.get(1).getObjectId("_id").toHexString();

        mockMvc.perform(get("/api/dashboard/summary").param("month", "2026-10").header("Authorization", owner.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalExpenses").value(450.00))
                .andExpect(jsonPath("$.totalIncome").value(0.00))
                .andExpect(jsonPath("$.pendingReviewCount").value(1))
                .andExpect(jsonPath("$.transactionCount").value(2))
                .andExpect(jsonPath("$.categoryExpenses[0].category").value("Food"))
                .andExpect(jsonPath("$.categoryExpenses[0].total").value(450.00));

        mockMvc.perform(patch("/api/transactions/" + undatedId).header("Authorization", owner.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"direction\":\"CREDIT\",\"date\":\"2026-10-04\",\"needsReview\":false}")).andExpect(status().isOk());

        mockMvc.perform(get("/api/dashboard/summary").param("month", "2026-10").header("Authorization", owner.bearer()))
                .andExpect(jsonPath("$.totalIncome").value(75.00))
                .andExpect(jsonPath("$.netCashFlow").value(-375.00))
                .andExpect(jsonPath("$.pendingReviewCount").value(0));

        mockMvc.perform(get("/api/dashboard/summary").param("month", "2026-10").header("Authorization", other.bearer()))
                .andExpect(jsonPath("$.totalIncome").value(0.00)).andExpect(jsonPath("$.totalExpenses").value(0.00))
                .andExpect(jsonPath("$.transactionCount").value(0));
        mockMvc.perform(get("/api/dashboard/summary").param("month", "bad").header("Authorization", owner.bearer()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theDatabaseEnforcesPerUserSourceHashUniqueness() throws Exception {
        Account a = newAccount(), b = newAccount();
        Upload upload = uploadPdf(a);
        List<Document> seeded = seedWorkerTransactions(a, upload);

        Document sameUserSameHash = new Document(seeded.get(0)).append("_id", new ObjectId());
        assertThatThrownBy(() -> collection("transactions").insertOne(sameUserSameHash))
                .isInstanceOfSatisfying(MongoWriteException.class, e -> assertThat(e.getCode()).isEqualTo(11000));

        Document otherUserSameHash = new Document(seeded.get(0)).append("_id", new ObjectId()).append("userId", b.id());
        collection("transactions").insertOne(otherUserSameHash);                              // allowed
        assertThat(collection("transactions").countDocuments(new Document("sourceHash", seeded.get(0).getString("sourceHash"))))
                .isEqualTo(2);
    }
}
