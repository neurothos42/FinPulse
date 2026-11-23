import { test } from "node:test";
import assert from "node:assert/strict";
import { emptyState } from "../src/data/seed";
import { reducer } from "../src/store/reducer";
import { applyRemap, hasChanges, pushChanges, type SyncApi } from "../src/services/sync";
import { loadRemoteState } from "../src/services/remote";
import { toClientTransaction, type ServerTransaction } from "../src/services/serverMap";
import { stateSchema, type AppState, type Transaction } from "../src/types/models";

/** A small in-memory stand-in for the Spring backend (same routes and rules the real one enforces). */
class FakeServer {
  n = 0;
  profile: Record<string, unknown> | null = null;
  userName = "Tester";
  ent: Record<string, Record<string, unknown>[]> = {
    accounts: [], budgets: [], goals: [], holdings: [], bills: [], loans: [], splits: [], festivals: [],
  };
  tx: Record<string, unknown>[] = [];
  calls: string[] = [];
  failNext: { status: number; message: string } | null = null;
  id = () => `srv${++this.n}`;
  err(status: number, message: string): never {
    throw Object.assign(new Error(message), { status });
  }
  api: SyncApi = {
    get: async (path: string, q?: Record<string, unknown>) => this.handle("GET", path, undefined, q) as never,
    post: async (path: string, body?: unknown) => this.handle("POST", path, body) as never,
    put: async (path: string, body?: unknown) => this.handle("PUT", path, body) as never,
    patch: async (path: string, body?: unknown) => this.handle("PATCH", path, body) as never,
    del: async (path: string) => void this.handle("DELETE", path),
  };
  handle(method: string, path: string, body?: any, q?: Record<string, unknown>): unknown {
    this.calls.push(`${method} ${path}`);
    if (this.failNext) {
      const f = this.failNext;
      this.failNext = null;
      this.err(f.status, f.message);
    }
    const parts = path.split("/").filter(Boolean).slice(1); // drop "api"
    const [r, id, sub] = parts;
    if (r === "users" && method === "PATCH") { this.userName = body.name; return {}; }
    if (r === "profile") {
      if (method === "GET") return this.profile ?? this.err(404, "Profile not found");
      this.profile = { ...body }; return this.profile;
    }
    if (r === "transactions") {
      if (method === "GET") {
        const rows = this.tx.filter((t) => q?.needsReview === undefined || t.needsReview === q.needsReview);
        const page = Number(q?.page ?? 0), size = Number(q?.size ?? 20);
        return { content: rows.slice(page * size, page * size + size), page, size, totalElements: rows.length, totalPages: Math.max(1, Math.ceil(rows.length / size)) };
      }
      if (method === "POST" && id === "bulk") {
        const created: Record<string, unknown>[] = [];
        for (const b of body.transactions) {
          if (this.tx.some((t) => t.clientRequestId === b.clientRequestId)) continue;
          const row = this.toServerTx(b); this.tx.push(row); created.push(row);
        }
        return { created, skippedDuplicates: body.transactions.length - created.length, errors: [] };
      }
      if (method === "POST") {
        const dup = this.tx.find((t) => t.clientRequestId === body.clientRequestId);
        if (dup) return dup;
        const accounts = this.ent.accounts.map((a) => a.id);
        if (!accounts.includes(body.accountId)) this.err(400, "accountId does not refer to one of your accounts");
        const row = this.toServerTx(body);
        this.tx.push(row); return row;
      }
      const i = this.tx.findIndex((t) => t.id === id);
      if (i < 0) this.err(404, "Transaction not found");
      if (method === "DELETE") {
        const [gone] = this.tx.splice(i, 1);
        for (const b of this.ent.bills)
          if (b.lastTransactionId === gone.id) { b.paid = false; b.dueDate = gone.billDueDate ?? b.dueDate; delete b.lastTransactionId; }
        return undefined;
      }
      if (method === "PATCH") { Object.assign(this.tx[i], body, { type: body.direction === "CREDIT" ? "income" : this.tx[i].type }); return this.tx[i]; }
    }
    if (r === "bills" && sub === "pay") {
      const bill = this.ent.bills.find((b) => b.id === id)!;
      if (bill.paid) this.err(409, "This bill is already paid");
      const row = this.toServerTx({ date: body.paidOn, amount: bill.amount, type: "expense", merchant: bill.name, category: bill.category, method: body.method, accountId: body.accountId, origin: "BILL", clientRequestId: `bill-${bill.id}` });
      row.billId = bill.id; row.billDueDate = bill.dueDate; this.tx.push(row);
      bill.lastTransactionId = row.id;
      if (bill.recurring) { const d = new Date(`${bill.dueDate}T00:00:00Z`); d.setUTCMonth(d.getUTCMonth() + 1); bill.dueDate = d.toISOString().slice(0, 10); } else bill.paid = true;
      return { ...bill, transaction: row };
    }
    if (r === "goals" && sub === "contributions") {
      const g = this.ent.goals.find((x) => x.id === id)!; g.current = Math.round(((g.current as number) + body.amount) * 100) / 100; return g;
    }
    const list = this.ent[r!];
    if (!list) this.err(404, "Unknown resource");
    if (method === "GET" && !id) return list.map((x) => ({ ...x, createdAt: "2026-01-01T00:00:00Z" }));
    if (method === "POST") {
      const key = r === "accounts" ? "name" : r === "budgets" ? "category" : null;
      if (key && list.some((x) => x[key] === body[key])) this.err(409, "Duplicate");
      const item = { id: this.id(), ...(r === "goals" ? { current: 0 } : {}), ...body }; if (r === "goals") item.current = 0;
      list.push(item); return item;
    }
    const i = list.findIndex((x) => x.id === id);
    if (i < 0) this.err(404, "Not found");
    if (method === "PUT") { list[i] = { ...list[i], ...body, id }; return list[i]; }
    if (method === "DELETE") {
      if (r === "accounts") {
        if (this.tx.some((t) => t.accountId === id || t.toAccountId === id)) this.err(409, "Account is used by transactions");
        if (list.length <= 1) this.err(409, "You must keep at least one account");
      }
      list.splice(i, 1); return undefined;
    }
    return list[i];
  }
  toServerTx(b: any): Record<string, any> {
    return { id: this.id(), date: b.date, amount: b.amount, direction: b.type === "income" ? "CREDIT" : "DEBIT", merchant: b.merchant,
      category: b.category === "Groceries" ? "Food" : b.category === "Medical" ? "Healthcare" : b.category === "Income" ? "Salary" : b.type === "investment" ? "Investment" : b.type === "transfer" ? "Transfer" : b.category,
      clientCategory: ["Groceries", "Medical", "Income", "Household", "Personal"].includes(b.category) && b.type !== "investment" && b.type !== "transfer" ? b.category : null,
      description: "", needsReview: false, accountId: b.accountId, toAccountId: b.toAccountId ?? null, method: b.method, origin: b.origin ?? "MANUAL",
      incomeSource: b.incomeSource ?? null, notes: b.notes ?? null, type: b.type, billId: null, billDueDate: null, clientRequestId: b.clientRequestId };
  }
}

