import {
  accountSchema,
  assetTypes,
  billSchema,
  budgetSchema,
  categories,
  festivalSchema,
  goalSchema,
  holdingSchema,
  loanSchema,
  splitSchema,
  type EntityKey,
} from "../types/models";
export type FormField = {
  key: string;
  label: string;
  kind?: "number" | "date" | "select";
  options?: readonly string[];
  hint?: string;
};
export const forms: Record<
  EntityKey,
  {
    title: string;
    schema:
      | typeof accountSchema
      | typeof billSchema
      | typeof budgetSchema
      | typeof festivalSchema
      | typeof goalSchema
      | typeof holdingSchema
      | typeof loanSchema
      | typeof splitSchema;
    fields: FormField[];
    note?: string;
  }
> = {
  accounts: {
    title: "Account",
    schema: accountSchema,
    fields: [
      { key: "name", label: "Account name" },
      {
        key: "type",
        label: "Account type",
        kind: "select",
        options: ["bank", "cash", "credit"],
      },
      {
        key: "openingBalance",
        label: "Opening balance (₹)",
        kind: "number",
        hint: "For a credit card, enter a negative opening balance for money owed.",
      },
      { key: "limit", label: "Credit limit (₹, cards only)", kind: "number" },
    ],
    note: "Opening balance is the balance before all transactions in this app. Editing it changes the calculated current balance.",
  },
  budgets: {
    title: "Budget",
    schema: budgetSchema,
    fields: [
      {
        key: "category",
        label: "Category",
        kind: "select",
        options: categories,
      },
      { key: "limit", label: "Monthly limit (₹)", kind: "number" },
    ],
  },
  goals: {
    title: "Financial goal",
    schema: goalSchema,
    fields: [
      { key: "name", label: "Goal name" },
      {
        key: "icon",
        label: "Icon",
        kind: "select",
        options: ["🎯", "🛟", "🚗", "✈️", "💻", "🏠", "📚"],
      },
      { key: "target", label: "Target amount (₹)", kind: "number" },
      { key: "current", label: "Already saved (₹)", kind: "number" },
      { key: "targetDate", label: "Target date (YYYY-MM-DD)", kind: "date" },
      {
        key: "emergency",
        label: "Emergency fund",
        kind: "select",
        options: ["No", "Yes"],
      },
    ],
    note: "Goal savings are earmarked amounts, not new assets. They are not added again when calculating net worth.",
  },
  holdings: {
    title: "Investment holding",
    schema: holdingSchema,
    fields: [
      { key: "name", label: "Holding name" },
      {
        key: "assetClass",
        label: "Investment type",
        kind: "select",
        options: assetTypes,
      },
      { key: "invested", label: "Total invested (₹)", kind: "number" },
      { key: "current", label: "Current value (₹)", kind: "number" },
      { key: "startDate", label: "Start date (YYYY-MM-DD)", kind: "date" },
    ],
    note: "This updates a portfolio valuation. To record a cash outflow, add an Investment transaction separately. Prices are entered manually.",
  },
  bills: {
    title: "Bill or subscription",
    schema: billSchema,
    fields: [
      { key: "name", label: "Name" },
      { key: "amount", label: "Amount (₹)", kind: "number" },
      { key: "dueDate", label: "Due date (YYYY-MM-DD)", kind: "date" },
      {
        key: "category",
        label: "Expense category",
        kind: "select",
        options: categories,
      },
      {
        key: "kind",
        label: "Kind",
        kind: "select",
        options: ["bill", "subscription"],
      },
      {
        key: "recurring",
        label: "Repeats monthly",
        kind: "select",
        options: ["Yes", "No"],
      },
    ],
    note: "Record payment adds one expense to the selected account. Monthly bills then advance to the next due date. No real payment is sent.",
  },
  loans: {
    title: "Loan",
    schema: loanSchema,
    fields: [
      { key: "name", label: "Loan name" },
      { key: "principal", label: "Original principal (₹)", kind: "number" },
      {
        key: "outstanding",
        label: "Outstanding principal (₹)",
        kind: "number",
      },
      { key: "emi", label: "Monthly EMI (₹)", kind: "number" },
      { key: "rate", label: "Annual interest (%)", kind: "number" },
    ],
    note: "Loan balances are manual snapshots. Update the outstanding principal after checking your lender statement.",
  },
  splits: {
    title: "Shared expense",
    schema: splitSchema,
    fields: [
      { key: "name", label: "What was it for?" },
      { key: "amount", label: "Total amount (₹)", kind: "number" },
      {
        key: "people",
        label: "Number of people (including you)",
        kind: "number",
      },
      {
        key: "paidByMe",
        label: "Did you pay the whole bill?",
        kind: "select",
        options: ["Yes", "No"],
      },
      { key: "date", label: "Date (YYYY-MM-DD)", kind: "date" },
    ],
    note: "Equal split. This is a settlement tracker; add the original expense to your ledger separately. Settlement does not send money.",
  },
  festivals: {
    title: "Festival plan",
    schema: festivalSchema,
    fields: [
      { key: "name", label: "Occasion" },
      { key: "targetDate", label: "Planned date (YYYY-MM-DD)", kind: "date" },
      { key: "budget", label: "Planned spend (₹)", kind: "number" },
      { key: "saved", label: "Already set aside (₹)", kind: "number" },
    ],
    note: "The amount set aside is a planning record and is not counted as an additional asset.",
  },
};
