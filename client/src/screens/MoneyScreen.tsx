import { useState } from "react";
import { View } from "react-native";
import { Plus, Upload } from "lucide-react-native";
import { useApp } from "../store/AppProvider";
import { accountBalance, selectMetrics } from "../store/selectors";
import { useTheme } from "../theme/ThemeProvider";
import { useNav } from "../hooks/useNav";
import {
  Screen,
  T,
  Row,
  Card,
  Section,
  Button,
  Chips,
  Progress,
  Select,
  Field,
  Empty,
  IconButton,
} from "../components/ui";
import { TransactionRow } from "../components/TransactionRow";
import { inr, percent } from "../utils/format";
import { monthKey, monthLabel, dateLabel } from "../utils/date";
import { categoryIcons, chartColors } from "../constants/catalog";
type Panel =
  "Analysis" | "Activity" | "Budgets" | "Accounts" | "Bills" | "Loans";
export function MoneyScreen() {
  const { state } = useApp(),
    c = useTheme(),
    nav = useNav();
  const [panel, setPanel] = useState<Panel>("Activity"),
    [period, setPeriod] = useState(monthKey()),
    [filter, setFilter] = useState("All"),
    [categoryFilter, setCategoryFilter] = useState<string | null>(null),
    [query, setQuery] = useState(""),
    [limit, setLimit] = useState(25);
  const m = selectMetrics(state, period),
    current = selectMetrics(state);
  const months = [
    ...new Set([
      monthKey(),
      ...state.transactions.map((t) => t.date.slice(0, 7)),
    ]),
  ]
    .sort()
    .reverse();
  const rows = [...m.txs]
    .sort((a, b) => b.date.localeCompare(a.date))
    .filter(
      (t) =>
        (filter === "All" ||
          (filter === "Income" && t.type === "income") ||
          (filter === "Expense" && t.type === "expense") ||
          (filter === "Investment" && t.type === "investment") ||
          (filter === "Transfer" && t.type === "transfer") ||
          (filter === "UPI" && t.method === "UPI") ||
          (filter === "Card" && t.method.includes("Card"))) &&
        (!categoryFilter || t.category === categoryFilter) &&
        `${t.merchant} ${t.category} ${t.notes}`
          .toLowerCase()
          .includes(query.toLowerCase()),
    );
  return (
    <Screen
      tab
      title="Money"
      subtitle="Your everyday money, organized."
      right={
        <IconButton
          label="Add transaction"
          onPress={() => nav.navigate("Expense")}
        >
          <Plus size={22} color={c.green} />
        </IconButton>
      }
    >
      <Chips
        options={
          [
            "Activity",
            "Analysis",
            "Budgets",
            "Accounts",
            "Bills",
            "Loans",
          ] as const
        }
        value={panel}
        onChange={setPanel}
      />
      {panel === "Activity" ? (
        <>
          <Select
            label="Period"
            value={period}
            options={[
              { label: "All time", value: "all" },
              ...months.map((k) => ({ label: monthLabel(k), value: k })),
            ]}
            onChange={(v) => {
              setPeriod(v);
              setLimit(25);
            }}
          />
          <Card>
            <Row>
              {[
                { label: "Income", value: m.income },
                { label: "Expenses", value: m.expenses },
                { label: "Savings", value: m.savings },
              ].map((s) => (
                <View key={s.label} style={{ flex: 1 }}>
                  <T muted size={10}>
                    {s.label}
                  </T>
                  <T bold size={16}>
                    {inr(s.value)}
                  </T>
                </View>
              ))}
            </Row>
          </Card>
          <Field
            label="Search transactions"
            value={query}
            onChangeText={(v) => {
              setQuery(v);
              setCategoryFilter(null);
              setLimit(25);
            }}
            placeholder="Merchant, category or note"
          />
          {categoryFilter ? (
            <Button
              title={`Clear ${categoryFilter} filter`}
              variant="secondary"
              style={{ marginBottom: 12 }}
              onPress={() => setCategoryFilter(null)}
            />
          ) : null}
          <Chips
            options={[
              "All",
              "Income",
              "Expense",
              "Investment",
              "Transfer",
              "UPI",
              "Card",
            ]}
            value={filter}
            onChange={setFilter}
          />
          <Row>
            <Button
              title="Add expense"
              onPress={() => nav.navigate("Expense")}
              style={{ flex: 1 }}
            />
            <Button
              title="Import"
              variant="secondary"
              icon={<Upload size={16} color={c.green} />}
              onPress={() => nav.navigate("Import")}
              style={{ flex: 1 }}
            />
          </Row>
          <Button
            title="Statements & receipts"
            variant="secondary"
            style={{ marginTop: 10 }}
            onPress={() => nav.navigate("Documents")}
          />
          <Section
            title={`${rows.length} transactions`}
            action="Analytics"
            onPress={() => nav.navigate("Tools", { tool: "reports" })}
          />
          <Card>
            {rows.length ? (
              rows
                .slice(0, limit)
                .map((t) => (
                  <TransactionRow
                    key={t.id}
                    transaction={t}
                    onPress={() => nav.navigate("Transaction", { id: t.id })}
                  />
                ))
            ) : (
              <Empty
                title="No matching transactions"
                body="Try a different filter or add your first entry."
              />
            )}
          </Card>
          {rows.length > limit ? (
            <Button
              title="Show more"
              variant="secondary"
              onPress={() => setLimit((n) => n + 25)}
            />
          ) : null}
        </>
      ) : null}
      {panel === "Analysis" ? (
        <>
          <Select
            label="Analysis period"
            value={period}
            options={[
              { label: "All time", value: "all" },
              ...months.map((k) => ({ label: monthLabel(k), value: k })),
            ]}
            onChange={setPeriod}
          />
          <Section title="Spending by category" />
          <Card>
            <T muted size={12}>
              Total expenses
            </T>
            <T size={30} bold>
              {inr(m.expenses)}
            </T>
            <T muted size={12}>
              Income, investments and transfers are excluded.
            </T>
          </Card>
          {m.expenses ? (
            <Card>
              <View
                style={{
                  flexDirection: "row",
                  height: 16,
                  borderRadius: 9,
                  overflow: "hidden",
                  marginBottom: 20,
                }}
              >
                {Object.entries(m.byCategory)
                  .sort((a, b) => b[1] - a[1])
                  .map(([category, value], i) => (
                    <View
                      key={category}
                      style={{
                        flex: value / m.expenses,
                        backgroundColor: chartColors[i % chartColors.length],
                      }}
                    />
                  ))}
              </View>
              {Object.entries(m.byCategory)
                .sort((a, b) => b[1] - a[1])
                .map(([category, value], i) => (
                  <View key={category} style={{ marginBottom: 20 }}>
                    <Row
                      style={{
                        justifyContent: "space-between",
                        marginBottom: 8,
                      }}
                    >
                      <T bold style={{ flex: 1 }}>
                        {categoryIcons[category]} {category}
                      </T>
                      <T bold>{inr(value)}</T>
                    </Row>
                    <Progress
                      value={percent(value, m.expenses)}
                      color={chartColors[i % chartColors.length]}
                    />
                    <T muted size={12} style={{ marginTop: 6 }}>
                      {percent(value, m.expenses).toFixed(1)}% ·{" "}
                      {
                        m.txs.filter(
                          (t) =>
                            t.type === "expense" && t.category === category,
                        ).length
                      }{" "}
                      transactions
                    </T>
                    <Button
                      title={`View ${category} expenses`}
                      variant="secondary"
                      style={{ marginTop: 8 }}
                      onPress={() => {
                        setCategoryFilter(category);
                        setQuery("");
                        setFilter("Expense");
                        setLimit(25);
                        setPanel("Activity");
                      }}
                    />
                  </View>
                ))}
            </Card>
          ) : (
            <Empty
              title="No expenses in this period"
              body="Add an expense or choose another month to see the category breakdown."
            />
          )}
        </>
      ) : null}
      {panel === "Budgets" ? (
        <>
          <Section
            title="This month’s budgets"
            action="Add budget"
            onPress={() => nav.navigate("Entity", { kind: "budgets" })}
          />
          <Card>
            <T muted size={12}>
              Budgeted category spending
            </T>
            <T size={27} bold>
              {inr(current.budgetSpent)}{" "}
              <T size={13} muted>
                / {inr(current.budget)}
              </T>
            </T>
            <Progress value={percent(current.budgetSpent, current.budget)} />
          </Card>
          {state.budgets.map((b) => {
            const spent = current.byCategory[b.category] ?? 0;
            return (
              <Card
                key={b.id}
                onPress={() =>
                  nav.navigate("Entity", { kind: "budgets", id: b.id })
                }
              >
                <Row
                  style={{ justifyContent: "space-between", marginBottom: 12 }}
                >
                  <T bold>
                    {categoryIcons[b.category]} {b.category}
                  </T>
                  <T size={12} muted>
                    {inr(spent)} / {inr(b.limit)}
                  </T>
                </Row>
                <Progress
                  value={percent(spent, b.limit)}
                  color={spent > b.limit ? "#f04438" : undefined}
                />
                <T
                  size={11}
                  style={{
                    color: spent > b.limit ? c.red : c.muted,
                    marginTop: 8,
                  }}
                >
                  {inr(Math.abs(b.limit - spent))}{" "}
                  {spent > b.limit ? "over budget" : "left to spend"}
                </T>
              </Card>
            );
          })}
          {!state.budgets.length ? (
            <Empty
              title="Give every category a plan"
              body="Add a monthly budget to start tracking limits."
            />
          ) : null}
        </>
      ) : null}
      {panel === "Accounts" ? (
        <>
          <Section
            title="Your accounts"
            action="Add account"
            onPress={() => nav.navigate("Entity", { kind: "accounts" })}
          />
          {state.accounts.map((a) => {
            const balance = accountBalance(state, a.id);
            return (
              <Card
                key={a.id}
                onPress={() =>
                  nav.navigate("Entity", { kind: "accounts", id: a.id })
                }
              >
                <Row style={{ justifyContent: "space-between" }}>
                  <View style={{ flex: 1 }}>
                    <T bold>
                      {a.type === "credit"
                        ? "💳"
                        : a.type === "cash"
                          ? "💵"
                          : "🏦"}{" "}
                      {a.name}
                    </T>
                    <T muted size={11}>
                      {a.type === "credit" ? "Credit card" : "Manual account"}
                    </T>
                  </View>
                  <T size={20} bold>
                    {inr(a.type === "credit" ? -balance : balance)}
                  </T>
                </Row>
                {a.type === "credit" ? (
                  <View style={{ marginTop: 14 }}>
                    <Progress value={percent(Math.max(0, -balance), a.limit)} />
                    <T size={11} muted style={{ marginTop: 7 }}>
                      Outstanding · Limit {inr(a.limit)}
                    </T>
                  </View>
                ) : null}
              </Card>
            );
          })}
          <T muted size={12}>
            Balances use your opening balance plus all recorded transactions.
            Bank syncing is not connected.
          </T>
        </>
      ) : null}
      {panel === "Bills" ? (
        <>
          <Section
            title="Bills & subscriptions"
            action="Add bill"
            onPress={() => nav.navigate("Entity", { kind: "bills" })}
          />
          <Card>
            <T muted size={12}>
              Monthly subscriptions
            </T>
            <T bold size={25}>
              {inr(
                state.bills
                  .filter((b) => b.kind === "subscription" && b.recurring)
                  .reduce((s, b) => s + b.amount, 0),
              )}
            </T>
          </Card>
          {state.bills.map((b) => (
            <Card
              key={b.id}
              onPress={() =>
                nav.navigate("Entity", { kind: "bills", id: b.id })
              }
            >
              <Row style={{ justifyContent: "space-between" }}>
                <View style={{ flex: 1 }}>
                  <T bold>
                    {b.kind === "subscription" ? "🔁" : "🧾"} {b.name}
                  </T>
                  <T size={11} muted>
                    {b.paid ? "Paid" : `Due ${dateLabel(b.dueDate)}`} ·{" "}
                    {b.recurring ? "Monthly" : "Once"}
                  </T>
                </View>
                <T bold>{inr(b.amount)}</T>
              </Row>
            </Card>
          ))}
          {!state.bills.length ? (
            <Empty
              title="No bills yet"
              body="Add a bill to track its due date and payment."
            />
          ) : null}
        </>
      ) : null}
      {panel === "Loans" ? (
        <>
          <Section
            title="Loans"
            action="Add loan"
            onPress={() => nav.navigate("Entity", { kind: "loans" })}
          />
          {state.loans.map((l) => (
            <Card
              key={l.id}
              onPress={() =>
                nav.navigate("Entity", { kind: "loans", id: l.id })
              }
            >
              <T bold>{l.name}</T>
              <T bold size={25} style={{ marginTop: 8 }}>
                {inr(l.outstanding)}
              </T>
              <T muted size={12}>
                Outstanding · {l.rate}% annual rate
              </T>
              <Progress
                value={percent(
                  Math.max(0, l.principal - l.outstanding),
                  l.principal,
                )}
              />
              <T size={12} style={{ marginTop: 8 }}>
                Monthly EMI {inr(l.emi)}
              </T>
            </Card>
          ))}
          <Button
            title="Open EMI calculator"
            variant="secondary"
            onPress={() => nav.navigate("Calculator", { kind: "EMI" })}
          />
        </>
      ) : null}
    </Screen>
  );
}
