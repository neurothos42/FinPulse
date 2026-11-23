package in.expensetrackerapp.account;

import in.expensetrackerapp.common.exception.BadRequestException;
import in.expensetrackerapp.common.exception.ResourceNotFoundException;
import in.expensetrackerapp.entity.EntityService;
import in.expensetrackerapp.profile.dto.ProfileRequest;
import in.expensetrackerapp.profile.service.ProfileService;
import in.expensetrackerapp.transaction.dto.TransactionCreateRequest;
import in.expensetrackerapp.transaction.dto.TransactionResponse;
import in.expensetrackerapp.transaction.model.Transaction;
import in.expensetrackerapp.transaction.service.TransactionService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Export of everything a user owns, and one-shot import of the client's local backup (AppState v1:
 * profile, accounts, budgets, goals, holdings, bills, loans, splits, festivals, transactions, readNotifications).
 * Import remaps the client ids to server ids, is safe to repeat (transactions use clientRequestId "import-<id>",
 * accounts/budgets that already exist are reused) and reports per-row errors instead of failing the whole batch.
 */
@Service
public class MigrationService {

    static final int MAX_TRANSACTIONS = 50_000;
    private static final List<String> ENTITY_ORDER = List.of("accounts", "budgets", "goals", "holdings", "bills", "loans", "splits", "festivals");

    private final EntityService entities;
    private final TransactionService transactions;
    private final ProfileService profiles;
    private final MongoTemplate mongo;

    public MigrationService(EntityService entities, TransactionService transactions, ProfileService profiles, MongoTemplate mongo) {
        this.entities = entities;
        this.transactions = transactions;
        this.profiles = profiles;
        this.mongo = mongo;
    }

    // ------------------------------------------------------------------ export

