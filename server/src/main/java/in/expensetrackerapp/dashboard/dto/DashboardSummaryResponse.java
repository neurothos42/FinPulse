package in.expensetrackerapp.dashboard.dto;

import in.expensetrackerapp.transaction.dto.TransactionResponse;

import java.math.BigDecimal;
import java.util.List;

/**
 * Dashboard figures. Counting rules (also in API_DOCUMENTATION.md):
 * - Only verified rows count: needsReview=true rows are excluded and reported in pendingReviewCount.
 * - Category "Transfer" (both directions) is excluded from income and expenses; see excludedTransferCount.
 * - Refunds/cashback are credits that are NOT income. They are shown separately and DO count towards net cash flow.
 * - netCashFlow = totalIncome + totalRefunds - totalExpenses.
 * - Only the configured currency (or rows without a currency) is summed; others are counted in otherCurrencyCount.
 * - NAMING TRAP: totalIncome/totalExpenses/totalRefunds/netCashFlow are LIFETIME figures (no date bound); only
 *   "currentMonth*" and categoryExpenses are limited to {@code month}. currentMonthExpenses includes Investment
 *   debits; use currentMonthInvestments / currentMonthSpending to separate them.
 * - "currentMonth*" figures and categoryExpenses refer to {@code month} (default: the current calendar month).
 */
public record DashboardSummaryResponse(
        String month,
        String currency,
        BigDecimal totalIncome,
        BigDecimal totalExpenses,
        BigDecimal totalRefunds,
        BigDecimal netCashFlow,
        BigDecimal currentMonthIncome,
        BigDecimal currentMonthExpenses,
        BigDecimal currentMonthRefunds,
        BigDecimal currentMonthNetCashFlow,
        List<CategoryExpense> categoryExpenses,
        List<TransactionResponse> recentTransactions,
        long transactionCount,
        long pendingReviewCount,
        long excludedTransferCount,
        long otherCurrencyCount,
        BigDecimal currentMonthInvestments,
        BigDecimal currentMonthSpending,
        BigDecimal lifetimeIncome,
        BigDecimal lifetimeExpenses,
        BigDecimal lifetimeRefunds,
        BigDecimal lifetimeNetCashFlow) {

    /**
     * Original 16-component shape; kept so existing callers keep compiling. The two added fields are
     * derived from the existing ones: investments come from the "Investment" bucket of categoryExpenses
     * and spending is expenses without investments (M5b: Home shows "invested" separately).
     */
    public DashboardSummaryResponse(String month, String currency, BigDecimal totalIncome, BigDecimal totalExpenses,
                                    BigDecimal totalRefunds, BigDecimal netCashFlow, BigDecimal currentMonthIncome,
                                    BigDecimal currentMonthExpenses, BigDecimal currentMonthRefunds,
                                    BigDecimal currentMonthNetCashFlow, List<CategoryExpense> categoryExpenses,
                                    List<TransactionResponse> recentTransactions, long transactionCount,
                                    long pendingReviewCount, long excludedTransferCount, long otherCurrencyCount) {
        this(month, currency, totalIncome, totalExpenses, totalRefunds, netCashFlow, currentMonthIncome,
                currentMonthExpenses, currentMonthRefunds, currentMonthNetCashFlow, categoryExpenses,
                recentTransactions, transactionCount, pendingReviewCount, excludedTransferCount, otherCurrencyCount,
                investments(categoryExpenses), currentMonthExpenses.subtract(investments(categoryExpenses)),
                totalIncome, totalExpenses, totalRefunds, netCashFlow);
    }

    private static BigDecimal investments(List<CategoryExpense> categories) {
        return categories == null ? BigDecimal.ZERO.setScale(2) : categories.stream()
                .filter(c -> "Investment".equals(c.category()))
                .map(CategoryExpense::total).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    public record CategoryExpense(String category, BigDecimal total, long transactionCount) {
    }
}
