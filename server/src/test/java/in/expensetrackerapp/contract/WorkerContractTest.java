package in.expensetrackerapp.contract;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import in.expensetrackerapp.document.model.DocumentStatus;
import in.expensetrackerapp.document.model.UploadedDocument;
import in.expensetrackerapp.job.model.JobStatus;
import in.expensetrackerapp.job.model.ProcessingJob;
import in.expensetrackerapp.transaction.model.Transaction;
import in.expensetrackerapp.transaction.model.TransactionDirection;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.convert.MongoConverter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract between the Spring Boot service and the Python worker, checked
 * WITHOUT a database.
 *
 * Direction 1 (worker -> Java): documents produced by the real Python mapper
 * (contracts/worker_transaction.json,
 * kept in sync by a Python test) must deserialise into the Transaction entity.
 * Direction 2 (Java -> worker): the documents Java writes must have the BSON
 * types the worker's queries and
 * updates rely on (ObjectId _id, string ids, Date timestamps, int attempt
 * counters, string enums).
 */
class WorkerContractTest {

    private static final Path FIXTURE = Path.of("..", "pipelines", "financial_transaction_extraction",
            "contracts", "worker_transaction.json");
    private static final Path MIGRATED_FIXTURE = Path.of("..", "pipelines", "financial_transaction_extraction",
            "contracts", "migrated_transaction.json");

    private static MongoClient client;
    private static MongoConverter converter;

    @BeforeAll
    static void setUp() {
        // The client connects lazily; nothing here ever touches a server.
        client = MongoClients.create("mongodb://localhost:1/?serverSelectionTimeoutMS=500");
        converter = new MongoTemplate(new SimpleMongoClientDatabaseFactory(client, "contract_unused")).getConverter();
    }

    @AfterAll
    static void tearDown() {
        client.close();
    }

    private static List<Document> workerDocuments() throws IOException {
        return Document.parse(Files.readString(FIXTURE)).getList("transactions", Document.class);
    }

    // ------------------------------------------------------------------ worker ->
    // Java
    @Test
    void aFullWorkerTransactionDeserialisesIntoTheJavaEntity() throws IOException {
        Transaction t = converter.read(Transaction.class, workerDocuments().get(0));

        assertThat(t.getId()).isEqualTo("507f1f77bcf86cd799439010");
        assertThat(t.getUserId()).isEqualTo("contract-user");
        assertThat(t.getDocumentId()).isEqualTo("507f1f77bcf86cd799439012");
        assertThat(t.getJobId()).isEqualTo("507f1f77bcf86cd799439013");
        assertThat(t.getDate()).isEqualTo("2026-10-03");
        assertThat(t.getAmount()).isEqualByComparingTo("450.00");
        assertThat(t.getAmount().scale()).isEqualTo(2);
        assertThat(t.getBalance()).isEqualByComparingTo("10000.50");
        assertThat(t.getCurrency()).isEqualTo("INR");
        assertThat(t.getDirection()).isEqualTo(TransactionDirection.DEBIT);
        assertThat(t.getMerchant()).isEqualTo("Swiggy");
        assertThat(t.getCategory()).isEqualTo("Food");
        assertThat(t.getPipelineCategory()).isEqualTo("Food & Dining");
        assertThat(t.getReferenceNumber()).isEqualTo("427619384729");
        assertThat(t.getSource()).isEqualTo("statement.pdf");
        assertThat(t.getSourceHash()).isEqualTo("a".repeat(64));
        assertThat(t.isNeedsReview()).isFalse();
        assertThat(t.getCreatedAt()).isEqualTo(Instant.parse("2026-01-01T12:00:00Z"));
        assertThat(t.getUpdatedAt()).isEqualTo(Instant.parse("2026-01-01T12:00:00Z"));
    }

    @Test
    void anUnknownDirectionRowWithoutADateDeserialisesToo() throws IOException {
        Transaction t = converter.read(Transaction.class, workerDocuments().get(1));

        assertThat(t.getDirection()).isEqualTo(TransactionDirection.UNKNOWN);
        assertThat(t.getDate()).isNull();
        assertThat(t.isNeedsReview()).isTrue();
        assertThat(t.getAmount()).isEqualByComparingTo("75.00");
        assertThat(t.getBalance()).isNull();
        assertThat(t.getMerchant()).isNull();
        assertThat(t.getSourceHash()).isEqualTo("b".repeat(64));

        assertThat(t.getPossibleDuplicateOf()).isEqualTo("a".repeat(64));
    }

