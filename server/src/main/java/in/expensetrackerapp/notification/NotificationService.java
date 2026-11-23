package in.expensetrackerapp.notification;

import in.expensetrackerapp.entity.EntityService;
import in.expensetrackerapp.profile.model.UserProfile;
import in.expensetrackerapp.dashboard.config.DashboardProperties;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M9: notifications are derived (never stored). Bills due within 7 days or overdue, and budgets at >= 80% / 100%
 * of the month's limit. Read state lives in user_profiles.readNotifications (ids are stable per bill+due date /
 * budget+month, so a notification returns only when its situation changes).
 */
@Service
public class NotificationService {

    static final int DUE_SOON_DAYS = 7;

    private final EntityService entities;
    private final MongoTemplate mongo;
    private final DashboardProperties properties;
    private final Clock clock;

    public NotificationService(EntityService entities, MongoTemplate mongo, DashboardProperties properties, Clock clock) {
        this.entities = entities;
        this.mongo = mongo;
        this.properties = properties;
        this.clock = clock;
    }

    /** Same rules and ids as the client's notifications() selector so persisted readNotifications keep working. */
    public Map<String, Object> list(String userId) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of(properties.getZone())));
        LocalDate week = today.plusDays(DUE_SOON_DAYS);
        UserProfile profile = mongo.findById(userId, UserProfile.class);
        Set<String> read = profile == null ? Set.of() : new HashSet<>(profile.getReadNotifications());
        List<Map<String, Object>> items = new ArrayList<>();

        for (Map<String, Object> bill : entities.list(userId, "bills")) {
            if (Boolean.TRUE.equals(bill.get("paid")) || bill.get("dueDate") == null) continue;
            LocalDate due = LocalDate.parse((String) bill.get("dueDate"));
            if (due.isAfter(week)) continue;
            String id = "bill-" + bill.get("id") + "-" + due;
            items.add(item(id, "bill", due.isBefore(today) ? "overdue" : "due-soon",
                    bill.get("name") + (due.isBefore(today) ? " is overdue" : " is due soon"),
                    "₹" + bill.get("amount") + " · " + due, "🔔", bill.get("amount"), read, due.toString()));
        }

        YearMonth month = YearMonth.from(today);
        Map<String, BigDecimal> spent = spentByCategory(userId, month);
        for (Map<String, Object> budget : entities.list(userId, "budgets")) {
            String category = (String) budget.get("category");
            BigDecimal limit = (BigDecimal) budget.get("limit");
            BigDecimal used = spent.getOrDefault(category, BigDecimal.ZERO);
            if (limit == null || used.compareTo(limit) <= 0) continue;          // client: only when exceeded
            items.add(item("budget-" + month + "-" + budget.get("id"), "budget", "over-budget",
                    category + " budget exceeded", "Review this month’s transactions and adjust your plan.",
                    "⚠️", used, read, null));
        }
        long unread = items.stream().filter(i -> Boolean.FALSE.equals(i.get("read"))).count();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("unreadCount", unread);
        body.put("items", items);
        return body;
    }

    private static Map<String, Object> item(String id, String type, String severity, String title, String body,
                                            String icon, Object amount, Set<String> read, String dueDate) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("type", type);
        m.put("severity", severity);
        m.put("title", title);
        m.put("body", body);
        m.put("icon", icon);
        m.put("amount", amount);
        if (dueDate != null) m.put("dueDate", dueDate);
        m.put("read", read.contains(id));
        return m;
    }

    private Map<String, BigDecimal> spentByCategory(String userId, YearMonth month) {
        Map<String, BigDecimal> out = new HashMap<>();
        mongo.getCollection("transactions").aggregate(List.of(
                new Document("$match", new Document("userId", userId).append("needsReview", false)
                        .append("direction", "DEBIT").append("category", new Document("$nin", List.of("Transfer", "Investment")))
                        .append("date", new Document("$gte", month.atDay(1).toString()).append("$lte", month.atEndOfMonth().toString()))
                        .append("$or", List.of(new Document("currency", properties.getCurrency()), new Document("currency", null)))),
                new Document("$group", new Document("_id", new Document("$ifNull", List.of("$clientCategory", "$category"))).append("total", new Document("$sum", "$amount")))))
                .forEach(d -> out.put(d.getString("_id"), ((Decimal128) d.get("total")).bigDecimalValue()));
        return out;
    }
}
