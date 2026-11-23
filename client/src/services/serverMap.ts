import {
  accountSchema,
  billSchema,
  budgetSchema,
  festivalSchema,
  goalSchema,
  holdingSchema,
  loanSchema,
  methods,
  profileSchema,
  splitSchema,
  transactionSchema,
  type AppState,
  type EntityKey,
  type EntityMap,
  type Profile,
  type Transaction,
} from "../types/models";

/** Pure mapping between the backend's JSON and the client's AppState shapes (no I/O, unit-tested). */

export type ServerTransaction = {
  id: string;
  date: string | null;
  amount: number;
  direction: "DEBIT" | "CREDIT" | "UNKNOWN";
  merchant: string | null;
  category: string;
  description: string | null;
  needsReview: boolean;
  accountId: string | null;
  toAccountId: string | null;
  method: string | null;
  origin: string | null;
  incomeSource: string | null;
  notes: string | null;
  type: string | null;
  clientCategory: string | null;
  billId: string | null;
  billDueDate: string | null;
  clientRequestId: string | null;
};

const ORIGIN_TO_SOURCE: Record<string, Transaction["source"]> = {
  MANUAL: "manual",
  IMPORT: "import",
  BILL: "bill",
  VOICE: "voice",
  RECEIPT: "receipt",
  DOCUMENT: "import",
};

/** Server category -> the client's label when the client-only label was not stored. */
const SERVER_TO_CLIENT: Record<string, Transaction["category"]> = {
  Healthcare: "Medical",
  Rent: "Household",
  Salary: "Income",
  Transfer: "Other",
  Investment: "Investment",
};
const CLIENT_CATEGORIES = new Set([
  "Food", "Groceries", "Shopping", "Transport", "Entertainment", "Household", "Bills",
  "Personal", "Medical", "Travel", "Education", "Other", "Income", "Investment",
]);

export function clientCategoryOf(s: Pick<ServerTransaction, "category" | "clientCategory">): Transaction["category"] {
  const raw = s.clientCategory ?? SERVER_TO_CLIENT[s.category] ?? s.category;
  return (CLIENT_CATEGORIES.has(raw) ? raw : "Other") as Transaction["category"];
}

/**
 * Server row -> client transaction, or null when the client cannot represent it yet
 * (no account, no date, unknown direction, still needs review). Those rows live in the Review screen.
 */
export function toClientTransaction(s: ServerTransaction): Transaction | null {
  if (!s.accountId || !s.date || s.needsReview || s.direction === "UNKNOWN") return null;
  const type = s.type;
  if (type !== "income" && type !== "expense" && type !== "investment" && type !== "transfer") return null;
  const method = (methods as readonly string[]).includes(s.method ?? "") ? s.method : "Bank Transfer";
  const merchant = (s.merchant ?? "").trim() || (s.description ?? "").trim().slice(0, 40) || "Transaction";
  const key = s.clientRequestId && !s.clientRequestId.startsWith("app-") ? s.clientRequestId : undefined;
  const parsed = transactionSchema.safeParse({
    id: s.id,
    merchant,
    amount: s.amount,
    type,
    category: clientCategoryOf(s),
    method,
    accountId: s.accountId,
    toAccountId: s.toAccountId ?? undefined,
    date: s.date,
    notes: (s.notes ?? s.description ?? "").slice(0, 2000),
    source: ORIGIN_TO_SOURCE[s.origin ?? ""] ?? "manual",
    importKey: key,
    billId: s.billId ?? undefined,
    billDueDate: s.billDueDate ?? undefined,
    incomeSource: s.incomeSource ?? undefined,
  });
  return parsed.success ? parsed.data : null;
}

/** Category sent to the server: transfer/investment are decided by `type`; client-only labels are aliased server-side. */
export function serverCategoryOf(t: Pick<Transaction, "type" | "category">): string {
  if (t.type === "transfer") return "Transfer";
  if (t.type === "investment") return "Investment";
  return t.category;
}

export const directionOf = (t: Pick<Transaction, "type">) => (t.type === "income" ? "CREDIT" : "DEBIT");

/** POST /api/transactions body. `resolve` maps temporary client ids to server ids. */
export function toServerCreate(t: Transaction, resolve: (id: string) => string) {
  return {
    date: t.date,
    amount: t.amount,
    type: t.type,
    merchant: t.merchant,
    category: serverCategoryOf(t),
    method: t.method,
    accountId: resolve(t.accountId),
    toAccountId: t.toAccountId ? resolve(t.toAccountId) : undefined,
    notes: t.notes || undefined,
    origin: t.source.toUpperCase(),
    incomeSource: t.incomeSource,
    clientRequestId: t.importKey ?? `app-${t.id}`,
  };
}

