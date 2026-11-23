package in.expensetrackerapp.voice;

import in.expensetrackerapp.common.exception.BadRequestException;
import in.expensetrackerapp.common.exception.ResourceNotFoundException;
import in.expensetrackerapp.dashboard.config.DashboardProperties;
import in.expensetrackerapp.transaction.dto.TransactionCreateRequest;
import in.expensetrackerapp.transaction.dto.TransactionResponse;
import in.expensetrackerapp.transaction.model.Transaction;
import in.expensetrackerapp.transaction.model.TransactionDirection;
import in.expensetrackerapp.transaction.service.TransactionService;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Speech -> Gemini -> structured expenses -> MongoDB.
 * Every voice request is kept in {@code voice_entries} ({_id, userId, speechToText, status, expenses, transactionIds}),
 * and each parsed expense becomes a normal transaction (method "Cash", origin "VOICE") on the user's cash account,
 * so it shows up in the existing Money / Home / Reports screens with no extra frontend data layer.
 */
@Service
public class VoiceService {

    static final int MAX_TEXT = 2000;
    static final int MAX_ITEMS = 20;

    private final GeminiClient gemini;
    private final MongoTemplate mongo;
    private final TransactionService transactions;
    private final DashboardProperties dashboard;
    private final Clock clock;

    public VoiceService(GeminiClient gemini, MongoTemplate mongo, TransactionService transactions,
                        DashboardProperties dashboard, Clock clock) {
        this.gemini = gemini;
        this.mongo = mongo;
        this.transactions = transactions;
        this.dashboard = dashboard;
        this.clock = clock;
    }

