import type { ApiClient } from "./api";
import {
  ENTITY_KEYS,
  needsRecreate,
  toClientEntity,
  toServerCreate,
  toServerEntity,
  toServerPatch,
  toServerProfile,
} from "./serverMap";
import type { AppState, EntityKey, EntityMap, Transaction } from "../types/models";

/**
 * Pushes the difference between the last server-confirmed state and the state the user now has.
 * The UI keeps working with its normal reducer; this module turns any state change (including whole-state
 * REPLACE from onboarding/allocation/profile) into the right REST calls, in dependency order:
 *   profile -> accounts -> transaction deletes -> other entities -> transactions -> entity deletes -> account deletes
 * New items get server ids; the returned `remap` lets the store swap the temporary client ids.
 */
export type SyncApi = Pick<ApiClient, "get" | "post" | "put" | "patch" | "del">;

export type Adopt = { [K in EntityKey]: { key: K; value: EntityMap[K] } }[EntityKey];

export type PushResult = {
  /** What the server now holds (client shape, server ids). On error: the part that did succeed. */
  confirmed: AppState;
  remap: Record<string, string>;
  /** Server-owned versions that must replace the local copies (e.g. a bill after payment). */
  adopt: Adopt[];
  error?: unknown;
};

const stable = (v: unknown): string =>
  JSON.stringify(v, (_k, val) =>
    val && typeof val === "object" && !Array.isArray(val)
      ? Object.fromEntries(Object.entries(val as Record<string, unknown>).filter(([, x]) => x !== undefined).sort(([a], [b]) => (a < b ? -1 : 1)))
      : val,
  );
export const same = (a: unknown, b: unknown) => stable(a) === stable(b);

type Item = { id: string };
const BULK_MIN = 8;
const BULK_CHUNK = 500;
const natural: Partial<Record<EntityKey, string>> = { accounts: "name", budgets: "category" };

/** Order-insensitive comparison (the server returns lists in its own order). */
export function hasChanges(confirmed: AppState, desired: AppState): boolean {
  return !same(normalize(confirmed), normalize(desired));
}
const byId = <T extends { id: string }>(l: T[]) => [...l].sort((a, b) => (a.id < b.id ? -1 : 1));
const normalize = (s: AppState) => ({
  ...s,
  demo: undefined,
  accounts: byId(s.accounts), budgets: byId(s.budgets), goals: byId(s.goals), holdings: byId(s.holdings),
  bills: byId(s.bills), loans: byId(s.loans), splits: byId(s.splits), festivals: byId(s.festivals),
  transactions: byId(s.transactions),
  readNotifications: [...s.readNotifications].sort(),
});