    public Map<String, Object> export(String userId, Map<String, Object> user) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", 1);
        out.put("exportedAt", java.time.Instant.now());
        out.put("user", user);
        try {
            out.put("profile", profiles.get(userId));
        } catch (ResourceNotFoundException e) {
            out.put("profile", null);
        }
        for (String r : ENTITY_ORDER) {
            out.put(r, entities.list(userId, r));
        }
        Query q = new Query(Criteria.where("userId").is(userId)).with(Sort.by(Sort.Direction.DESC, "date", "createdAt"))
                .limit(MAX_TRANSACTIONS);
        out.put("transactions", mongo.find(q, Transaction.class).stream().map(TransactionResponse::fromTransaction).toList());
        return out;
    }

    // ------------------------------------------------------------------ import

    @SuppressWarnings("unchecked")
    public Map<String, Object> importLocal(String userId, Map<String, Object> body) {
        Map<String, Object> data = body.get("data") instanceof Map<?, ?> m ? (Map<String, Object>) m : body;   // accept {data:{...}} backups
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<Map<String, Object>> errors = new ArrayList<>();
        Map<String, String> accountIds = new HashMap<>();
        Map<String, String> billIds = new HashMap<>();
        Map<String, String> txIds = new HashMap<>();

        for (String resource : ENTITY_ORDER) {
            int n = 0;
            for (Map<String, Object> item : list(data, resource)) {
                String oldId = str(item.get("id"));
                try {
                    Map<String, Object> created = createEntity(userId, resource, item);
                    String newId = (String) created.get("id");
                    if ("accounts".equals(resource) && oldId != null) accountIds.put(oldId, newId);
                    if ("bills".equals(resource) && oldId != null) billIds.put(oldId, newId);
                    if ("goals".equals(resource) && item.get("current") instanceof Number c && c.doubleValue() > 0) {
                        Map<String, Object> amount = new HashMap<>();
                        amount.put("amount", c);
                        entities.contribute(userId, newId, amount);
                    }
                    n++;
                } catch (RuntimeException e) {
                    errors.add(error(resource, oldId, e.getMessage()));
                }
            }
            counts.put(resource, n);
        }

        List<Map<String, Object>> txs = list(data, "transactions");
        if (txs.size() > MAX_TRANSACTIONS) {
            throw new BadRequestException("At most " + MAX_TRANSACTIONS + " transactions can be imported");
        }
        int imported = 0;
        for (Map<String, Object> t : txs) {
            String oldId = str(t.get("id"));
            try {
                TransactionResponse r = createTransaction(userId, t, oldId, accountIds, billIds);
                if (oldId != null) txIds.put(oldId, r.id());
                imported++;
            } catch (RuntimeException e) {
                errors.add(error("transactions", oldId, e.getMessage()));
            }
        }
        counts.put("transactions", imported);

        for (Map<String, Object> bill : list(data, "bills")) {          // re-point lastTransactionId to the new ids
            String oldBill = str(bill.get("id")), oldTx = str(bill.get("lastTransactionId"));
            if (oldBill != null && oldTx != null && billIds.containsKey(oldBill) && txIds.containsKey(oldTx)) {
                mongo.updateFirst(new Query(Criteria.where("userId").is(userId)
                                .and("_id").is(new org.bson.types.ObjectId(billIds.get(oldBill)))),
                        new Update().set("lastTransactionId", txIds.get(oldTx)), "bills");
            }
        }

        boolean profileSaved = false;
        if (data.get("profile") instanceof Map<?, ?> p) {
            try {
                profiles.put(userId, toProfile((Map<String, Object>) p, list(data, "readNotifications", true)));
                profileSaved = true;
            } catch (RuntimeException e) {
                errors.add(error("profile", null, e.getMessage()));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("imported", counts);
        out.put("profile", profileSaved);
        out.put("errors", errors);
        return out;
    }

    private Map<String, Object> createEntity(String userId, String resource, Map<String, Object> item) {
        try {
            return entities.create(userId, resource, item);
        } catch (DuplicateKeyException e) {
            // already there (unique account name / budget category): reuse it so a repeated import is harmless
            String field = "accounts".equals(resource) ? "name" : "category";
            for (Map<String, Object> existing : entities.list(userId, resource)) {
                if (String.valueOf(item.get(field)).equals(String.valueOf(existing.get(field)))) return existing;
            }
            throw e;
        }
    }

    private TransactionResponse createTransaction(String userId, Map<String, Object> t, String oldId,
                                                  Map<String, String> accountIds, Map<String, String> billIds) {
        String account = map(accountIds, str(t.get("accountId")));
        String toAccount = map(accountIds, str(t.get("toAccountId")));
        String source = str(t.get("source")) == null ? "MANUAL" : str(t.get("source")).toUpperCase(Locale.ROOT);
        String clientRequestId = oldId == null ? null : ("import-" + oldId).substring(0, Math.min(200, ("import-" + oldId).length()));
        TransactionCreateRequest request = new TransactionCreateRequest(str(t.get("date")), decimal(t.get("amount")), null,
                str(t.get("merchant")), str(t.get("category")), null, account, toAccount, str(t.get("method")), source,
                null, str(t.get("incomeSource")), str(t.get("notes")), clientRequestId, null, str(t.get("type")));
        String billId = str(t.get("billId"));
        if (billId != null && billIds.containsKey(billId) && str(t.get("billDueDate")) != null) {
            return transactions.createForBill(userId, request, billIds.get(billId), str(t.get("billDueDate")));
        }
        return transactions.create(userId, request);
    }

    @SuppressWarnings("unchecked")
    private ProfileRequest toProfile(Map<String, Object> p, List<Map<String, Object>> ignored) {
        Map<String, Double> allocation = null;
        if (p.get("allocation") instanceof Map<?, ?> a) {
            allocation = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : a.entrySet()) allocation.put(String.valueOf(e.getKey()), ((Number) e.getValue()).doubleValue());
        }
        return new ProfileRequest(str(p.get("ageRange")), str(p.get("employment")), strings(p.get("incomeSources")),
                strings(p.get("banks")), str(p.get("riskAnswer")), str(p.get("risk")), decimal(p.get("monthlyIncome")),
                p.get("salaryDay") instanceof Number n ? n.intValue() : null, decimal(p.get("sipTarget")),
                decimal(p.get("savingsTarget")), allocation, p.get("darkMode") instanceof Boolean b ? b : null,
                true, strings(p.get("readNotifications")));
    }

    // ------------------------------------------------------------------ helpers

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> data, String key) {
        return list(data, key, false);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> data, String key, boolean lenient) {
        Object v = data.get(key);
        if (!(v instanceof List<?> l)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : l) if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        return out;
    }

    private static List<String> strings(Object v) {
        if (!(v instanceof List<?> l)) return null;
        List<String> out = new ArrayList<>();
        for (Object o : l) if (o != null) out.add(String.valueOf(o));
        return out;
    }

    private static String str(Object v) {
        return v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v);
    }

    private static String map(Map<String, String> ids, String old) {
        return old == null ? null : ids.get(old);
    }

    private static BigDecimal decimal(Object v) {
        return v instanceof Number n ? new BigDecimal(n.toString()) : v == null ? null : new BigDecimal(String.valueOf(v));
    }

    private static Map<String, Object> error(String where, String id, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("where", where);
        m.put("id", id);
        m.put("message", message);
        return m;
    }
}
