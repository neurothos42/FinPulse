import {
  createContext,
  useContext,
  useEffect,
  useState,
  type ReactNode,
} from "react";
import { AppState, Platform } from "react-native";
import * as SecureStore from "expo-secure-store";
import { sessionSchema, type Session } from "../services/auth";

const KEY = "finpulse-auth-session-v1";
type Auth = {
  session: Session | null;
  ready: boolean;
  demo: boolean;
  local: boolean;
  enterLocal: () => void;
  error: string | null;
  accept: (session: Session) => Promise<void>;
  signOut: () => Promise<void>;
  exploreDemo: () => void;
};
const Context = createContext<Auth | null>(null);
export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(null),
    [ready, setReady] = useState(false),
    [demo, setDemo] = useState(false),
    [local, setLocal] = useState(false),
    [error, setError] = useState<string | null>(null);
  useEffect(() => {
    let active = true;
    (async () => {
      try {
        const raw =
          Platform.OS === "web" ? null : await SecureStore.getItemAsync(KEY);
        const parsed = raw ? sessionSchema.safeParse(JSON.parse(raw)) : null;
        if (active && parsed?.success && parsed.data.expiresAt > Date.now())
          setSession(parsed.data);
      } catch {
        if (active)
          setError(
            "Your previous sign-in could not be restored. Please sign in again.",
          );
      } finally {
        if (active) setReady(true);
      }
    })();
    return () => {
      active = false;
    };
  }, []);
  useEffect(() => {
    if (!session) return;
    function check() {
      if (session && session.expiresAt <= Date.now()) {
        setSession(null);
        setError("Your session expired. Please sign in again.");
      }
    }
    const timer = setTimeout(
      check,
      Math.min(Math.max(0, session.expiresAt - Date.now()), 2147483647),
    );
    const sub = AppState.addEventListener("change", (value) => {
      if (value === "active") check();
    });
    return () => {
      clearTimeout(timer);
      sub.remove();
    };
  }, [session]);
  async function accept(value: Session) {
    // Browser sessions are memory-only. Native tokens live in the OS secure store.
    if (Platform.OS !== "web")
      await SecureStore.setItemAsync(KEY, JSON.stringify(value));
    setError(null);
    setDemo(false);
    setLocal(false);
    setSession(value);
  }
  async function signOut() {
    if (Platform.OS !== "web") await SecureStore.deleteItemAsync(KEY);
    setSession(null);
    setDemo(false);
    setLocal(false);
    setError(null);
  }
  return (
    <Context.Provider
      value={{
        session,
        ready,
        demo,
        local,
        enterLocal: () => {
          setSession(null);
          setDemo(false);
          setLocal(true);
          setError(null);
        },
        error,
        accept,
        signOut,
        exploreDemo: () => {
          setError(null);
          setSession(null);
          setLocal(false);
          setDemo(true);
        },
      }}
    >
      {children}
    </Context.Provider>
  );
}
export function useAuth() {
  const value = useContext(Context);
  if (!value) throw new Error("AuthProvider is missing");
  return value;
}
