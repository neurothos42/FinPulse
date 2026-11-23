package in.expensetrackerapp.entity;

import in.expensetrackerapp.common.exception.BadRequestException;
import in.expensetrackerapp.entity.EntitySchemas.Field;
import in.expensetrackerapp.entity.EntitySchemas.Kind;
import in.expensetrackerapp.entity.EntitySchemas.Schema;
import org.bson.Document;
import org.bson.types.Decimal128;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Set;

/** Validates a request body against a Schema and returns the cleaned document fields (money as Decimal128). */
final class EntityValidator {

    private EntityValidator() {
    }

    /**
     * @param existing current stored document on update (null on create). Body values overlay it; managed fields
     *                 are never taken from the body.
     */
    static Document clean(Schema schema, Map<String, Object> body, Document existing) {
        Document out = new Document();
        for (Field f : schema.fields()) {
            Object raw = f.managed() ? null : body.get(f.name());
            if (raw == null && existing != null && existing.get(f.name()) != null) {
                out.put(f.name(), existing.get(f.name()));
                continue;
            }
            if (raw == null) {
                if (f.defaultValue() != null) {
                    out.put(f.name(), f.kind() == Kind.MONEY
                            ? new Decimal128(((BigDecimal) f.defaultValue()).setScale(2)) : f.defaultValue());
                } else if (f.required()) {
                    throw new BadRequestException(f.name() + " is required");
                }
                continue;
            }
            out.put(f.name(), convert(f, raw));
        }
        return out;
    }

    private static Object convert(Field f, Object raw) {
        switch (f.kind()) {
            case TEXT: {
                if (!(raw instanceof String s) || s.strip().isEmpty() || s.strip().length() > 200) {
                    throw new BadRequestException(f.name() + " must be between 1 and 200 characters");
                }
                return s.strip();
            }
            case BOOL: {
                if (!(raw instanceof Boolean b)) {
                    throw new BadRequestException(f.name() + " must be true or false");
                }
                return b;
            }
            case DATE: {
                try {
                    return LocalDate.parse(String.valueOf(raw)).toString();
                } catch (DateTimeParseException e) {
                    throw new BadRequestException(f.name() + " must be a valid date (yyyy-MM-dd)");
                }
            }
            case ENUM: {
                String s = String.valueOf(raw);
                Set<String> allowed = f.allowed();
                if (!allowed.contains(s)) {
                    throw new BadRequestException(f.name() + " must be one of " + allowed.stream().sorted().toList());
                }
                return s;
            }
            case INT: {
                BigDecimal n = number(f.name(), raw);
                if (n.stripTrailingZeros().scale() > 0) {
                    throw new BadRequestException(f.name() + " must be a whole number");
                }
                range(f, n);
                return n.intValueExact();
            }
            case MONEY:
            default: {
                BigDecimal n = number(f.name(), raw);
                if (n.stripTrailingZeros().scale() > 2) {
                    throw new BadRequestException(f.name() + " must have at most 2 decimal places");
                }
                range(f, n);
                return new Decimal128(n.setScale(2, RoundingMode.UNNECESSARY));
            }
        }
    }

    static BigDecimal number(String name, Object raw) {
        try {
            if (raw instanceof Number || raw instanceof String) {
                return new BigDecimal(raw.toString().trim());
            }
        } catch (NumberFormatException ignored) {
            // falls through
        }
        throw new BadRequestException(name + " must be a number");
    }

    private static void range(Field f, BigDecimal n) {
        if (f.min() != null && (f.minExclusive() ? n.compareTo(f.min()) <= 0 : n.compareTo(f.min()) < 0)) {
            throw new BadRequestException(f.name() + (f.minExclusive() ? " must be greater than " : " must be at least ") + f.min().stripTrailingZeros().toPlainString());
        }
        if (f.max() != null && n.compareTo(f.max()) > 0) {
            throw new BadRequestException(f.name() + " must be at most " + f.max().toPlainString());
        }
    }

    /** Goal plan: kind/frequency/optionId rules copied from the client's goalSchema. */
    static Document plan(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) {
            throw new BadRequestException("plan must be an object");
        }
        String kind = str(m, "kind");
        String frequency = str(m, "frequency");
        Object option = m.get("optionId");
        if (!Set.of("savings", "investment").contains(kind)) throw new BadRequestException("plan.kind must be savings or investment");
        if (!Set.of("daily", "weekly", "monthly").contains(frequency)) throw new BadRequestException("plan.frequency must be daily, weekly or monthly");
        String optionId = option == null ? null : String.valueOf(option);
        if (optionId != null && !Set.of("rd", "fd", "debt", "hybrid", "index").contains(optionId)) {
            throw new BadRequestException("plan.optionId is not a known option");
        }
        if ("investment".equals(kind) && (optionId == null || !"monthly".equals(frequency))) {
            throw new BadRequestException("Choose an investment option (monthly) for an investment plan");
        }
        if ("savings".equals(kind) && optionId != null) {
            throw new BadRequestException("Savings plans cannot contain an investment option");
        }
        Document d = new Document("kind", kind).append("frequency", frequency);
        if (optionId != null) d.append("optionId", optionId);
        d.append("amount", planMoney(m, "amount")).append("finalAmount", planMoney(m, "finalAmount"));
        BigDecimal payments = number("plan.payments", m.get("payments"));
        if (payments.signum() < 0 || payments.stripTrailingZeros().scale() > 0) {
            throw new BadRequestException("plan.payments must be a whole number >= 0");
        }
        d.append("payments", payments.intValueExact());
        try {
            d.append("startDate", LocalDate.parse(str(m, "startDate")).toString());
        } catch (DateTimeParseException e) {
            throw new BadRequestException("plan.startDate must be a valid date (yyyy-MM-dd)");
        }
        return d;
    }

    private static Decimal128 planMoney(Map<?, ?> m, String key) {
        BigDecimal n = number("plan." + key, m.get(key));
        if (n.signum() < 0 || n.compareTo(EntitySchemas.TRILLION) > 0 || n.stripTrailingZeros().scale() > 2) {
            throw new BadRequestException("plan." + key + " must be between 0 and 1e12 with at most 2 decimals");
        }
        return new Decimal128(n.setScale(2, RoundingMode.UNNECESSARY));
    }

    private static String str(Map<?, ?> m, String key) {
        Object v = m.get(key);
        if (v == null) throw new BadRequestException("plan." + key + " is required");
        return String.valueOf(v);
    }
}
