import { Platform } from "react-native";

/**
 * The one place that talks HTTP to the FinPulse backend: base URL, Bearer token, timeouts,
 * ErrorResponse parsing and a global 401 handler. Tokens are never logged.
 */
export const apiBaseUrl = (
  process.env.EXPO_PUBLIC_AUTH_API_URL ??
  process.env.EXPO_PUBLIC_API_URL ??
  ""
).replace(/\/$/, "");

export type Query = Record<string, string | number | boolean | undefined>;
export type FilePart = { uri: string; name: string; type: string };

export class ApiError extends Error {
  readonly status: number;
  readonly validationErrors: { field: string; message: string }[];
  readonly retryAfter?: number;
  /** true when the server could not be reached at all (offline, DNS, timeout). */
  readonly network: boolean;
  constructor(
    status: number,
    message: string,
    opts: {
      validationErrors?: { field: string; message: string }[];
      retryAfter?: number;
      network?: boolean;
    } = {},
  ) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.validationErrors = opts.validationErrors ?? [];
    this.retryAfter = opts.retryAfter;
    this.network = opts.network ?? false;
  }
}

/** Short text that is safe to show to a user. */
export function errorMessage(
  e: unknown,
  fallback = "Something went wrong.",
): string {
  if (e instanceof ApiError) {
    if (e.network) return "Could not reach the server. Check your connection.";
    if (e.status === 401) return "Your session ended. Please sign in again.";
    if (e.status === 429)
      return e.retryAfter
        ? `Too many requests. Try again in ${e.retryAfter} seconds.`
        : "Too many requests. Please wait a moment.";
    if (e.status === 413) return "That file is too large (maximum 10 MB).";
    if (e.status === 415) return "That file type is not supported.";
    if (e.status >= 500 && e.status !== 503)
      return "The server had a problem. Please try again.";
    const first = e.validationErrors[0];
    return first ? `${first.field}: ${first.message}` : e.message || fallback;
  }
  return e instanceof Error && e.message ? e.message : fallback;
}

export type ApiClient = {
  get<T>(path: string, query?: Query): Promise<T>;
  post<T>(path: string, body?: unknown, timeoutMs?: number): Promise<T>;
  put<T>(path: string, body?: unknown): Promise<T>;
  patch<T>(path: string, body?: unknown): Promise<T>;
  del(path: string): Promise<void>;
  upload<T>(
    path: string,
    field: string,
    file: FilePart,
    timeoutMs?: number,
  ): Promise<T>;
};

type Opts = {
  query?: Query;
  body?: unknown;
  form?: FormData;
  timeoutMs?: number;
};

export function createApi(
  getToken: () => string | null,
  onUnauthorized: () => void,
  baseUrl: string = apiBaseUrl,
  fetchImpl: typeof fetch = fetch,
): ApiClient {
  async function request<T>(
    method: string,
    path: string,
    o: Opts = {},
  ): Promise<T> {
    if (!baseUrl)
      throw new ApiError(
        0,
        "The server address is not configured (EXPO_PUBLIC_API_URL).",
        {
          network: true,
        },
      );
    const url = new URL(baseUrl);
    if (
      url.protocol !== "https:" &&
      !(typeof __DEV__ !== "undefined" && __DEV__ && url.protocol === "http:")
    )
      throw new ApiError(0, "The server must use HTTPS.");
    const qs = Object.entries(o.query ?? {})
      .filter(([, v]) => v !== undefined && v !== "")
      .map(
        ([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`,
      )
      .join("&");
    const headers: Record<string, string> = { Accept: "application/json" };
    const token = getToken();
    if (token) headers.Authorization = `Bearer ${token}`;
    let body: BodyInit | undefined;
    if (o.form)
      body = o.form; // never set Content-Type: the runtime adds the multipart boundary
    else if (o.body !== undefined) {
      headers["Content-Type"] = "application/json";
      body = JSON.stringify(o.body);
    }
    // --------DEv
    console.log("🚀 API REQUEST:", {
      method,
      url: `${baseUrl}${path}${qs ? `?${qs}` : ""}`,
      query: o.query,
      body: o.body,
      authenticated: !!token,
    });
    // -----------Dev
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), o.timeoutMs ?? 15000);
    let response: Response;
    try {
      response = await fetchImpl(`${baseUrl}${path}${qs ? `?${qs}` : ""}`, {
        method,
        headers,
        body,
        signal: controller.signal,
      });
    } catch {
      throw new ApiError(0, "Could not reach the server.", { network: true });
    } finally {
      clearTimeout(timer);
    }
    if (response.ok) {
      if (response.status === 204) return undefined as T;
      const text = await response.text();
      return (text ? JSON.parse(text) : undefined) as T;
    }
    let message = response.statusText || "Request failed";
    let validationErrors: { field: string; message: string }[] | undefined;
    try {
      const j = (await response.json()) as {
        message?: string;
        validationErrors?: { field: string; message: string }[];
      };
      if (j.message) message = j.message;
      validationErrors = j.validationErrors;
    } catch {
      /* body was not JSON */
    }
    const retry = Number(response.headers.get("Retry-After"));
    if (response.status === 401 && token) onUnauthorized();
    throw new ApiError(response.status, message, {
      validationErrors,
      retryAfter: Number.isFinite(retry) && retry > 0 ? retry : undefined,
    });
  }
  return {
    get: (path, query) => request("GET", path, { query }),
    post: (path, body, timeoutMs) => request("POST", path, { body, timeoutMs }),
    put: (path, body) => request("PUT", path, { body }),
    patch: (path, body) => request("PATCH", path, { body }),
    del: (path) => request<void>("DELETE", path),

    async upload<T>(
      path: string,
      field: string,
      file: FilePart,
      timeoutMs = 90000,
    ) {
      const form = new FormData();

      if (Platform.OS === "web") {
        const blob = await (await fetchImpl(file.uri)).blob();
        form.append(field, blob, file.name);
      } else {
        form.append(field, {
          uri: file.uri,
          name: file.name,
          type: file.type,
        } as unknown as Blob);
      }

      return request<T>("POST", path, {
        form,
        timeoutMs,
      });
    },
  };
}

// ---- shared instance used by the app -------------------------------------------------------------
let token: string | null = null;
let unauthorized: () => void = () => {};
/** Called by AuthProvider whenever the session changes. */
export function setApiSession(
  accessToken: string | null,
  onUnauthorized: () => void,
) {
  token = accessToken;
  unauthorized = onUnauthorized;
}
export const api: ApiClient = createApi(
  () => token,
  () => unauthorized(),
);
export const hasApiSession = () => token !== null;
