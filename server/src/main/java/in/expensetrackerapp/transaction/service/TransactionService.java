package in.expensetrackerapp.transaction.service;

import in.expensetrackerapp.common.exception.BadRequestException;
import in.expensetrackerapp.common.exception.ResourceNotFoundException;
import in.expensetrackerapp.transaction.dto.TransactionBulkRequest;
import in.expensetrackerapp.transaction.dto.TransactionBulkResponse;
import in.expensetrackerapp.transaction.dto.TransactionCreateRequest;
import in.expensetrackerapp.transaction.dto.TransactionPageResponse;
import in.expensetrackerapp.transaction.dto.TransactionResponse;
import in.expensetrackerapp.transaction.dto.TransactionUpdateRequest;
import in.expensetrackerapp.transaction.model.Transaction;
import in.expensetrackerapp.transaction.model.CategoryAliases;
import in.expensetrackerapp.transaction.model.TransactionCategory;
import in.expensetrackerapp.transaction.model.TransactionDirection;
import in.expensetrackerapp.transaction.repository.TransactionRepository;
import org.bson.types.Decimal128;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;

/** Every method takes the authenticated user's id and scopes every query by it. */
@Service
public class TransactionService {

    static final int MAX_PAGE_SIZE = 100;

    private final TransactionRepository repository;
    private final MongoTemplate mongoTemplate;
    private final Clock clock;

    public TransactionService(TransactionRepository repository, MongoTemplate mongoTemplate, Clock clock) {
        this.repository = repository;
        this.mongoTemplate = mongoTemplate;
        this.clock = clock;
    }

    public TransactionPageResponse list(String userId, TransactionFilter filter, int page, int size) {
        Query countQuery = buildQuery(userId, filter);
        long total = mongoTemplate.count(countQuery, Transaction.class);

        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        // Newest first; createdAt and id make the order total, so pages never overlap or skip rows.
        Pageable pageable = PageRequest.of(safePage, safeSize,
                Sort.by(Sort.Direction.DESC, "date", "createdAt", "id"));
        List<TransactionResponse> content = mongoTemplate
                .find(buildQuery(userId, filter).with(pageable), Transaction.class)
                .stream().map(TransactionResponse::fromTransaction).toList();

        int totalPages = (int) Math.ceil(total / (double) safeSize);
        return new TransactionPageResponse(content, safePage, safeSize, total, totalPages);
    }

    public TransactionResponse get(String userId, String id) {
        return TransactionResponse.fromTransaction(findOwned(userId, id));
    }

    public TransactionResponse update(String userId, String id, TransactionUpdateRequest request) {
        if (request.hasNoChanges()) {
            throw new BadRequestException("No fields to update");
        }
        Transaction existing = findOwned(userId, id);

        Update update = new Update();
        TransactionDirection direction = existing.getDirection();
        String date = existing.getDate();

        if (request.category() != null) {
            TransactionCategory category = CategoryAliases.resolve(request.category())
                    .orElseThrow(() -> new BadRequestException("Unknown category"));
            update.set("category", category.getValue());
            String label = CategoryAliases.clientLabel(request.category());
            if (label != null) {
                update.set("clientCategory", label);          // keep the client's finer label (Groceries, Medical...)
            } else {
                update.unset("clientCategory");
            }
        }
        if (request.direction() != null) {
            if (request.direction() == TransactionDirection.UNKNOWN) {
                throw new BadRequestException("Direction must be DEBIT or CREDIT");
            }
            direction = request.direction();
            update.set("direction", direction);
        }
        if (request.date() != null) {
            try {
                date = LocalDate.parse(request.date()).toString();
            } catch (DateTimeParseException e) {
                throw new BadRequestException("date is not a valid calendar date");
            }
            update.set("date", date);
        }
        if (request.amount() != null) {
            // Stored as Decimal128 with scale 2. Bean validation already limits input to 2 decimals;
            // this guard keeps the service safe even when called without it (no silent rounding of money).
            BigDecimal amount;
            try {
                amount = request.amount().setScale(2, RoundingMode.UNNECESSARY);
            } catch (ArithmeticException e) {
                throw new BadRequestException("amount must have at most 2 decimal places");
            }
            update.set("amount", new Decimal128(amount));
        }
        if (request.merchant() != null) {
            String merchant = request.merchant().strip();
            if (merchant.isEmpty()) {
                throw new BadRequestException("merchant must not be blank");
            }
            update.set("merchant", merchant);
        }
        if (request.description() != null) {
            update.set("description", request.description());
        }
        if (request.needsReview() != null) {
            boolean unresolved = direction == TransactionDirection.UNKNOWN || date == null;
            if (!request.needsReview() && unresolved) {
                throw new BadRequestException(
                        "Set the direction and date before marking this transaction as reviewed");
            }
            update.set("needsReview", request.needsReview());
        }
        if (request.accountId() != null) {
            requireOwnedAccount(userId, request.accountId());
            update.set("accountId", blankToNull(request.accountId()));
        }
        if (request.method() != null) {
            update.set("method", blankToNull(request.method()));
        }
        if (request.incomeSource() != null) {
            update.set("incomeSource", blankToNull(request.incomeSource()));
        }
        if (request.notes() != null) {
            update.set("notes", request.notes());
        }
        update.set("updatedAt", clock.instant());

        Transaction updated = mongoTemplate.findAndModify(
                ownedQuery(userId, id), update, FindAndModifyOptions.options().returnNew(true), Transaction.class);
        if (updated == null) {
            throw new ResourceNotFoundException("Transaction not found");
        }
        return TransactionResponse.fromTransaction(updated);
    }

