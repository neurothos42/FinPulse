package in.expensetrackerapp.transaction.model;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * D1 (interim): the client has 14 categories, the server 13. Rather than changing the pinned enum, the 4 client-only
 * labels are accepted on create and stored under the closest server category (lossy, documented). Does not change
 * the 13-value contract shared with the Python worker and the ML labels.
 */
public final class CategoryAliases {

    public static final Map<String, TransactionCategory> ALIASES = Map.of(
            "groceries", TransactionCategory.FOOD,
            "household", TransactionCategory.BILLS,
            "personal", TransactionCategory.SHOPPING,
            "medical", TransactionCategory.HEALTHCARE,
            "income", TransactionCategory.SALARY);

    private CategoryAliases() {
    }

    /** The client label (title case) when the input is a client-only category, else null. */
    public static String clientLabel(String input) {
        if (input == null) return null;
        String key = input.trim().toLowerCase(Locale.ROOT);
        return ALIASES.containsKey(key) ? Character.toUpperCase(key.charAt(0)) + key.substring(1) : null;
    }

    /** Canonical lookup first, then client aliases. */
    public static Optional<TransactionCategory> resolve(String input) {
        Optional<TransactionCategory> exact = TransactionCategory.fromValue(input);
        if (exact.isPresent() || input == null) {
            return exact;
        }
        return Optional.ofNullable(ALIASES.get(input.trim().toLowerCase(Locale.ROOT)));
    }
}
