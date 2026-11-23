package in.expensetrackerapp.dashboard.service;

import in.expensetrackerapp.common.exception.BadRequestException;
import in.expensetrackerapp.dashboard.config.DashboardProperties;
import in.expensetrackerapp.entity.EntityService;
import in.expensetrackerapp.profile.model.UserProfile;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Server-side equivalent of the client's selectMetrics() (client/src/store/selectors.ts): totals, category split,
 * balances, net worth, budgets and the 6-component health score, computed from MongoDB so the app does not have to
 * download every transaction. Rows still needing review (needsReview=true) are excluded, as on the dashboard.
 * Categories are the client's labels (clientCategory when stored, else the 13-value category).
 */
@Service
public class MetricsService {

    private static final Set<String> ESSENTIALS = Set.of("Household", "Groceries", "Transport", "Bills", "Medical");

    private final MongoTemplate mongo;
    private final EntityService entities;
    private final DashboardProperties properties;
    private final Clock clock;

    public MetricsService(MongoTemplate mongo, EntityService entities, DashboardProperties properties, Clock clock) {
        this.mongo = mongo;
        this.entities = entities;
        this.properties = properties;
        this.clock = clock;
    }

    public Map<String, Object> metrics(String userId, String period) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of(properties.getZone())));
        String p = period == null || period.isBlank() ? YearMonth.from(today).toString() : period.trim();
        List<Document> match = new ArrayList<>();
        Document filter = new Document("userId", userId).append("needsReview", false)
                .append("direction", new Document("$in", List.of("DEBIT", "CREDIT")))
                .append("date", new Document("$ne", null));
        if (!"all".equals(p)) {
            YearMonth ym;
            try {
                ym = YearMonth.parse(p);
            } catch (DateTimeParseException e) {
                throw new BadRequestException("month must be yyyy-MM or 'all'");
            }
            filter.put("date", new Document("$gte", ym.atDay(1).toString()).append("$lte", ym.atEndOfMonth().toString()));
        }
        match.add(new Document("$match", filter));
        match.add(new Document("$group", new Document("_id", new Document("d", "$direction")
                .append("base", "$category")
                .append("c", new Document("$ifNull", List.of("$clientCategory", "$category"))))
                .append("total", new Document("$sum", "$amount")).append("n", new Document("$sum", 1))));

        double income = 0, expenses = 0, invested = 0;
        long count = 0;
        Map<String, Double> byCategory = new LinkedHashMap<>();
        for (Document d : mongo.getCollection("transactions").aggregate(match)) {
            Document id = d.get("_id", Document.class);
            double total = ((Decimal128) d.get("total")).bigDecimalValue().doubleValue();
            count += ((Number) d.get("n")).longValue();
            if ("Transfer".equals(id.getString("base"))) continue;
            if ("CREDIT".equals(id.getString("d"))) {
                income += total;
            } else if ("Investment".equals(id.getString("base"))) {
                invested += total;
            } else {
                expenses += total;
                byCategory.merge(id.getString("c"), total, Double::sum);
            }
        }
        income = round(income); expenses = round(expenses); invested = round(invested);

        // balances / net worth parts
        Map<String, BigDecimal> moves = entities.balances(userId);
        double available = 0, cardDebt = 0, cardLimit = 0, cardCredit = 0;
        for (Map<String, Object> a : entities.list(userId, "accounts")) {
            double bal = ((BigDecimal) a.get("balance")).doubleValue();
            if ("credit".equals(a.get("type"))) {
                cardLimit += num(a.get("limit"));
                cardDebt += Math.max(0, -bal);
                cardCredit += Math.max(0, bal);
            } else {
                available += bal;
            }
        }
        double portfolio = 0, cost = 0, loans = 0, emi = 0, emergency = 0, budget = 0, budgetSpent = 0, goalCurrent = 0, goalTarget = 0;
        for (Map<String, Object> h : entities.list(userId, "holdings")) { portfolio += num(h.get("current")); cost += num(h.get("invested")); }
        for (Map<String, Object> l : entities.list(userId, "loans")) { loans += num(l.get("outstanding")); emi += num(l.get("emi")); }
        for (Map<String, Object> g : entities.list(userId, "goals")) {
            goalCurrent += num(g.get("current")); goalTarget += num(g.get("target"));
            if (Boolean.TRUE.equals(g.get("emergency"))) emergency += num(g.get("current"));
        }
        List<Map<String, Object>> budgets = entities.list(userId, "budgets");
        for (Map<String, Object> b : budgets) {
            budget += num(b.get("limit"));
            budgetSpent += byCategory.getOrDefault((String) b.get("category"), 0.0);
        }
        long overdue = entities.list(userId, "bills").stream()
                .filter(b -> !Boolean.TRUE.equals(b.get("paid")) && ((String) b.get("dueDate")).compareTo(today.toString()) < 0).count();

        double savings = round(income - expenses), cashSurplus = round(savings - invested);
        double essentials = ESSENTIALS.stream().mapToDouble(c -> byCategory.getOrDefault(c, 0.0)).sum();
        double coverage = essentials > 0 ? emergency / essentials : 0;
        UserProfile profile = mongo.findById(userId, UserProfile.class);
        double sip = profile == null || profile.getSipTarget() == null ? 0 : profile.getSipTarget().doubleValue();

        List<Map<String, Object>> components = new ArrayList<>();
        components.add(comp("Spending control", budgets.isEmpty() ? 0 : clamp(100 - Math.max(0, percent(budgetSpent, budget) - 80)), 20));
        components.add(comp("Savings rate", clamp(percent(savings, income) / 30 * 100), 20));
        components.add(comp("Emergency fund", clamp(coverage / 6 * 100), 20));
        components.add(comp("Debt health", clamp(100 - percent(cardDebt, cardLimit) * 0.5
                - (income > 0 ? percent(emi, income) : loans > 0 ? 100 : 0)), 15));
        components.add(comp("Investing consistency", clamp(percent(invested, sip)), 15));
        components.add(comp("Bills on time", clamp(100 - overdue * 20), 10));
        boolean hasData = count > 0;
        long score = hasData ? Math.round(components.stream()
                .mapToDouble(c -> ((Number) c.get("value")).doubleValue() * ((Number) c.get("weight")).doubleValue() / 100).sum()) : 0;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("period", p);
        out.put("income", income); out.put("expenses", expenses); out.put("invested", invested);
        out.put("savings", savings); out.put("cashSurplus", cashSurplus);
        Map<String, Double> rounded = new LinkedHashMap<>();
        byCategory.forEach((k, v) -> rounded.put(k, round(v)));
        out.put("byCategory", rounded);
        out.put("available", round(available)); out.put("cardDebt", round(cardDebt));
        out.put("cardLimit", round(cardLimit)); out.put("cardCredit", round(cardCredit));
        out.put("portfolio", round(portfolio)); out.put("cost", round(cost)); out.put("loans", round(loans));
        out.put("netWorth", round(available + portfolio + cardCredit - cardDebt - loans));
        out.put("essentials", round(essentials)); out.put("emergency", round(emergency)); out.put("coverage", coverage);
        out.put("budget", round(budget)); out.put("budgetSpent", round(budgetSpent)); out.put("overdue", overdue);
        out.put("score", score); out.put("components", components); out.put("hasData", hasData);
        out.put("savingsRate", percent(savings, income)); out.put("returnPct", percent(portfolio - cost, cost));
        out.put("goalCurrent", round(goalCurrent)); out.put("goalTarget", round(goalTarget));
        out.put("transactionCount", count);
        return out;
    }

    private static Map<String, Object> comp(String name, double value, int weight) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name); m.put("value", value); m.put("weight", weight);
        return m;
    }

    private static double num(Object o) { return o instanceof BigDecimal b ? b.doubleValue() : o instanceof Number n ? n.doubleValue() : 0; }
    private static double percent(double part, double whole) { return whole > 0 ? part / whole * 100 : 0; }
    private static double clamp(double v) { return Math.min(100, Math.max(0, v)); }
    private static double round(double v) { return Math.round((v + Math.ulp(1.0)) * 100) / 100.0; }
}