    public Map<String, Object> process(String userId, String text, byte[] audio, String audioMime) {
        String transcript = text == null ? null : text.strip();
        if (audio == null && (transcript == null || transcript.isEmpty())) {
            throw new BadRequestException("speechToText is required");
        }
        if (transcript != null && transcript.length() > MAX_TEXT) {
            throw new BadRequestException("speechToText must be at most " + MAX_TEXT + " characters");
        }
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of(dashboard.getZone())));
        ObjectId entryId = new ObjectId();
        Date now = Date.from(clock.instant());
        Document entry = new Document("_id", entryId).append("userId", userId)
                .append("speechToText", transcript).append("status", "PROCESSING")
                .append("createdAt", now).append("updatedAt", now);
        mongo.insert(entry, "voice_entries");

        Document parsed;
        try {
            parsed = gemini.extract(transcript, audio, audioMime, today);
        } catch (RuntimeException e) {
            update(entryId, new Document("status", "FAILED").append("error", e.getMessage()));
            throw e;
        }
        String heard = transcript != null ? transcript : parsed.getString("speechToText");
        List<?> items = parsed.get("expenses") instanceof List<?> l ? l : List.of();

        String cashAccountId = items.isEmpty() ? null : ensureCashAccount(userId);
        List<TransactionResponse> created = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        List<Object> parsedItems = new ArrayList<>();
        for (int i = 0; i < Math.min(items.size(), MAX_ITEMS); i++) {
            if (!(items.get(i) instanceof Document d)) {
                skipped.add(skip(i, "Unreadable item"));
                continue;
            }
            parsedItems.add(d);
            try {
                created.add(transactions.create(userId, toRequest(d, i, entryId.toHexString(), cashAccountId, today)));
            } catch (RuntimeException e) {
                skipped.add(skip(i, e.getMessage()));
            }
        }
        String status = created.isEmpty() ? "NO_TRANSACTIONS" : skipped.isEmpty() ? "COMPLETED" : "PARTIALLY_COMPLETED";
        update(entryId, new Document("status", status).append("speechToText", heard).append("expenses", parsedItems)
                .append("transactionIds", created.stream().map(TransactionResponse::id).toList())
                .append("skipped", skipped));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", entryId.toHexString());
        out.put("speechToText", heard);
        out.put("status", status);
        out.put("transactions", created);
        out.put("skipped", skipped);
        return out;
    }

    public List<Map<String, Object>> list(String userId, int page, int size) {
        Query q = new Query(Criteria.where("userId").is(userId)).with(Sort.by(Sort.Direction.DESC, "createdAt"))
                .skip((long) Math.max(page, 0) * Math.min(Math.max(size, 1), 100)).limit(Math.min(Math.max(size, 1), 100));
        return mongo.find(q, Document.class, "voice_entries").stream().map(d -> summary(d, false)).toList();
    }

    public Map<String, Object> get(String userId, String id) {
        if (!ObjectId.isValid(id)) throw new ResourceNotFoundException("Voice entry not found");
        Document d = mongo.findOne(new Query(Criteria.where("_id").is(new ObjectId(id)).and("userId").is(userId)),
                Document.class, "voice_entries");
        if (d == null) throw new ResourceNotFoundException("Voice entry not found");
        return summary(d, true);
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> summary(Document d, boolean withTransactions) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getObjectId("_id").toHexString());
        m.put("speechToText", d.getString("speechToText"));
        m.put("status", d.getString("status"));
        if (d.getString("error") != null) m.put("error", d.getString("error"));
        List<String> ids = d.getList("transactionIds", String.class, List.of());
        m.put("transactionIds", ids);
        m.put("createdAt", d.getDate("createdAt") == null ? null : d.getDate("createdAt").toInstant());
        if (withTransactions && !ids.isEmpty()) {
            List<ObjectId> oids = ids.stream().filter(ObjectId::isValid).map(ObjectId::new).toList();
            m.put("transactions", mongo.find(new Query(Criteria.where("_id").in(oids)
                    .and("userId").is(d.getString("userId"))), Transaction.class).stream()
                    .map(TransactionResponse::fromTransaction).toList());
        }
        return m;
    }

    private TransactionCreateRequest toRequest(Document d, int index, String entryId, String accountId, LocalDate today) {
        Object amountRaw = d.get("amount");
        if (!(amountRaw instanceof Number n)) {
            throw new BadRequestException("Amount missing");
        }
        BigDecimal amount = new BigDecimal(n.toString()).setScale(2, RoundingMode.HALF_UP);
        if (amount.signum() <= 0) {
            throw new BadRequestException("Amount must be positive");
        }
        TransactionDirection direction = "CREDIT".equalsIgnoreCase(d.getString("direction"))
                ? TransactionDirection.CREDIT : TransactionDirection.DEBIT;
        String merchant = d.getString("merchant") == null || d.getString("merchant").isBlank()
                ? "Cash expense" : d.getString("merchant").strip();
        if (merchant.length() > 200) merchant = merchant.substring(0, 200);
        String description = d.getString("description");
        if (description != null && description.length() > 2000) description = description.substring(0, 2000);
        return new TransactionCreateRequest(date(d.getString("date"), today).toString(), amount, direction, merchant,
                d.getString("category"), description, accountId, null, "Cash", "VOICE", null, null, null,
                entryId + "-" + index, null);
    }

    /** Invalid or future dates fall back to today. */
    private static LocalDate date(String value, LocalDate today) {
        try {
            LocalDate parsed = LocalDate.parse(value);
            return parsed.isAfter(today) ? today : parsed;
        } catch (DateTimeParseException | NullPointerException e) {
            return today;
        }
    }

    /** The user's cash account (created on first use) so voice expenses are "registered in cash". */
    private String ensureCashAccount(String userId) {
        Document existing = mongo.findOne(new Query(Criteria.where("userId").is(userId).and("type").is("cash")),
                Document.class, "accounts");
        if (existing != null) {
            return existing.getObjectId("_id").toHexString();
        }
        for (String name : new String[]{"Cash", "Cash Wallet", "Cash (voice)"}) {
            Date now = Date.from(clock.instant());
            Document acc = new Document("_id", new ObjectId()).append("userId", userId).append("name", name)
                    .append("type", "cash").append("openingBalance", new Decimal128(BigDecimal.ZERO.setScale(2)))
                    .append("limit", new Decimal128(BigDecimal.ZERO.setScale(2)))
                    .append("createdAt", now).append("updatedAt", now);
            try {
                mongo.insert(acc, "accounts");
                return acc.getObjectId("_id").toHexString();
            } catch (DuplicateKeyException e) {
                // name taken by a non-cash account: try the next name
            }
        }
        throw new BadRequestException("Could not create a cash account. Please create one in Accounts first");
    }

    private void update(ObjectId id, Document fields) {
        fields.append("updatedAt", Date.from(clock.instant()));
        mongo.updateFirst(new Query(Criteria.where("_id").is(id)),
                new org.springframework.data.mongodb.core.query.BasicUpdate(new Document("$set", fields)), "voice_entries");
    }

    private static Map<String, Object> skip(int index, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("index", index);
        m.put("reason", reason);
        return m;
    }
}
