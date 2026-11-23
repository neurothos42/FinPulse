import React, {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useReducer,
  useRef,
  useState,
} from "react";
import { AppState as NativeAppState } from "react-native";
import { emptyState, demoState } from "../data/seed";
import { loadState, saveState } from "../services/storage";
import { reducer, type Action } from "./reducer";
import type { AppState } from "../types/models";
import { isoDate } from "../utils/date";
import { api, errorMessage, ApiError } from "../services/api";
import { loadRemoteState } from "../services/remote";
import { hasChanges, pushChanges } from "../services/sync";

type Store = {
  state: AppState;
  dispatch: React.Dispatch<Action>;
  ready: boolean;
  error: string | null;
  blocked: boolean;
  recover: (state: AppState) => Promise<void>;
  retry: () => Promise<void>;
  today: string;
  /** true when the data lives on the backend (signed-in session). */
  online: boolean;
  /** Re-reads everything from the backend (e.g. after the worker added rows). */
  reload: () => Promise<void>;
  /** Set when the first load from the backend failed; the app shows a retry screen. */
  loadError: string | null;
};
const Context = createContext<Store | null>(null);
export function AppProvider({
  children,
  scope,
  demo = false,
  remote,
}: {
  children: React.ReactNode;
  scope?: string;
  demo?: boolean;
  /** Signed-in user: data is loaded from and saved to the backend (the API client already holds the token). */
  remote?: { id: string; name: string; email: string };
}) {
  const online = !!remote && !demo;
  const [state, dispatch] = useReducer(
    reducer,
    undefined,
    demo ? demoState : emptyState,
  );
  const [ready, setReady] = useState(false),
    [error, setError] = useState<string | null>(null),
    [loadError, setLoadError] = useState<string | null>(null),
    [blocked, setBlocked] = useState(false),
    [today, setToday] = useState(isoDate());
  const stateRef = useRef(state);
  stateRef.current = state;
  const confirmed = useRef<AppState | null>(null); // what the server holds
  const pushing = useRef(false);
  const again = useRef(false);
  const retryTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      if (retryTimer.current) clearTimeout(retryTimer.current);
    };
  }, []);

  const reload = useCallback(async () => {
    if (!remote) return;
    const { state: fresh, dropped } = await loadRemoteState(api, remote);
    if (!mounted.current) return;
    confirmed.current = fresh;
    dispatch({ type: "REPLACE", state: fresh });
    setLoadError(null);
    if (dropped > 0)
      setError(`${dropped} item(s) from the server could not be shown by this app version.`);
  }, [remote]);

  // ---- initial load ---------------------------------------------------------------------------
  useEffect(() => {
    let active = true;
    if (online) {
      reload()
        .catch(async (e) => {
          if (!active) return;
          if (e instanceof ApiError && e.network) {
            // Offline: fall back to the last copy saved on this device.
            try {
              const cached = await loadState(scope);
              if (cached && active) {
                confirmed.current = cached;
                dispatch({ type: "REPLACE", state: cached });
                setError("You are offline. Showing your last saved data; changes will sync when you reconnect.");
                return;
              }
            } catch {
              /* fall through to the error screen */
            }
          }
          setLoadError(errorMessage(e, "Could not load your data."));
        })
        .finally(() => {
          if (active) setReady(true);
        });
    } else {
      loadState(scope)
        .then((s) => {
          if (active && s) dispatch({ type: "REPLACE", state: s });
        })
        .catch((e) => {
          if (active) {
            setBlocked(true);
            setError(String(e.message ?? e));
          }
        })
        .finally(() => {
          if (active) setReady(true);
        });
    }
    return () => {
      active = false;
    };
  }, [scope, online, reload]);

  // ---- local cache (offline copy; the source of truth is the server when online) ------------
  useEffect(() => {
    if (!ready || blocked || loadError) return;
    let active = true;
    saveState(state, scope)
      .then(() => {
        if (active && !online) setError(null);
      })
      .catch(() => {
        if (active && !online)
          setError(
            "Changes are in memory but could not be saved. Free up storage, then retry.",
          );
      });
    return () => {
      active = false;
    };
  }, [state, ready, blocked, scope, online, loadError]);

  // ---- push local changes to the backend -----------------------------------------------------
  const flush = useCallback(async () => {
    if (!online || !confirmed.current) return;
    if (pushing.current) {
      again.current = true;
      return;
    }
    pushing.current = true;
    try {
      do {
        again.current = false;
        const base = confirmed.current;
        const desired = stateRef.current;
        if (!base || !hasChanges(base, desired)) break;
        const result = await pushChanges(api, base, desired);
        confirmed.current = result.confirmed;
        if (!mounted.current) return;
        if (Object.keys(result.remap).length || result.adopt.length)
          dispatch({ type: "SYNC_RESULT", remap: result.remap, adopt: result.adopt });
        if (result.error) {
          const e = result.error;
          if (e instanceof ApiError && e.network) {
            setError("You are offline. Your changes will sync when you reconnect.");
            if (retryTimer.current) clearTimeout(retryTimer.current);
            retryTimer.current = setTimeout(() => void flush(), 15000);
          } else {
            // The server refused a change: show why and go back to what the server holds.
            const message = errorMessage(e, "A change could not be saved.");
            try {
              await reload();
            } catch {
              /* keep the local copy; the user can retry */
            }
            if (mounted.current) setError(`Could not save: ${message}`);
          }
          return;
        }
        setError((cur) => (cur && cur.startsWith("You are offline") ? null : cur));
      } while (again.current);
    } finally {
      pushing.current = false;
    }
  }, [online, reload]);

  useEffect(() => {
    if (!online || !ready || !confirmed.current) return;
    if (!hasChanges(confirmed.current, state)) return;
    const timer = setTimeout(() => void flush(), 400);
    return () => clearTimeout(timer);
  }, [state, online, ready, flush]);

  useEffect(() => {
    const sub = NativeAppState.addEventListener("change", (s) => {
      if (s === "active") {
        setToday(isoDate());
        if (online) void flush();
      }
    });
    const timer = setInterval(() => setToday(isoDate()), 60000);
    return () => {
      sub.remove();
      clearInterval(timer);
    };
  }, [scope, online, flush]);

  const recover = useCallback(
    async (s: AppState) => {
      await saveState(s, scope);
      dispatch({ type: "REPLACE", state: s });
      setBlocked(false);
      setError(null);
    },
    [scope],
  );
  const retry = useCallback(async () => {
    if (online) {
      setError(null);
      if (confirmed.current) await flush();
      else await reload().catch((e) => setLoadError(errorMessage(e)));
      return;
    }
    try {
      await saveState(state, scope);
      setError(null);
    } catch {
      setError(
        "Storage is still unavailable. Export a backup before closing the app.",
      );
    }
  }, [state, scope, online, flush, reload]);
  return (
    <Context.Provider
      value={{ state, dispatch, ready, error, blocked, recover, retry, today, online, reload, loadError }}
    >
      {children}
    </Context.Provider>
  );
}
export function useApp() {
  const ctx = useContext(Context);
  if (!ctx) throw new Error("AppProvider is missing");
  return ctx;
}