/** PATCH body for the fields the server can change in place. */
export function toServerPatch(t: Transaction, resolve: (id: string) => string) {
  return {
    date: t.date,
    amount: t.amount,
    merchant: t.merchant,
    category: serverCategoryOf(t),
    direction: directionOf(t),
    method: t.method,
    accountId: resolve(t.accountId),
    notes: t.notes,
    incomeSource: t.incomeSource ?? "",
  };
}

/** Fields that force delete + recreate because PATCH cannot change them. */
export function needsRecreate(a: Transaction, b: Transaction): boolean {
  return (
    a.type !== b.type ||
    (a.toAccountId ?? "") !== (b.toAccountId ?? "") ||
    (a.billId ?? "") !== (b.billId ?? "") ||
    (a.billDueDate ?? "") !== (b.billDueDate ?? "")
  );
}

// ---- entities --------------------------------------------------------------------------------

const ENTITY_SCHEMAS = {
  accounts: accountSchema,
  budgets: budgetSchema,
  goals: goalSchema,
  holdings: holdingSchema,
  bills: billSchema,
  loans: loanSchema,
  splits: splitSchema,
  festivals: festivalSchema,
} as const;

export const ENTITY_KEYS = Object.keys(ENTITY_SCHEMAS) as EntityKey[];

/** Server entity JSON -> client entity (extra server fields are dropped by the schema). null if invalid. */
export function toClientEntity<K extends EntityKey>(key: K, raw: unknown): EntityMap[K] | null {
  const parsed = ENTITY_SCHEMAS[key].safeParse(raw);
  return parsed.success ? (parsed.data as EntityMap[K]) : null;
}

/** Request body for POST/PUT of an entity: no id; `lastTransactionId` and goal `current` are server-managed. */
export function toServerEntity(key: EntityKey, item: EntityMap[EntityKey]): Record<string, unknown> {
  const { id: _id, ...rest } = item as Record<string, unknown> & { id: string };
  void _id;
  if (key === "bills") delete rest.lastTransactionId;
  if (key === "goals") delete rest.current;
  return rest;
}

// ---- profile ---------------------------------------------------------------------------------

export type ServerProfile = Partial<Omit<Profile, "name" | "savingsTarget">> & {
  savingsTarget?: number | null;
  onboarded?: boolean;
  readNotifications?: string[];
};

export function toClientProfile(server: ServerProfile | null, name: string, base: Profile): Profile {
  if (!server) return { ...base, name };
  const candidate = {
    ...base,
    name,
    ageRange: server.ageRange ?? undefined,
    employment: server.employment ?? undefined,
    incomeSources: server.incomeSources ?? undefined,
    banks: server.banks ?? undefined,
    riskAnswer: server.riskAnswer ?? undefined,
    monthlyIncome: server.monthlyIncome ?? base.monthlyIncome,
    salaryDay: server.salaryDay ?? base.salaryDay,
    risk: server.risk ?? base.risk,
    darkMode: server.darkMode ?? base.darkMode,
    sipTarget: server.sipTarget ?? base.sipTarget,
    savingsTarget: server.savingsTarget ?? undefined,
    allocation: server.allocation ?? base.allocation,
  };
  const parsed = profileSchema.safeParse(candidate);
  if (parsed.success) return parsed.data;
  // Free-text fields that the client enums do not know are dropped instead of rejecting the whole profile.
  const lenient = profileSchema.safeParse({
    ...candidate,
    ageRange: undefined,
    employment: undefined,
    incomeSources: undefined,
    riskAnswer: undefined,
  });
  return lenient.success ? lenient.data : { ...base, name };
}

export function toServerProfile(s: Pick<AppState, "profile" | "onboarded" | "readNotifications">) {
  const p = s.profile;
  return {
    ageRange: p.ageRange,
    employment: p.employment,
    incomeSources: p.incomeSources,
    banks: p.banks,
    riskAnswer: p.riskAnswer,
    risk: p.risk,
    monthlyIncome: p.monthlyIncome,
    salaryDay: p.salaryDay,
    sipTarget: p.sipTarget,
    savingsTarget: p.savingsTarget,
    allocation: p.allocation,
    darkMode: p.darkMode,
    onboarded: s.onboarded,
    readNotifications: s.readNotifications,
  };
}
