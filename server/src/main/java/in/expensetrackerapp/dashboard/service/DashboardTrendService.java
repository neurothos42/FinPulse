package in.expensetrackerapp.dashboard.service;

import in.expensetrackerapp.common.exception.BadRequestException;
import in.expensetrackerapp.dashboard.config.DashboardProperties;
import in.expensetrackerapp.dashboard.dto.TrendResponse;
import in.expensetrackerapp.transaction.model.Transaction;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** M5(c): GET /api/dashboard/trend. Aggregated inside MongoDB; the user id is bound as a value, never concatenated. */
@Service
public class DashboardTrendService {

    static final int MAX_MONTHS = 36;
    static final int DEFAULT_MONTHS = 6;
    private static final Pattern MONTH_FORMAT = Pattern.compile("\\d{4}-(0[1-9]|1[0-2])");

    private final MongoTemplate mongoTemplate;
    private final DashboardProperties properties;
    private final Clock clock;

    public DashboardTrendService(MongoTemplate mongoTemplate, DashboardProperties properties, Clock clock) {
        this.mongoTemplate = mongoTemplate;
        this.properties = properties;
        this.clock = clock;
    }

    public TrendResponse trend(String userId, String fromParam, String toParam) {
        YearMonth to = toParam == null || toParam.isBlank()
                ? YearMonth.now(clock.withZone(ZoneId.of(properties.getZone()))) : parse(toParam);
        YearMonth from = fromParam == null || fromParam.isBlank() ? to.minusMonths(DEFAULT_MONTHS - 1L) : parse(fromParam);
        if (from.isAfter(to)) {
            throw new BadRequestException("'from' must not be after 'to'");
        }
        if (ChronoUnit.MONTHS.between(from, to) + 1 > MAX_MONTHS) {
            throw new BadRequestException("The range must not exceed " + MAX_MONTHS + " months");
        }
        String currency = properties.getCurrency();

        Map<String, Document> byMonth = new HashMap<>();
        for (Document d : run(userId, currency, from.atDay(1).toString(), to.atEndOfMonth().toString())) {
            byMonth.put(d.getString("_id"), d);
        }
        List<TrendResponse.Point> points = new ArrayList<>();
        for (YearMonth m = from; !m.isAfter(to); m = m.plusMonths(1)) {
            Document d = byMonth.getOrDefault(m.toString(), new Document());
            BigDecimal income = DashboardService.money(d.get("income"));
            BigDecimal expenses = DashboardService.money(d.get("expenses"));
            BigDecimal investments = DashboardService.money(d.get("investments"));
            BigDecimal refunds = DashboardService.money(d.get("refunds"));
            points.add(new TrendResponse.Point(m.toString(), income, expenses, investments, refunds,
                    income.add(refunds).subtract(expenses).subtract(investments)));
        }
        return new TrendResponse(from.toString(), to.toString(), currency, points);
    }

    private List<Document> run(String userId, String currency, String start, String end) {
        Document refundCond = new Document("$and", List.of(
                new Document("$eq", List.of("$direction", "CREDIT")),
                new Document("$eq", List.of("$pipelineCategory", "Refund / Cashback")),
                new Document("$eq", List.of("$category", "Other"))));
        Document isDebit = new Document("$eq", List.of("$direction", "DEBIT"));
        Document isInvestment = new Document("$eq", List.of("$category", "Investment"));
        Document isCredit = new Document("$eq", List.of("$direction", "CREDIT"));

        List<Document> pipeline = List.of(
                new Document("$match", new Document("userId", userId)
                        .append("needsReview", false)
                        .append("direction", new Document("$in", List.of("DEBIT", "CREDIT")))
                        .append("category", new Document("$ne", "Transfer"))
                        .append("date", new Document("$gte", start).append("$lte", end))
                        .append("$or", List.of(new Document("currency", currency), new Document("currency", null)))),
                new Document("$group", new Document("_id", new Document("$substrBytes", List.of("$date", 0, 7)))
                        .append("income", sum(new Document("$and", List.of(isCredit, new Document("$not", List.of(refundCond))))))
                        .append("refunds", sum(refundCond))
                        .append("expenses", sum(new Document("$and", List.of(isDebit, new Document("$not", List.of(isInvestment))))))
                        .append("investments", sum(new Document("$and", List.of(isDebit, isInvestment))))),
                new Document("$sort", new Document("_id", 1)));
        String collection = mongoTemplate.getCollectionName(Transaction.class);
        return mongoTemplate.getCollection(collection).aggregate(pipeline).into(new ArrayList<>());
    }

    private static Document sum(Document condition) {
        return new Document("$sum", new Document("$cond", List.of(condition, "$amount", 0)));
    }

    private static YearMonth parse(String value) {
        String v = value.trim();
        if (!MONTH_FORMAT.matcher(v).matches()) {
            throw new BadRequestException("month must be in yyyy-MM format");
        }
        try {
            return YearMonth.parse(v);
        } catch (DateTimeParseException e) {
            throw new BadRequestException("month must be in yyyy-MM format");
        }
    }
}