    @Test
    void aRowCopiedFromTheLegacyDatabaseDeserialisesWithNoDocumentAndItsOriginalTimestamp() throws IOException {
        Document migrated = Document.parse(Files.readString(MIGRATED_FIXTURE))
                .getList("transactions", Document.class).get(0);
        assertThat(migrated.keySet()).contains("legacy"); // traceability field Java does not declare

        Transaction t = converter.read(Transaction.class, migrated);

        assertThat(t.getDocumentId()).isNull();
        assertThat(t.getJobId()).isEqualTo("migration-20261114T100000Z");
        assertThat(t.getSourceHash()).isEqualTo("c".repeat(64));
        assertThat(t.getAmount()).isEqualByComparingTo("450.00");
        assertThat(t.getDirection()).isEqualTo(TransactionDirection.DEBIT);
        assertThat(t.getCategory()).isEqualTo("Food");
        assertThat(t.getCreatedAt()).isEqualTo(Instant.parse("2025-12-01T08:30:00Z"));
    }

    @Test
    void fieldsOnlyTheWorkerKnowsAboutDoNotBreakReading() throws IOException {
        Document withExtras = workerDocuments().get(0);
        assertThat(withExtras.keySet()).contains("categorySource", "categoryConfidence", "ocrConfidence");
        assertThat(converter.read(Transaction.class, withExtras).getId()).isNotNull();
    }

    // ------------------------------------------------------------------ Java ->
    // worker
    @Test
    void processingJobsAreWrittenWithTheTypesTheWorkerClaimQueryRelaysOn() {
        ProcessingJob job = new ProcessingJob();
        job.setId(new ObjectId().toHexString());
        job.setUserId("user-1");
        job.setDocumentId(new ObjectId().toHexString());
        job.setGridFsFileId(new ObjectId().toHexString());
        job.setStatus(JobStatus.PENDING);
        job.setAttempts(0);
        job.setMaxAttempts(3);
        job.setRemainingAttempts(3);
        job.setAvailableAt(Instant.parse("2026-01-01T00:00:00Z"));
        job.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));

        Document written = new Document();
        converter.write(job, written);

        assertThat(written.get("_id")).as("_id must be an ObjectId").isInstanceOf(ObjectId.class);
        assertThat(written.get("userId")).isEqualTo("user-1");
        assertThat(written.get("documentId")).as("stored as a hex STRING").isInstanceOf(String.class);
        assertThat(written.get("gridFsFileId")).isInstanceOf(String.class);
        assertThat(written.get("status")).isEqualTo("PENDING");
        assertThat(written.get("attempts")).isInstanceOf(Integer.class);
        assertThat(written.get("maxAttempts")).isInstanceOf(Integer.class);
        assertThat(written.get("remainingAttempts")).isEqualTo(3);
        assertThat(written.get("availableAt")).isInstanceOf(Date.class);
        assertThat(written.get("createdAt")).isInstanceOf(Date.class);
        assertThat(written.containsKey("leaseOwner")).as("null fields are omitted").isFalse();
        assertThat(written.containsKey("leaseUntil")).isFalse();
    }

    @Test
    void uploadedDocumentsAreWrittenWithTheTypesTheWorkerReads() {
        UploadedDocument d = new UploadedDocument();
        d.setId(new ObjectId().toHexString());
        d.setUserId("user-1");
        d.setOriginalFilename("statement.pdf");
        d.setStoredFilename("x.pdf");
        d.setContentType("application/pdf");
        d.setSizeBytes(1234L);
        d.setGridFsFileId(new ObjectId().toHexString());
        d.setStatus(DocumentStatus.UPLOADED);
        d.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        d.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));

        Document written = new Document();
        converter.write(d, written);

        assertThat(written.get("_id")).as("the worker looks documents up by ObjectId").isInstanceOf(ObjectId.class);
        assertThat(written.get("userId")).isEqualTo("user-1");
        assertThat(written.get("gridFsFileId")).isInstanceOf(String.class);
        assertThat(written.get("contentType")).isEqualTo("application/pdf");
        assertThat(written.get("originalFilename")).isEqualTo("statement.pdf");
        assertThat(written.get("status")).isEqualTo("UPLOADED");
        assertThat(written.get("createdAt")).isInstanceOf(Date.class);
    }

    @Test
    void transactionsEditedThroughJavaStillStoreMoneyAsDecimal128AndDatesAsStrings() {
        Transaction t = new Transaction();
        t.setUserId("user-1");
        t.setSourceHash("h");
        t.setDate("2026-10-03");
        t.setAmount(new java.math.BigDecimal("450.00"));
        t.setBalance(new java.math.BigDecimal("10000.50"));
        t.setDirection(TransactionDirection.UNKNOWN);
        t.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));

        Document written = new Document();
        converter.write(t, written);

        assertThat(written.get("amount")).isInstanceOf(Decimal128.class);
        assertThat(written.get("balance")).isInstanceOf(Decimal128.class);
        assertThat(written.get("date")).isInstanceOf(String.class);
        assertThat(written.get("direction")).isEqualTo("UNKNOWN");
        assertThat(written.get("createdAt")).isInstanceOf(Date.class);
    }
}