export async function pushChanges(api: SyncApi, confirmedIn: AppState, desired: AppState): Promise<PushResult> {
  const confirmed: AppState = JSON.parse(JSON.stringify(confirmedIn)) as AppState;
  const remap: Record<string, string> = {};
  const adopt: Adopt[] = [];
  const rid = (id: string) => remap[id] ?? id;
  const consumed = new Set<string>(); // server items reused for a same-named replacement
  try {
    // ---- profile (name -> users.name, the rest -> /profile)
    if (desired.profile.name !== confirmed.profile.name) {
      await api.patch("/api/users/me", { name: desired.profile.name });
      confirmed.profile.name = desired.profile.name;
    }
    if (
      !same(toServerProfile(confirmed), toServerProfile(desired)) ||
      desired.onboarded !== confirmed.onboarded
    ) {
      await api.put("/api/profile", toServerProfile(desired));
      confirmed.profile = { ...desired.profile };
      confirmed.onboarded = desired.onboarded;
      confirmed.readNotifications = [...desired.readNotifications];
    }

    // ---- accounts: create / update (deletes come last)
    await upsertEntities("accounts");

    // ---- transactions: deletes first (they also reopen bills server-side)
    const desiredTxIds = new Set(desired.transactions.map((t) => t.id));
    for (const t of [...confirmed.transactions]) {
      if (desiredTxIds.has(t.id)) continue;
      await api.del(`/api/transactions/${t.id}`);
      removeTransaction(t);
    }

    // ---- other entities
    const payBills = new Set(
      desired.transactions
        .filter((t) => isNewBillPayment(t))
        .map((t) => t.billId as string),
    );
    for (const key of ENTITY_KEYS) if (key !== "accounts") await upsertEntities(key, payBills);

    // ---- transactions: create / update
    const confirmedTx = new Map(confirmed.transactions.map((t) => [t.id, t]));
    await bulkCreate(desired.transactions.filter((t) => !confirmedTx.has(t.id) && !isNewBillPayment(t)));
    for (const t of desired.transactions) {
      const before = confirmedTx.get(t.id);
      if (!before) {
        if (remap[t.id]) continue; // already created by the bulk call
        await createTransaction(t);
      } else if (!same(before, t)) {
        if (needsRecreate(before, t)) {
          await api.del(`/api/transactions/${before.id}`);
          removeTransaction(before);
          await createTransaction(t);
        } else {
          await api.patch(`/api/transactions/${before.id}`, toServerPatch(t, rid));
          replaceTransaction({ ...t, accountId: rid(t.accountId) });
        }
      }
    }

    // ---- entity deletes, then account deletes (accounts last: they may be referenced)
    for (const key of ENTITY_KEYS) if (key !== "accounts") await deleteMissing(key);
    await deleteMissing("accounts");
  } catch (error) {
    return { confirmed, remap, adopt, error };
  }
  return { confirmed, remap, adopt };

  // ------------------------------------------------------------------------------------------

  function isNewBillPayment(t: Transaction) {
    return (
      t.source === "bill" &&
      !!t.billId &&
      !!t.billDueDate &&
      !confirmed.transactions.some((c) => c.id === t.id)
    );
  }

  function removeTransaction(t: Transaction) {
    confirmed.transactions = confirmed.transactions.filter((x) => x.id !== t.id);
    confirmed.bills = confirmed.bills.map((b) =>
      b.lastTransactionId === t.id
        ? { ...b, paid: false, dueDate: t.billDueDate ?? b.dueDate, lastTransactionId: undefined }
        : b,
    );
  }

  function replaceTransaction(t: Transaction) {
    confirmed.transactions = confirmed.transactions.map((x) => (x.id === t.id ? t : x));
  }

  /** Imports (CSV, SMS, backups) arrive as many rows: send them in chunks instead of one request each. */
  async function bulkCreate(fresh: Transaction[]) {
    if (fresh.length < BULK_MIN) return;
    for (let i = 0; i < fresh.length; i += BULK_CHUNK) {
      const chunk = fresh.slice(i, i + BULK_CHUNK);
      const res = await api.post<{ created: { id: string; clientRequestId: string | null }[] }>(
        "/api/transactions/bulk",
        { transactions: chunk.map((t) => toServerCreate(t, rid)) },
      );
      const ids = new Map(res.created.map((c) => [c.clientRequestId, c.id]));
      for (const t of chunk) {
        const serverId = ids.get(t.importKey ?? `app-${t.id}`);
        if (!serverId) continue; // skipped as a duplicate: the single-row path below resolves its id
        remap[t.id] = serverId;
        confirmed.transactions.push({
          ...t,
          id: serverId,
          accountId: rid(t.accountId),
          toAccountId: t.toAccountId ? rid(t.toAccountId) : undefined,
          billId: t.billId ? rid(t.billId) : undefined,
        });
      }
    }
  }

  async function createTransaction(t: Transaction) {
    if (isNewBillPayment(t)) {
      const billId = rid(t.billId as string);
      const res = await api.post<Record<string, unknown> & { transaction: { id: string } }>(
        `/api/bills/${billId}/pay`,
        { accountId: rid(t.accountId), method: t.method, paidOn: t.date },
      );
      const { transaction, ...billRaw } = res;
      const bill = toClientEntity("bills", billRaw);
      remap[t.id] = transaction.id;
      confirmed.transactions.push({
        ...t,
        id: transaction.id,
        accountId: rid(t.accountId),
        billId,
        amount: bill ? bill.amount : t.amount,
      });
      if (bill) {
        confirmed.bills = confirmed.bills.map((b) => (b.id === bill.id ? bill : b));
        adopt.push({ key: "bills", value: bill });
      }
      return;
    }
    const created = await api.post<{ id: string }>("/api/transactions", toServerCreate(t, rid));
    remap[t.id] = created.id;
    if (!confirmed.transactions.some((x) => x.id === created.id)) {
      confirmed.transactions.push({
        ...t,
        id: created.id,
        accountId: rid(t.accountId),
        toAccountId: t.toAccountId ? rid(t.toAccountId) : undefined,
        billId: t.billId ? rid(t.billId) : undefined,
      });
    }
  }

  async function upsertEntities<K extends EntityKey>(key: K, payBillIds: Set<string> = new Set()) {
    const path = `/api/${key}`;
    const list = confirmed[key] as unknown as Item[];
    const known = new Map(list.map((e) => [e.id, e]));
    const desiredIds = new Set((desired[key] as unknown as Item[]).map((e) => e.id));
    for (const raw of desired[key] as unknown as Item[]) {
      let item = raw as unknown as EntityMap[EntityKey];
      const before = known.get(raw.id) as unknown as EntityMap[EntityKey] | undefined;
      if (before) {
        let target = item;
        if (key === "bills" && payBillIds.has(raw.id)) {
          // paid/dueDate/lastTransactionId change through POST /bills/{id}/pay, not through PUT
          const b = before as EntityMap["bills"];
          target = { ...(item as EntityMap["bills"]), paid: b.paid, dueDate: b.dueDate, lastTransactionId: b.lastTransactionId };
        }
        if (!same(before, target)) {
          await put(key, raw.id, target);
        }
        continue;
      }
      // not on the server yet: reuse a same-named account / same-category budget that is being replaced
      const nk = natural[key];
      if (nk) {
        const twin = list.find(
          (e) =>
            !desiredIds.has(e.id) &&
            !consumed.has(e.id) &&
            (e as unknown as Record<string, unknown>)[nk] === (raw as unknown as Record<string, unknown>)[nk],
        );
        if (twin) {
          consumed.add(twin.id);
          remap[raw.id] = twin.id;
          await put(key, twin.id, { ...item, id: twin.id } as EntityMap[EntityKey]);
          continue;
        }
      }
      if (key === "bills" && payBillIds.has(raw.id)) {
        const pay = desired.transactions.find((t) => t.billId === raw.id && t.source === "bill");
        if (pay?.billDueDate) item = { ...(item as EntityMap["bills"]), paid: false, dueDate: pay.billDueDate, lastTransactionId: undefined };
      }
      const created = await api.post<Record<string, unknown>>(path, toServerEntity(key, item));
      const serverItem = toClientEntity(key, created);
      const id = (created.id as string) ?? raw.id;
      remap[raw.id] = id;
      let confirmedItem = (serverItem ?? { ...item, id }) as EntityMap[EntityKey];
      if (key === "goals") {
        const current = (item as EntityMap["goals"]).current;
        if (current > 0) {
          const after = await api.post<Record<string, unknown>>(`/api/goals/${id}/contributions`, { amount: current });
          confirmedItem = (toClientEntity("goals", after) ?? { ...confirmedItem, current }) as EntityMap[EntityKey];
        }
      }
      (confirmed[key] as unknown as Item[]).push(confirmedItem as unknown as Item);
    }
  }

  async function put(key: EntityKey, id: string, item: EntityMap[EntityKey]) {
    const list = confirmed[key] as unknown as Item[];
    if (key === "goals") {
      const before = list.find((e) => e.id === id) as unknown as EntityMap["goals"] | undefined;
      const goal = item as EntityMap["goals"];
      let current = before?.current ?? 0;
      await api.put(`/api/goals/${id}`, toServerEntity(key, item));
      if (goal.current > current) {
        await api.post(`/api/goals/${id}/contributions`, { amount: Math.round((goal.current - current) * 100) / 100 });
        current = goal.current;
      }
      setConfirmed(key, id, { ...goal, id, current });
      return;
    }
    const res = await api.put<Record<string, unknown>>(`/api/${key}/${id}`, toServerEntity(key, item));
    setConfirmed(key, id, (toClientEntity(key, res) ?? { ...item, id }) as EntityMap[EntityKey]);
  }

  function setConfirmed(key: EntityKey, id: string, value: EntityMap[EntityKey]) {
    const list = confirmed[key] as unknown as Item[];
    const i = list.findIndex((e) => e.id === id);
    if (i >= 0) list[i] = value as unknown as Item;
    else list.push(value as unknown as Item);
  }

  async function deleteMissing(key: EntityKey) {
    const keep = new Set((desired[key] as unknown as Item[]).map((e) => rid(e.id))); // new items already have server ids
    for (const e of [...(confirmed[key] as unknown as Item[])]) {
      if (keep.has(e.id) || consumed.has(e.id)) continue;
      await api.del(`/api/${key}/${e.id}`);
      (confirmed as unknown as Record<string, Item[]>)[key] = (confirmed[key] as unknown as Item[]).filter((x) => x.id !== e.id);
    }
  }
}

