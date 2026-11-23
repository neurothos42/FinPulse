package in.expensetrackerapp.entity;

import in.expensetrackerapp.common.exception.BadRequestException;
import in.expensetrackerapp.common.exception.ConflictException;
import in.expensetrackerapp.common.exception.ResourceNotFoundException;
import in.expensetrackerapp.entity.EntitySchemas.Schema;
import in.expensetrackerapp.transaction.dto.TransactionCreateRequest;
import in.expensetrackerapp.transaction.dto.TransactionResponse;
import in.expensetrackerapp.transaction.model.CategoryAliases;
import in.expensetrackerapp.transaction.service.TransactionService;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.BasicUpdate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CRUD for accounts, budgets, goals, holdings, bills, loans, splits and festivals (M8).
 * Every query is scoped by the authenticated user's id; another user's id is reported as 404.
 */
@Service
public class EntityService {

    static final int MAX_PER_USER = 1000;

    private final MongoTemplate mongo;
    private final TransactionService transactionService;
    private final Clock clock;

    public EntityService(MongoTemplate mongo, TransactionService transactionService, Clock clock) {
        this.mongo = mongo;
        this.transactionService = transactionService;
        this.clock = clock;
    }

    public List<Map<String, Object>> list(String userId, String resource) {
        Schema schema = schema(resource);
        Query q = new Query(Criteria.where("userId").is(userId)).with(Sort.by(Sort.Direction.ASC, "createdAt"))
                .limit(MAX_PER_USER);
        List<Map<String, Object>> items = mongo.find(q, Document.class, schema.collection()).stream()
                .map(EntityService::toApi).toList();
        if ("accounts".equals(resource)) {
            Map<String, BigDecimal> balances = balances(userId);
            items.forEach(a -> a.put("balance", balanceOf(a, balances)));
        }
        return items;
    }

    private static BigDecimal balanceOf(Map<String, Object> account, Map<String, BigDecimal> movements) {
        BigDecimal opening = account.get("openingBalance") instanceof BigDecimal b ? b : BigDecimal.ZERO;
        return opening.add(movements.getOrDefault((String) account.get("id"), BigDecimal.ZERO)).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Net movement per account id, computed (never stored): CREDIT adds, DEBIT subtracts, and a Transfer's
     * destination (toAccountId) receives the amount. Unreviewed rows are excluded, as on the dashboard.
     */
    public Map<String, BigDecimal> balances(String userId) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        var coll = mongo.getCollection("transactions");
        var own = new Document("userId", userId).append("needsReview", false);
        coll.aggregate(List.of(
                new Document("$match", new Document(own).append("accountId", new Document("$ne", null))
                        .append("direction", new Document("$in", List.of("DEBIT", "CREDIT")))),
                new Document("$group", new Document("_id", new Document("a", "$accountId").append("d", "$direction"))
                        .append("total", new Document("$sum", "$amount"))))).forEach(d -> {
            Document id = d.get("_id", Document.class);
            BigDecimal t = ((Decimal128) d.get("total")).bigDecimalValue();
            out.merge(id.getString("a"), "CREDIT".equals(id.getString("d")) ? t : t.negate(), BigDecimal::add);
        });
        coll.aggregate(List.of(
                new Document("$match", new Document(own).append("category", "Transfer").append("direction", "DEBIT")
                        .append("toAccountId", new Document("$ne", null))),
                new Document("$group", new Document("_id", "$toAccountId").append("total", new Document("$sum", "$amount"))))
        ).forEach(d -> out.merge(d.getString("_id"), ((Decimal128) d.get("total")).bigDecimalValue(), BigDecimal::add));
        return out;
    }

    public Map<String, Object> get(String userId, String resource, String id) {
        Map<String, Object> item = toApi(findOwned(schema(resource), userId, id));
        if ("accounts".equals(resource)) {
            item.put("balance", balanceOf(item, balances(userId)));
        }
        return item;
    }

