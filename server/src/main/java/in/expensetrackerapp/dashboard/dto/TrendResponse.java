package in.expensetrackerapp.dashboard.dto;

import java.math.BigDecimal;
import java.util.List;

/** Month-by-month totals for charts. Same inclusion rules as the summary; investments are separate from expenses. */
public record TrendResponse(String from, String to, String currency, List<Point> points) {

    public record Point(String month, BigDecimal income, BigDecimal expenses, BigDecimal investments,
                        BigDecimal refunds, BigDecimal net) {
    }
}