/** Swap temporary ids for server ids everywhere in a state (used after a push succeeded). */
export function applyRemap(state: AppState, remap: Record<string, string>, adopt: Adopt[] = []): AppState {
  const r = (id: string) => remap[id] ?? id;
  const ro = (id?: string) => (id ? r(id) : id);
  const next: AppState = {
    ...state,
    accounts: state.accounts.map((a) => ({ ...a, id: r(a.id) })),
    budgets: state.budgets.map((a) => ({ ...a, id: r(a.id) })),
    goals: state.goals.map((a) => ({ ...a, id: r(a.id) })),
    holdings: state.holdings.map((a) => ({ ...a, id: r(a.id) })),
    loans: state.loans.map((a) => ({ ...a, id: r(a.id) })),
    splits: state.splits.map((a) => ({ ...a, id: r(a.id) })),
    festivals: state.festivals.map((a) => ({ ...a, id: r(a.id) })),
    bills: state.bills.map((b) => ({ ...b, id: r(b.id), lastTransactionId: ro(b.lastTransactionId) })),
    transactions: state.transactions.map((t) => ({
      ...t,
      id: r(t.id),
      accountId: r(t.accountId),
      toAccountId: ro(t.toAccountId),
      billId: ro(t.billId),
    })),
  };
  for (const a of adopt) {
    const list = next[a.key] as unknown as Item[];
    (next as unknown as Record<string, Item[]>)[a.key] = list.map((x) => (x.id === a.value.id ? (a.value as unknown as Item) : x));
  }
  return next;
}