    public Map<String, Object> create(String userId, String resource, Map<String, Object> body) {
        Schema schema = schema(resource);
        if (mongo.count(new Query(Criteria.where("userId").is(userId)), schema.collection()) >= MAX_PER_USER) {
            throw new ConflictException("Limit of " + MAX_PER_USER + " items reached for " + resource);
        }
        Document doc = EntityValidator.clean(schema, body, null);
        applyPlan(resource, body, doc);
        Date now = Date.from(clock.instant());
        doc.append("_id", new ObjectId()).append("userId", userId).append("createdAt", now).append("updatedAt", now);
        mongo.insert(doc, schema.collection());           // unique indexes -> DuplicateKeyException -> 409
        return toApi(doc);
    }

    public Map<String, Object> update(String userId, String resource, String id, Map<String, Object> body) {
        Schema schema = schema(resource);
        Document existing = findOwned(schema, userId, id);
        Document doc = EntityValidator.clean(schema, body, existing);
        if ("goals".equals(resource) && !body.containsKey("plan") && existing.get("plan") != null) {
            doc.put("plan", existing.get("plan"));
        }
        applyPlan(resource, body, doc);
        if ("bills".equals(resource) && existing.get("lastTransactionId") != null) {
            doc.put("lastTransactionId", existing.get("lastTransactionId"));     // managed
        }
        doc.put("updatedAt", Date.from(clock.instant()));
        Document set = new Document(doc);
        Document unset = new Document();
        if ("goals".equals(resource) && body.containsKey("plan") && body.get("plan") == null) {
            set.remove("plan");
            unset.put("plan", "");
        }
        Document update = new Document("$set", set);
        if (!unset.isEmpty()) {
            update.append("$unset", unset);
        }
        mongo.updateFirst(ownedQuery(userId, id), new BasicUpdate(update), schema.collection());
        return toApi(findOwned(schema, userId, id));
    }

    public void delete(String userId, String resource, String id) {
        Schema schema = schema(resource);
        findOwned(schema, userId, id);
        if ("accounts".equals(resource)) {
            long used = mongo.count(new Query(Criteria.where("userId").is(userId).orOperator(
                    Criteria.where("accountId").is(id), Criteria.where("toAccountId").is(id))), "transactions");
            if (used > 0) {
                throw new ConflictException("This account is used by " + used + " transaction(s) and cannot be deleted");
            }
            if (mongo.count(new Query(Criteria.where("userId").is(userId)), schema.collection()) <= 1) {
                throw new ConflictException("You must keep at least one account");
            }
        }
        mongo.remove(ownedQuery(userId, id), schema.collection());
    }

    /** POST /api/goals/{id}/contributions - the only way to change a goal's {@code current}. Atomic $inc. */
    public Map<String, Object> contribute(String userId, String id, Map<String, Object> body) {
        Schema schema = schema("goals");
        findOwned(schema, userId, id);
        BigDecimal amount = EntityValidator.number("amount", body.get("amount"));
        if (amount.signum() <= 0 || amount.compareTo(EntitySchemas.TRILLION) > 0 || amount.stripTrailingZeros().scale() > 2) {
            throw new BadRequestException("amount must be positive with at most 2 decimal places");
        }
        Document update = new Document("$inc", new Document("current", new Decimal128(amount.setScale(2, RoundingMode.UNNECESSARY))))
                .append("$set", new Document("updatedAt", Date.from(clock.instant())));
        mongo.updateFirst(ownedQuery(userId, id), new BasicUpdate(update), schema.collection());
        return toApi(findOwned(schema, userId, id));
    }