const user = { id: "u1", name: "Tester", email: "t@example.com" };
async function boot() {
  const server = new FakeServer();
  const { state } = await loadRemoteState(server.api, user);
  return { server, state, confirmed: state };
}
/** Run one "state changed -> save" cycle exactly like AppProvider does. */
async function save(server: FakeServer, confirmed: AppState, desired: AppState) {
  const result = await pushChanges(server.api, confirmed, desired);
  assert.equal(result.error, undefined, String(result.error));
  const state = applyRemap(desired, result.remap, result.adopt);
  assert.ok(stateSchema.safeParse(state).success, "state stays valid after remap");
  return { state, confirmed: result.confirmed };
}
const tx = (o: Partial<Transaction>): Transaction => ({
  id: "t-" + Math.random().toString(36).slice(2), merchant: "Swiggy", amount: 420, type: "expense", category: "Food",
  method: "UPI", accountId: "cash", date: "2026-11-19", notes: "", source: "manual", ...o,
});

test("first load creates the default cash account on the server and yields a valid state", async () => {
  const { server, state } = await boot();
  assert.equal(state.accounts.length, 1);
  assert.equal(server.ent.accounts.length, 1);
  assert.ok(stateSchema.safeParse(state).success);
  assert.equal(state.onboarded, false);
});

test("a new transaction is POSTed once, gets its server id, and a second push is a no-op", async () => {
  let { server, state, confirmed } = await boot();
  const t = tx({ accountId: state.accounts[0].id, category: "Groceries" });
  state = reducer(state, { type: "TRANSACTIONS", rows: [t] });
  ({ state, confirmed } = await save(server, confirmed, state));
  assert.equal(server.tx.length, 1);
  assert.notEqual(state.transactions[0].id, t.id);
  assert.equal(state.transactions[0].id, server.tx[0].id);
  assert.equal(server.tx[0].clientRequestId, `app-${t.id}`);
  assert.equal(hasChanges(confirmed, state), false);
  const before = server.calls.length;
  await save(server, confirmed, state);
  assert.equal(server.calls.length, before, "no calls when nothing changed");
});

