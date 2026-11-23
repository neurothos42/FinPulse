package in.expensetrackerapp.entity;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Declarative field rules for the eight user-owned financial collections. They replicate the client's zod
 * schemas (client/src/types/models.ts) with server-side validation. Rules live here as data so adding a
 * field is a one-line change and there are no eight near-identical entity/service/controller sets.
 */
public final class EntitySchemas {

    public enum Kind { TEXT, MONEY, INT, BOOL, DATE, ENUM }

    /** managed = set by the server only (ignored in request bodies; kept on update). */
    public record Field(String name, Kind kind, boolean required, BigDecimal min, BigDecimal max,
                        boolean minExclusive, Object defaultValue, Set<String> allowed, boolean managed) {
    }

    public record Schema(String resource, String collection, List<Field> fields) {
    }

    static final BigDecimal TRILLION = new BigDecimal("1000000000000");
    static final Set<String> CATEGORIES = Set.of("Food", "Groceries", "Shopping", "Transport", "Entertainment",
            "Household", "Bills", "Personal", "Medical", "Travel", "Education", "Other");
    static final Set<String> ASSET_TYPES = Set.of("Mutual Funds", "Stocks", "ETF", "FD/RD", "Gold", "PPF", "EPF",
            "NPS", "Bonds");

    private static final Map<String, Schema> SCHEMAS = new LinkedHashMap<>();

    static {
        add("accounts", List.of(
                text("name", true), enumf("type", Set.of("bank", "cash", "credit")),
                new Field("openingBalance", Kind.MONEY, true, TRILLION.negate(), TRILLION, false, null, null, false),
                money("limit", false, false, BigDecimal.ZERO)));
        add("budgets", List.of(enumf("category", CATEGORIES), money("limit", true, true, null)));
        add("goals", List.of(
                text("name", true), text("icon", true), money("target", true, true, null),
                managed(money("current", false, false, BigDecimal.ZERO)), date("targetDate"),
                bool("emergency", false, Boolean.FALSE)));
        add("holdings", List.of(
                text("name", true), enumf("assetClass", ASSET_TYPES), money("invested", true, true, null),
                money("current", true, false, null), date("startDate")));
        add("bills", List.of(
                text("name", true), money("amount", true, true, null), date("dueDate"),
                bool("recurring", true, null), bool("paid", false, Boolean.FALSE), enumf("category", CATEGORIES),
                enumf("kind", Set.of("bill", "subscription"))));
        add("loans", List.of(
                text("name", true), money("principal", true, true, null), money("outstanding", true, false, null),
                money("emi", true, false, null),
                new Field("rate", Kind.MONEY, true, BigDecimal.ZERO, new BigDecimal("100"), false, null, null, false)));
        add("splits", List.of(
                text("name", true), money("amount", true, true, null),
                new Field("people", Kind.INT, true, new BigDecimal("2"), new BigDecimal("100"), false, null, null, false),
                bool("paidByMe", true, null), bool("settled", false, Boolean.FALSE), date("date")));
        add("festivals", List.of(
                text("name", true), date("targetDate"), money("budget", true, true, null),
                money("saved", false, false, BigDecimal.ZERO)));
    }

    private EntitySchemas() {
    }

    public static Schema get(String resource) {
        return SCHEMAS.get(resource);
    }

    public static Iterable<Schema> all() {
        return SCHEMAS.values();
    }

    private static void add(String resource, List<Field> fields) {
        SCHEMAS.put(resource, new Schema(resource, resource, fields));
    }

    private static Field text(String n, boolean req) {
        return new Field(n, Kind.TEXT, req, null, null, false, null, null, false);
    }

    private static Field enumf(String n, Set<String> allowed) {
        return new Field(n, Kind.ENUM, true, null, null, false, null, allowed, false);
    }

    private static Field date(String n) {
        return new Field(n, Kind.DATE, true, null, null, false, null, null, false);
    }

    private static Field bool(String n, boolean req, Object def) {
        return new Field(n, Kind.BOOL, req, null, null, false, def, null, false);
    }

    private static Field money(String n, boolean req, boolean positive, Object def) {
        return new Field(n, Kind.MONEY, req, BigDecimal.ZERO, TRILLION, positive, def, null, false);
    }

    private static Field managed(Field f) {
        return new Field(f.name(), f.kind(), f.required(), f.min(), f.max(), f.minExclusive(), f.defaultValue(),
                f.allowed(), true);
    }
}