    public void delete(String userId, String id) {
        Transaction existing = repository.findByIdAndUserId(id, userId).orElse(null);
        if (repository.deleteByIdAndUserId(id, userId) == 0) {
            throw new ResourceNotFoundException("Transaction not found");
        }
        if (existing != null && existing.getBillId() != null) {
            reopenBill(userId, existing);
        }
    }

    /** Deleting a bill payment puts the bill back to unpaid with its previous due date (same as the client reducer). */
    private void reopenBill(String userId, Transaction payment) {
        Update revert = new Update().set("paid", false).unset("lastTransactionId")
                .set("updatedAt", java.util.Date.from(clock.instant()));
        if (payment.getBillDueDate() != null) {
            revert.set("dueDate", payment.getBillDueDate());
        }
        mongoTemplate.updateFirst(new Query(Criteria.where("userId").is(userId)
                .and("lastTransactionId").is(payment.getId())), revert, "bills");
    }

    private Transaction findOwned(String userId, String id) {
        return repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction not found"));
    }

    private static Query ownedQuery(String userId, String id) {
        return new Query(Criteria.where("id").is(id).and("userId").is(userId));
    }

    private Query buildQuery(String userId, TransactionFilter filter) {
        if (filter.from() != null && filter.to() != null && filter.from().isAfter(filter.to())) {
            throw new BadRequestException("'from' must not be after 'to'");
        }
        Criteria criteria = Criteria.where("userId").is(userId);
        if (filter.from() != null && filter.to() != null) {
            criteria = criteria.and("date").gte(filter.from().toString()).lte(filter.to().toString());
        } else if (filter.from() != null) {
            criteria = criteria.and("date").gte(filter.from().toString());
        } else if (filter.to() != null) {
            criteria = criteria.and("date").lte(filter.to().toString());
        }
        if (filter.category() != null) {
            TransactionCategory category = TransactionCategory.fromValue(filter.category())
                    .orElseThrow(() -> new BadRequestException("Unknown category"));
            criteria = criteria.and("category").is(category.getValue());
        }
        if (filter.direction() != null) {
            criteria = criteria.and("direction").is(filter.direction());
        }
        if (filter.needsReview() != null) {
            criteria = criteria.and("needsReview").is(filter.needsReview());
        }
        // Ids are opaque strings (migrated rows carry "migration-<ts>"); never ObjectId-validate them.
        if (filter.documentId() != null && !filter.documentId().isBlank()) {
            criteria = criteria.and("documentId").is(filter.documentId().trim());
        }
        if (filter.jobId() != null && !filter.jobId().isBlank()) {
            criteria = criteria.and("jobId").is(filter.jobId().trim());
        }
        // Compound conditions go through ONE $and (Mongo/Spring reject a second andOperator on a Criteria).
        List<Criteria> extra = new ArrayList<>();
        if (filter.accountId() != null && !filter.accountId().isBlank()) {
            String acc = filter.accountId().trim();
            extra.add(new Criteria().orOperator(
                    Criteria.where("accountId").is(acc), Criteria.where("toAccountId").is(acc)));
        }
        if (filter.q() != null && !filter.q().isBlank()) {
            // Quoted, case-insensitive literal search: user input is never interpreted as a regex.
            Pattern pattern = Pattern.compile(Pattern.quote(filter.q().trim()), Pattern.CASE_INSENSITIVE);
            extra.add(new Criteria().orOperator(
                    Criteria.where("merchant").regex(pattern), Criteria.where("description").regex(pattern)));
        }
        if (filter.type() != null && !filter.type().isBlank()) {
            extra.add(typeCriteria(filter.type().trim().toLowerCase(Locale.ROOT)));
        }
        if (!extra.isEmpty()) {
            criteria = criteria.andOperator(extra.toArray(new Criteria[0]));
        }
        return new Query(criteria);
    }

