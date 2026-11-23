import { emptyState } from "../data/seed";
import { stateSchema, type AppState, type EntityKey } from "../types/models";
import type { SyncApi } from "./sync";
import {
  ENTITY_KEYS,
  toClientEntity,
  toClientProfile,
  toClientTransaction,
  type ServerProfile,
  type ServerTransaction,
} from "./serverMap";

type Page = { content: ServerTransaction[]; page: number; totalPages: number };
type User = { id: string; name: string; email: string };

const MAX_PAGES = 600; // 600 x 100 = the client's 50,000 transaction cap

/**
 * Builds the app's AppState from the server. Rows the client cannot show yet (no account, no date, still
 * needing review) are left out here; they live in the Review screen until the user approves them.
 */
export async function loadRemoteState(
  api: SyncApi,
  user: User,
): Promise<{ state: AppState; dropped: number }> {
  let profile: (ServerProfile & { onboarded?: boolean; readNotifications?: string[] }) | null = null;
  try {
    profile = await api.get("/api/profile");
  } catch (e) {
    if ((e as { status?: number }).status !== 404) throw e; // 404 = not onboarded yet
  }
  const base = emptyState();
  let dropped = 0;
  const lists = {} as Record<EntityKey, unknown[]>;
  for (const key of ENTITY_KEYS) lists[key] = await api.get<unknown[]>(`/api/${key}`);

  if (!lists.accounts.length) {
    // The app always needs at least one account (cash). Create it once, on the server.
    const created = await api.post<unknown>("/api/accounts", {
      name: "Cash",
      type: "cash",
      openingBalance: 0,
      limit: 0,
    });
    lists.accounts = [created];
  }

  const state: AppState = {
    ...base,
    onboarded: profile?.onboarded ?? false,
    profile: toClientProfile(profile, user.name, base.profile),
    readNotifications: profile?.readNotifications ?? [],
  };
  for (const key of ENTITY_KEYS) {
    const mapped = lists[key].map((raw) => toClientEntity(key, raw));
    dropped += mapped.filter((m) => m === null).length;
    (state as unknown as Record<string, unknown>)[key] = mapped.filter((m) => m !== null);
  }

  const accountIds = new Set(state.accounts.map((a) => a.id));
  const rows: AppState["transactions"] = [];
  for (let page = 0; page < MAX_PAGES; page++) {
    const res = await api.get<Page>("/api/transactions", { page, size: 100, needsReview: false });
    for (const raw of res.content) {
      const t = toClientTransaction(raw);
      const ok =
        t !== null &&
        accountIds.has(t.accountId) &&
        (t.type !== "transfer" || (!!t.toAccountId && accountIds.has(t.toAccountId) && t.toAccountId !== t.accountId));
      if (ok) rows.push(t);
      else if (!raw.needsReview && raw.accountId) dropped++;
    }
    if (page + 1 >= res.totalPages) break;
  }
  state.transactions = rows;

  const parsed = stateSchema.safeParse(state);
  if (!parsed.success) throw new Error("Your saved data on the server could not be read by this app version.");
  return { state: parsed.data, dropped };
}