test("client category and type survive a round trip through the server", async () => {
  let { server, state, confirmed } = await boot();
  const acc = state.accounts[0].id;
  state = reducer(state, { type: "TRANSACTIONS", rows: [
    tx({ accountId: acc, category: "Groceries", merchant: "BigBasket" }),
    tx({ accountId: acc, category: "Medical", merchant: "Pharmacy" }),
    tx({ accountId: acc, type: "income", category: "Income", merchant: "Company", amount: 50000, incomeSource: "Salary" }),
    tx({ accountId: acc, type: "investment", category: "Investment", merchant: "SIP", amount: 5000 }),
  ] });
  ({ state, confirmed } = await save(server, confirmed, state));
  const reloaded = (await loadRemoteState(server.api, user)).state;
  const byMerchant = Object.fromEntries(reloaded.transactions.map((t) => [t.merchant, t]));
  assert.equal(byMerchant.BigBasket.category, "Groceries");
  assert.equal(byMerchant.Pharmacy.category, "Medical");
  assert.equal(byMerchant.Company.type, "income");
  assert.equal(byMerchant.Company.category, "Income");
  assert.equal(byMerchant.SIP.type, "investment");
  void confirmed;
});

test("editing amount PATCHes in place; changing type recreates the row", async () => {
  let { server, state, confirmed } = await boot();
  const acc = state.accounts[0].id;
  state = reducer(state, { type: "TRANSACTIONS", rows: [tx({ accountId: acc })] });
  ({ state, confirmed } = await save(server, confirmed, state));
  const id = state.transactions[0].id;
  state = reducer(state, { type: "TRANSACTIONS", rows: [{ ...state.transactions[0], amount: 999 }] });
  server.calls.length = 0;
  ({ state, confirmed } = await save(server, confirmed, state));
  assert.deepEqual(server.calls, [`PATCH /api/transactions/${id}`]);
  state = reducer(state, { type: "TRANSACTIONS", rows: [{ ...state.transactions[0], type: "investment", category: "Investment" }] });
  server.calls.length = 0;
  ({ state, confirmed } = await save(server, confirmed, state));
  assert.deepEqual(server.calls.map((c) => c.split(" ")[0]), ["DELETE", "POST"]);
  assert.equal(server.tx.length, 1);
  void confirmed;
});

test("onboarding REPLACE: same-named Cash account is reused, new accounts are created, profile saved", async () => {
  let { server, state, confirmed } = await boot();
  const serverCash = state.accounts[0].id;
  const next = emptyState();
  next.onboarded = true;
  next.profile = { ...next.profile, name: "Arjun", monthlyIncome: 85000 };
  next.accounts = [
    { id: "cash", name: "Cash", type: "cash", openingBalance: 0, limit: 0 },
    { id: "bank-1", name: "HDFC Bank", type: "bank", openingBalance: 1000, limit: 0 },
  ];
  ({ state, confirmed } = await save(server, confirmed, next));
  assert.equal(server.ent.accounts.length, 2);
  assert.ok(state.accounts.some((a) => a.id === serverCash), "Cash kept its server id");
  assert.equal(server.profile?.onboarded, true);
  assert.equal(server.userName, "Arjun");
  void confirmed;
});

test("paying a bill uses /pay; deleting the payment reopens the bill both locally and on the server", async () => {
  let { server, state, confirmed } = await boot();
  const acc = state.accounts[0].id;
  state = reducer(state, { type: "UPSERT", key: "bills", value: { id: "b1", name: "Internet", amount: 999, dueDate: "2026-11-25", recurring: true, paid: false, category: "Bills", kind: "bill" } });
  ({ state, confirmed } = await save(server, confirmed, state));
  const billId = state.bills[0].id;
  const payment = tx({ accountId: acc, merchant: "Internet", amount: 999, category: "Bills", source: "bill", billId, billDueDate: "2026-11-25", date: "2026-11-20" });
  state = reducer(state, { type: "PAY_BILL", id: billId, transaction: payment });
  assert.equal(state.bills[0].dueDate, "2026-12-25");
  server.calls.length = 0;
  ({ state, confirmed } = await save(server, confirmed, state));
  assert.ok(server.calls.includes(`POST /api/bills/${billId}/pay`));
  assert.ok(!server.calls.some((c) => c === "POST /api/transactions"));
  assert.equal(state.bills[0].dueDate, "2026-12-25");
  assert.equal(state.bills[0].lastTransactionId, state.transactions[0].id);
  state = reducer(state, { type: "DELETE_TRANSACTION", id: state.transactions[0].id });
  ({ state, confirmed } = await save(server, confirmed, state));
  assert.equal(server.ent.bills[0].paid, false);
  assert.equal(server.ent.bills[0].dueDate, "2026-11-25");
  assert.equal(state.bills[0].dueDate, "2026-11-25");
  assert.equal(hasChanges(confirmed, state), false);
});

