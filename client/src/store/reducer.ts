import type {
  AppState,
  EntityKey,
  EntityMap,
  Profile,
  Transaction,
} from "../types/models";
import { stateSchema } from "../types/models";
import { shiftMonths } from "../utils/date";
import { roundMoney } from "../utils/format";
import { applyRemap, type Adopt } from "../services/sync";

type Upsert = {
  [K in EntityKey]: { type: "UPSERT"; key: K; value: EntityMap[K] };
}[EntityKey];
export type Action =
  | Upsert
  | { type: "REPLACE"; state: AppState }
  | { type: "PROFILE"; profile: Profile }
  | { type: "TRANSACTIONS"; rows: Transaction[] }
  | { type: "DELETE_TRANSACTION"; id: string }
  | { type: "REMOVE"; key: EntityKey; id: string }
  | { type: "CONTRIBUTE"; id: string; amount: number }
  | { type: "PAY_BILL"; id: string; transaction: Transaction }
  | { type: "READ_NOTIFICATIONS"; ids: string[] }
  | { type: "SYNC_RESULT"; remap: Record<string, string>; adopt: Adopt[] };

export function reducer(state: AppState, action: Action): AppState {
  let next = state;
  switch (action.type) {
    case "REPLACE":
      return stateSchema.parse(action.state);
    case "PROFILE":
      next = { ...state, profile: action.profile, onboarded: true };
      break;
    case "UPSERT": {
      const list = state[action.key] as EntityMap[EntityKey][];
      next = {
        ...state,
        [action.key]: list.some((e) => e.id === action.value.id)
          ? list.map((e) => (e.id === action.value.id ? action.value : e))
          : [...list, action.value],
      };
      break;
    }
    case "REMOVE": {
      if (
        action.key === "accounts" &&
        (state.accounts.length === 1 ||
          state.transactions.some(
            (t) => t.accountId === action.id || t.toAccountId === action.id,
          ))
      )
        return state;
      next = {
        ...state,
        [action.key]: state[action.key].filter((x) => x.id !== action.id),
      };
      break;
    }
    case "TRANSACTIONS": {
      const rows = new Map(state.transactions.map((t) => [t.id, t]));
      const keys = new Set(
        state.transactions.map((t) => t.importKey).filter(Boolean),
      );
      for (const row of action.rows) {
        if (row.importKey && keys.has(row.importKey) && !rows.has(row.id))
          continue;
        rows.set(row.id, row);
        if (row.importKey) keys.add(row.importKey);
      }
      next = { ...state, transactions: [...rows.values()] };
      break;
    }
    case "DELETE_TRANSACTION": {
      const row = state.transactions.find((t) => t.id === action.id);
      next = {
        ...state,
        transactions: state.transactions.filter((t) => t.id !== action.id),
        bills: state.bills.map((b) =>
          b.lastTransactionId === action.id
            ? {
                ...b,
                paid: false,
                dueDate: row?.billDueDate ?? b.dueDate,
                lastTransactionId: undefined,
              }
            : b,
        ),
      };
      break;
    }
    case "CONTRIBUTE": {
      if (!Number.isFinite(action.amount) || action.amount <= 0) return state;
      next = {
        ...state,
        goals: state.goals.map((g) =>
          g.id === action.id
            ? { ...g, current: roundMoney(g.current + action.amount) }
            : g,
        ),
      };
      break;
    }
    case "PAY_BILL": {
      const bill = state.bills.find((b) => b.id === action.id);
      if (
        !bill ||
        bill.paid ||
        action.transaction.billDueDate !== bill.dueDate ||
        state.transactions.some(
          (t) => t.billId === bill.id && t.billDueDate === bill.dueDate,
        )
      )
        return state;
      const transaction = {
        ...action.transaction,
        amount: bill.amount,
        billId: bill.id,
        billDueDate: bill.dueDate,
        source: "bill" as const,
      };
      next = {
        ...state,
        transactions: [...state.transactions, transaction],
        bills: state.bills.map((b) =>
          b.id === bill.id
            ? {
                ...b,
                paid: !b.recurring,
                dueDate: b.recurring ? shiftMonths(b.dueDate, 1) : b.dueDate,
                lastTransactionId: transaction.id,
              }
            : b,
        ),
      };
      break;
    }
    case "SYNC_RESULT":
      // Server ids replace the temporary client ids after a successful save (see services/sync.ts).
      next = applyRemap(state, action.remap, action.adopt);
      break;
    case "READ_NOTIFICATIONS":
      next = {
        ...state,
        readNotifications: [
          ...new Set([...state.readNotifications, ...action.ids]),
        ],
      };
      break;
  }
  // Keep invalid backups or stale UI actions from corrupting the ledger.
  const result = stateSchema.safeParse(next);
  return result.success ? result.data : state;
}
