import {
  ageRanges,
  employments,
  incomeSources,
  riskAnswers,
} from "../data/onboarding";
import { z } from "zod";

const money = z.number().finite().min(0).max(1e12);
const text = z.string().trim().min(1).max(200);
export const dateSchema = z.iso.date();
export const categories = [
  "Food",
  "Groceries",
  "Shopping",
  "Transport",
  "Entertainment",
  "Household",
  "Bills",
  "Personal",
  "Medical",
  "Travel",
  "Education",
  "Other",
] as const;
export const methods = [
  "UPI",
  "Credit Card",
  "Debit Card",
  "Cash",
  "Bank Transfer",
] as const;
export const risks = ["Conservative", "Moderate", "Aggressive"] as const;
export const assetTypes = [
  "Mutual Funds",
  "Stocks",
  "ETF",
  "FD/RD",
  "Gold",
  "PPF",
  "EPF",
  "NPS",
  "Bonds",
] as const;
export const transactionSchema = z.object({
  id: text,
  merchant: text,
  amount: money.positive(),
  type: z.enum(["income", "expense", "investment", "transfer"]),
  category: z.enum([...categories, "Income", "Investment"]),
  method: z.enum(methods),
  accountId: text,
  toAccountId: text.optional(),
  date: dateSchema,
  notes: z.string().max(2000).default(""),
  source: z
    .enum(["manual", "import", "bill", "voice", "receipt"])
    .default("manual"),
  importKey: z.string().optional(),
  billId: z.string().optional(),
  billDueDate: dateSchema.optional(),
  incomeSource: z
    .enum(["Salary", "Bonus", "Freelance", "Gift", "Refund", "Other"])
    .optional(),
});
export const accountSchema = z.object({
  id: text,
  name: text,
  type: z.enum(["bank", "cash", "credit"]),
  openingBalance: z.number().finite().min(-1e12).max(1e12),
  limit: money.default(0),
});
export const budgetSchema = z.object({
  id: text,
  category: z.enum(categories),
  limit: money.positive(),
});
export const goalSchema = z.object({
  id: text,
  name: text,
  icon: text,
  target: money.positive(),
  current: money,
  targetDate: dateSchema,
  emergency: z.boolean().default(false),
  plan: z
    .object({
      kind: z.enum(["savings", "investment"]),
      frequency: z.enum(["daily", "weekly", "monthly"]),
      optionId: z.enum(["rd", "fd", "debt", "hybrid", "index"]).optional(),
      amount: money,
      payments: z.number().int().min(0),
      finalAmount: money,
      startDate: dateSchema,
    })
    .refine(
      (p) =>
        p.kind !== "investment" || (!!p.optionId && p.frequency === "monthly"),
      "Choose an investment option for an investment plan",
    )
    .refine(
      (p) => p.kind !== "savings" || !p.optionId,
      "Savings plans cannot contain an investment option",
    )
    .optional(),
});
export const holdingSchema = z.object({
  id: text,
  name: text,
  assetClass: z.enum(assetTypes),
  invested: money.positive(),
  current: money,
  startDate: dateSchema,
});
export const billSchema = z.object({
  id: text,
  name: text,
  amount: money.positive(),
  dueDate: dateSchema,
  recurring: z.boolean(),
  paid: z.boolean(),
  category: z.enum(categories),
  kind: z.enum(["bill", "subscription"]),
  lastTransactionId: z.string().optional(),
});
export const loanSchema = z.object({
  id: text,
  name: text,
  principal: money.positive(),
  outstanding: money,
  emi: money,
  rate: z.number().min(0).max(100),
});
export const splitSchema = z.object({
  id: text,
  name: text,
  amount: money.positive(),
  people: z.number().int().min(2).max(100),
  paidByMe: z.boolean(),
  settled: z.boolean(),
  date: dateSchema,
});
export const festivalSchema = z.object({
  id: text,
  name: text,
  targetDate: dateSchema,
  budget: money.positive(),
  saved: money,
});
export const profileSchema = z.object({
  ageRange: z.enum(ageRanges).optional(),
  employment: z.enum(employments).optional(),
  incomeSources: z.array(z.enum(incomeSources)).max(6).optional(),
  banks: z.array(z.string().trim().min(1).max(80)).max(30).optional(),
  riskAnswer: z.enum(riskAnswers.map((a) => a.label)).optional(),
  name: text,
  monthlyIncome: money,
  salaryDay: z.number().int().min(1).max(31),
  risk: z.enum(risks),
  darkMode: z.boolean(),
  sipTarget: money,
  savingsTarget: money.optional(),
  allocation: z
    .object({
      Essentials: money.max(100),
      Savings: money.max(100),
      Investments: money.max(100),
      Lifestyle: money.max(100),
    })
    .refine(
      (a) =>
        Math.abs(Object.values(a).reduce((s, v) => s + v, 0) - 100) < 0.001,
      "Allocation must total 100%",
    ),
});
export const stateSchema = z
  .object({
    version: z.literal(1),
    onboarded: z.boolean(),
    demo: z.boolean(),
    profile: profileSchema,
    transactions: z.array(transactionSchema).max(50000),
    accounts: z.array(accountSchema).min(1).max(100),
    budgets: z.array(budgetSchema),
    goals: z.array(goalSchema),
    holdings: z.array(holdingSchema),
    bills: z.array(billSchema),
    loans: z.array(loanSchema),
    splits: z.array(splitSchema),
    festivals: z.array(festivalSchema),
    readNotifications: z.array(z.string()),
  })
  .superRefine((s, ctx) => {
    for (const key of [
      "transactions",
      "accounts",
      "budgets",
      "goals",
      "holdings",
      "bills",
      "loans",
      "splits",
      "festivals",
    ] as const) {
      if (new Set(s[key].map((x) => x.id)).size !== s[key].length)
        ctx.addIssue({
          code: "custom",
          path: [key],
          message: "Duplicate identifiers",
        });
    }
    const accounts = new Set(s.accounts.map((a) => a.id));
    if (
      s.transactions.some(
        (t) =>
          !accounts.has(t.accountId) ||
          (t.type === "transfer" &&
            (!t.toAccountId ||
              !accounts.has(t.toAccountId) ||
              t.toAccountId === t.accountId)),
      )
    )
      ctx.addIssue({
        code: "custom",
        path: ["transactions"],
        message: "A transaction refers to a missing account",
      });
    if (new Set(s.budgets.map((b) => b.category)).size !== s.budgets.length)
      ctx.addIssue({
        code: "custom",
        path: ["budgets"],
        message: "Duplicate category budgets",
      });
  });
export type AppState = z.infer<typeof stateSchema>;
export type Transaction = z.infer<typeof transactionSchema>;
export type Account = z.infer<typeof accountSchema>;
export type Goal = z.infer<typeof goalSchema>;
export type Holding = z.infer<typeof holdingSchema>;
export type Bill = z.infer<typeof billSchema>;
export type Profile = z.infer<typeof profileSchema>;
export type Risk = (typeof risks)[number];
export type Category = (typeof categories)[number];
export type EntityKey =
  | "accounts"
  | "budgets"
  | "goals"
  | "holdings"
  | "bills"
  | "loans"
  | "splits"
  | "festivals";
export type EntityMap = { [K in EntityKey]: AppState[K][number] };