test("goal contribution goes through /contributions and goal edits keep the server's current", async () => {
  let { server, state, confirmed } = await boot();
  state = reducer(state, { type: "UPSERT", key: "goals", value: { id: "g1", name: "Trip", icon: "✈️", target: 50000, current: 0, targetDate: "2027-06-01", emergency: false } });
  ({ state, confirmed } = await save(server, confirmed, state));
  state = reducer(state, { type: "CONTRIBUTE", id: state.goals[0].id, amount: 2500 });
  server.calls.length = 0;
  ({ state, confirmed } = await save(server, confirmed, state));
  assert.ok(server.calls.some((c) => c.endsWith("/contributions")));
  assert.equal(server.ent.goals[0].current, 2500);
  assert.equal(hasChanges(confirmed, state), false);
});

test("account with transactions cannot be deleted: server refuses and the error surfaces", async () => {
  let { server, state, confirmed } = await boot();
  state = reducer(state, { type: "UPSERT", key: "accounts", value: { id: "a2", name: "Wallet", type: "cash", openingBalance: 0, limit: 0 } });
  ({ state, confirmed } = await save(server, confirmed, state));
  const wallet = state.accounts.find((a) => a.name === "Wallet")!.id;
  state = reducer(state, { type: "TRANSACTIONS", rows: [tx({ accountId: wallet })] });
  ({ state, confirmed } = await save(server, confirmed, state));
  server.failNext = null;
  // bypass the reducer guard to simulate a stale UI deleting it
  const forced: AppState = { ...state, accounts: state.accounts.filter((a) => a.id !== wallet), transactions: state.transactions.filter((t) => t.accountId !== wallet) };
  const r = await pushChanges(server.api, confirmed, forced);
  assert.equal(r.error, undefined, "deleting its transactions first makes it deletable");
});

test("a rejected change reports the error and keeps what already succeeded", async () => {
  let { server, state, confirmed } = await boot();
  state = reducer(state, { type: "TRANSACTIONS", rows: [tx({ accountId: state.accounts[0].id })] });
  server.failNext = { status: 400, message: "amount must be positive" };
  const r = await pushChanges(server.api, confirmed, state);
  assert.ok(r.error);
  assert.equal((r.error as { status: number }).status, 400);
  assert.equal(server.tx.length, 0);
  void confirmed;
});

test("review rows (no account / needs review) are not loaded into the app state", async () => {
  const row: ServerTransaction = { id: "x", date: "2026-10-01", amount: 10, direction: "DEBIT", merchant: "Shop", category: "Food", description: "raw", needsReview: false, accountId: null, toAccountId: null, method: null, origin: "DOCUMENT", incomeSource: null, notes: null, type: "expense", clientCategory: null, billId: null, billDueDate: null, clientRequestId: null };
  assert.equal(toClientTransaction(row), null);
  assert.equal(toClientTransaction({ ...row, accountId: "a1", needsReview: true }), null);
  const ok = toClientTransaction({ ...row, accountId: "a1", category: "Healthcare" });
  assert.equal(ok?.category, "Medical");
  assert.equal(ok?.source, "import");
  assert.equal(ok?.method, "Bank Transfer");
});

test("an import of many rows uses one bulk request and is safe to repeat", async () => {
  let { server, state, confirmed } = await boot();
  const acc = state.accounts[0].id;
  const rows = Array.from({ length: 12 }, (_, i) => tx({ accountId: acc, merchant: `Shop ${i}`, source: "import", importKey: `key-${i}` }));
  state = reducer(state, { type: "TRANSACTIONS", rows });
  server.calls.length = 0;
  ({ state, confirmed } = await save(server, confirmed, state));
  assert.deepEqual(server.calls, ["POST /api/transactions/bulk"]);
  assert.equal(server.tx.length, 12);
  assert.ok(state.transactions.every((t) => t.id.startsWith("srv")));
  // importing the same file again (e.g. after reinstall): rows come back with importKey, so nothing is added
  const fresh = (await loadRemoteState(server.api, user)).state;
  const again = reducer(fresh, { type: "TRANSACTIONS", rows: rows.map((r) => ({ ...r, id: r.id + "x" })) });
  assert.equal(again.transactions.length, 12);
  void confirmed;
});