    /**
     * POST /api/bills/{id}/pay. Creates the expense (unique per bill+due date, so a double tap cannot pay twice),
     * then marks the bill paid, or advances a recurring bill by one month.
     */
    public Map<String, Object> payBill(String userId, String id, Map<String, Object> body) {
        Schema schema = schema("bills");
        Document bill = findOwned(schema, userId, id);
        String dueDate = bill.getString("dueDate");
        if (Boolean.TRUE.equals(bill.getBoolean("paid"))) {
            throw new ConflictException("This bill is already paid");
        }
        String accountId = optionalString(body, "accountId");
        if (accountId != null) {
            findOwned(schema("accounts"), userId, accountId);          // must exist and belong to the caller
        }
        String paidOn = optionalString(body, "paidOn");
        try {
            paidOn = paidOn == null ? LocalDate.now(clock).toString() : LocalDate.parse(paidOn).toString();
        } catch (DateTimeParseException e) {
            throw new BadRequestException("paidOn must be a valid date (yyyy-MM-dd)");
        }
        BigDecimal amount = ((Decimal128) bill.get("amount")).bigDecimalValue().setScale(2, RoundingMode.HALF_UP);
        String category = CategoryAliases.resolve(bill.getString("category")).map(c -> c.getValue()).orElse("Bills");

        TransactionCreateRequest request = new TransactionCreateRequest(paidOn, amount,
                in.expensetrackerapp.transaction.model.TransactionDirection.DEBIT, bill.getString("name"), category,
                "Paid bill: " + bill.getString("name"), accountId, null, optionalString(body, "method"), "BILL",
                null, null, null, null, null);
        TransactionResponse tx = transactionService.createForBill(userId, request, id, dueDate);

        Document set = new Document("lastTransactionId", tx.id()).append("updatedAt", Date.from(clock.instant()));
        if (Boolean.TRUE.equals(bill.getBoolean("recurring"))) {
            set.append("dueDate", LocalDate.parse(dueDate).plusMonths(1).toString()).append("paid", false);
        } else {
            set.append("paid", true);
        }
        mongo.updateFirst(ownedQuery(userId, id), new BasicUpdate(new Document("$set", set)), schema.collection());
        Map<String, Object> result = toApi(findOwned(schema, userId, id));
        result.put("transaction", tx);
        return result;
    }

    // ------------------------------------------------------------------ helpers

    private void applyPlan(String resource, Map<String, Object> body, Document doc) {
        if ("goals".equals(resource) && body.get("plan") != null) {
            doc.put("plan", EntityValidator.plan(body.get("plan")));
        }
    }

    private static Schema schema(String resource) {
        Schema s = EntitySchemas.get(resource);
        if (s == null) {
            throw new ResourceNotFoundException("Unknown resource");
        }
        return s;
    }

    private Document findOwned(Schema schema, String userId, String id) {
        if (id == null || !ObjectId.isValid(id)) {
            throw new ResourceNotFoundException("Not found");
        }
        Document d = mongo.findOne(ownedQuery(userId, id), Document.class, schema.collection());
        if (d == null) {
            throw new ResourceNotFoundException("Not found");
        }
        return d;
    }

    private static Query ownedQuery(String userId, String id) {
        return new Query(Criteria.where("_id").is(new ObjectId(id)).and("userId").is(userId));
    }

    private static String optionalString(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v).trim();
    }

    /** Stored document -> API map: _id -> id, Decimal128 -> number, Date -> Instant; userId is never returned. */
    static Map<String, Object> toApi(Document d) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", d.getObjectId("_id").toHexString());
        for (Map.Entry<String, Object> e : d.entrySet()) {
            if (e.getKey().equals("_id") || e.getKey().equals("userId")) continue;
            out.put(e.getKey(), convert(e.getValue()));
        }
        return out;
    }

    private static Object convert(Object v) {
        if (v instanceof Decimal128 d) return d.bigDecimalValue();
        if (v instanceof Date date) return date.toInstant();
        if (v instanceof Instant i) return i;
        if (v instanceof Document nested) {
            Map<String, Object> m = new LinkedHashMap<>();
            nested.forEach((k, val) -> m.put(k, convert(val)));
            return m;
        }
        if (v instanceof List<?> list) {
            List<Object> l = new ArrayList<>();
            list.forEach(x -> l.add(convert(x)));
            return l;
        }
        return v;
    }
}