    /** Used by bill payment: creates the DEBIT row linked to (billId, dueDate); the unique partial index blocks double pay. */
    public TransactionResponse createForBill(String userId, TransactionCreateRequest base, String billId, String dueDate) {
        Transaction t = buildNew(userId, base);
        t.setBillId(billId);
        t.setBillDueDate(dueDate);
        t.setOrigin("BILL");
        t.setSourceHash(sha(userId + "|bill|" + billId + "|" + dueDate));
        try {
            return TransactionResponse.fromTransaction(repository.insert(t));
        } catch (DuplicateKeyException e) {
            throw new in.expensetrackerapp.common.exception.ConflictException("This bill is already paid for that due date");
        }
    }

    // ---------------------------------------------------------------- create (M2)

    /** Creates one manual/voice/receipt/import row. Idempotent by clientRequestId; 409 on a duplicate hash. */
    public TransactionResponse create(String userId, TransactionCreateRequest request) {
        if (request.clientRequestId() != null && !request.clientRequestId().isBlank()) {
            Transaction existing = mongoTemplate.findOne(new Query(Criteria.where("userId").is(userId)
                    .and("clientRequestId").is(request.clientRequestId().trim())), Transaction.class);
            if (existing != null) {
                return TransactionResponse.fromTransaction(existing);   // replayed request: same result
            }
        }
        Transaction t = buildNew(userId, request);
        try {
            return TransactionResponse.fromTransaction(repository.insert(t));
        } catch (DuplicateKeyException e) {
            throw new in.expensetrackerapp.common.exception.ConflictException(
                    "An identical transaction already exists");
        }
    }

    /** Creates many rows (<= 2000). Per-row failures are reported, never abort the batch. */
    public TransactionBulkResponse createBulk(String userId, TransactionBulkRequest request) {
        List<TransactionResponse> created = new ArrayList<>();
        List<TransactionBulkResponse.RowError> errors = new ArrayList<>();
        int skipped = 0;
        List<TransactionCreateRequest> rows = request.transactions();
        for (int i = 0; i < rows.size(); i++) {
            try {
                Transaction t = buildNew(userId, rows.get(i));
                Transaction existing = t.getClientRequestId() == null ? null : mongoTemplate.findOne(
                        new Query(Criteria.where("userId").is(userId).and("clientRequestId").is(t.getClientRequestId())),
                        Transaction.class);
                if (existing != null) {
                    skipped++;
                    continue;
                }
                created.add(TransactionResponse.fromTransaction(repository.insert(t)));
            } catch (DuplicateKeyException e) {
                skipped++;
            } catch (BadRequestException e) {
                errors.add(new TransactionBulkResponse.RowError(i, e.getMessage()));
            }
        }
        return new TransactionBulkResponse(created, skipped, errors);
    }

    private Transaction buildNew(String userId, TransactionCreateRequest r) {
        String date;
        try {
            date = LocalDate.parse(r.date()).toString();
        } catch (DateTimeParseException | NullPointerException e) {
            throw new BadRequestException("date is not a valid calendar date");
        }
        TransactionDirection requestedDirection = r.direction();
        String typeCategory = null;
        if (r.type() != null) {
            switch (r.type()) {
                case "income" -> requestedDirection = TransactionDirection.CREDIT;
                case "expense" -> requestedDirection = TransactionDirection.DEBIT;
                case "investment" -> { requestedDirection = TransactionDirection.DEBIT; typeCategory = "Investment"; }
                default -> {
                    requestedDirection = TransactionDirection.DEBIT;
                    typeCategory = "Transfer";
                    if (r.toAccountId() == null || r.toAccountId().isBlank() || r.accountId() == null || r.accountId().isBlank()) {
                        throw new BadRequestException("A transfer needs accountId and toAccountId");
                    }
                }
            }
        }
        if (requestedDirection == null || requestedDirection == TransactionDirection.UNKNOWN) {
            throw new BadRequestException("direction or type is required (DEBIT/CREDIT)");
        }
        final TransactionDirection direction = requestedDirection;
        BigDecimal amount;
        try {
            amount = r.amount().setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException | NullPointerException e) {
            throw new BadRequestException("amount must have at most 2 decimal places");
        }
        if (amount.signum() <= 0) {
            throw new BadRequestException("amount must be positive");
        }
        String merchant = r.merchant() == null ? "" : r.merchant().strip();
        if (merchant.isEmpty()) {
            throw new BadRequestException("merchant must not be blank");
        }
        String category = typeCategory != null ? typeCategory : r.category() == null || r.category().isBlank()
                ? (direction == TransactionDirection.CREDIT ? TransactionCategory.SALARY : TransactionCategory.OTHER).getValue()
                : in.expensetrackerapp.transaction.model.CategoryAliases.resolve(r.category()).orElseThrow(() -> new BadRequestException("Unknown category")).getValue();
        boolean transfer = TransactionCategory.TRANSFER.getValue().equals(category);
        if (transfer && r.toAccountId() != null && !r.toAccountId().isBlank()
                && r.toAccountId().equals(r.accountId())) {
            throw new BadRequestException("toAccountId must differ from accountId");
        }
        String currency = r.currency() == null || r.currency().isBlank() ? null : r.currency().trim().toUpperCase(Locale.ROOT);

        requireOwnedAccount(userId, r.accountId());
        requireOwnedAccount(userId, r.toAccountId());
        Instant now = clock.instant();
        Transaction t = new Transaction();
        t.setUserId(userId);
        t.setDate(date);
        t.setAmount(amount);
        t.setCurrency(currency);
        t.setDirection(direction);
        t.setMerchant(merchant);
        t.setCategory(category);
        // Refund flag: reuse the pipeline's exact label so the dashboard classifies it as a refund (M4).
        boolean refund = (Boolean.TRUE.equals(r.refund()) || "Refund".equalsIgnoreCase(r.incomeSource())) && direction == TransactionDirection.CREDIT;
        t.setClientCategory(typeCategory != null ? null : CategoryAliases.clientLabel(r.category()));
        if (refund) {
            t.setCategory(TransactionCategory.OTHER.getValue());
            t.setPipelineCategory("Refund / Cashback");
        }
        t.setDescription(r.description() == null ? "" : r.description());
        t.setSource("manual");
        t.setOrigin(blankToNull(r.origin()) == null ? "MANUAL" : r.origin().trim().toUpperCase(Locale.ROOT));
        t.setAccountId(blankToNull(r.accountId()));
        t.setToAccountId(blankToNull(r.toAccountId()));
        t.setMethod(blankToNull(r.method()));
        t.setIncomeSource(blankToNull(r.incomeSource()));
        t.setNotes(r.notes());
        t.setClientRequestId(blankToNull(r.clientRequestId()));
        t.setNeedsReview(false);                        // always explicit: absent fields are excluded by dashboard filters (T5)
        t.setSourceHash(manualHash(userId, t));
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        t.setPossibleDuplicateOf(findPossibleDuplicateHash(userId, t));
        return t;
    }

    /** sha256(userId|date|merchant|amount|direction|accountId|method|clientRequestId) - never null (X5). */
    static String manualHash(String userId, Transaction t) {
        return sha(String.join("|", userId, t.getDate(), t.getMerchant().toLowerCase(Locale.ROOT),
                t.getAmount().toPlainString(), t.getDirection().name(),
                nz(t.getAccountId()), nz(t.getMethod()), nz(t.getClientRequestId())));
    }

    private static String sha(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** X4: a statement row with the same date/amount/direction/merchant already exists -> flag, do not block. */
    private String findPossibleDuplicateHash(String userId, Transaction t) {
        Query q = new Query(Criteria.where("userId").is(userId).and("date").is(t.getDate())
                .and("direction").is(t.getDirection())
                .and("amount").is(new Decimal128(t.getAmount()))
                .and("merchant").regex(Pattern.compile("^" + Pattern.quote(t.getMerchant()) + "$", Pattern.CASE_INSENSITIVE)));
        q.limit(1);
        Transaction other = mongoTemplate.findOne(q, Transaction.class);
        return other == null ? null : other.getSourceHash();
    }

    private static Criteria typeCriteria(String type) {
        return switch (type) {
            case "transfer" -> Criteria.where("category").is("Transfer");
            case "investment" -> new Criteria().andOperator(Criteria.where("category").is("Investment"),
                    Criteria.where("direction").is(TransactionDirection.DEBIT));
            case "income" -> new Criteria().andOperator(Criteria.where("direction").is(TransactionDirection.CREDIT),
                    Criteria.where("category").ne("Transfer"));
            case "expense" -> new Criteria().andOperator(Criteria.where("direction").is(TransactionDirection.DEBIT),
                    Criteria.where("category").nin("Transfer", "Investment"));
            default -> throw new BadRequestException("type must be income, expense, investment or transfer");
        };
    }

    /** DATABASE_RELATIONSHIPS: accountId must exist and belong to the same user (400, never reveals other users). */
    private void requireOwnedAccount(String userId, String accountId) {
        if (accountId == null || accountId.isBlank()) {
            return;
        }
        if (!org.bson.types.ObjectId.isValid(accountId.trim())
                || mongoTemplate.count(new Query(Criteria.where("userId").is(userId)
                .and("_id").is(new org.bson.types.ObjectId(accountId.trim()))), "accounts") == 0) {
            throw new BadRequestException("accountId does not refer to one of your accounts");
        }
    }

    // ---------------------------------------------------------------- CSV export (Reports)

    static final int MAX_EXPORT_ROWS = 50_000;

    /** Same filters as list(); one row per transaction, newest first. Cells starting with = + - @ are neutralised. */
    public String exportCsv(String userId, TransactionFilter filter) {
        Pageable pageable = PageRequest.of(0, MAX_EXPORT_ROWS, Sort.by(Sort.Direction.DESC, "date", "createdAt", "id"));
        StringBuilder sb = new StringBuilder("date,type,direction,amount,currency,merchant,category,method,description,source,needsReview\n");
        for (Transaction t : mongoTemplate.find(buildQuery(userId, filter).with(pageable), Transaction.class)) {
            sb.append(String.join(",",
                    cell(t.getDate()), cell(TransactionResponse.deriveType(t.getDirection(), t.getCategory())),
                    cell(t.getDirection() == null ? null : t.getDirection().name()),
                    t.getAmount() == null ? "" : t.getAmount().toPlainString(), cell(t.getCurrency()),
                    cell(t.getMerchant()), cell(t.getCategory()), cell(t.getMethod()),
                    cell(t.getNotes() != null ? t.getNotes() : t.getDescription()), cell(t.getSource()),
                    String.valueOf(t.isNeedsReview()))).append('\n');
        }
        return sb.toString();
    }

    private static String cell(String v) {
        if (v == null) {
            return "";
        }
        String x = v.replace("\r", " ").replace("\n", " ");
        if (!x.isEmpty() && "=+-@\t".indexOf(x.charAt(0)) >= 0) {
            x = "'" + x;
        }
        return "\"" + x.replace("\"", "\"\"") + "\"";
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }
}
