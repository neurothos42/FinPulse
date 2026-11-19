import { z } from "zod";

export const authApiUrl = (
  process.env.EXPO_PUBLIC_AUTH_API_URL ??
  process.env.EXPO_PUBLIC_API_URL ??
  ""
).replace(/\/$/, "");
export const sessionSchema = z.object({
  accessToken: z.string().min(1),
  expiresAt: z.number().positive(),
  user: z.object({
    id: z.string().min(1),
    name: z.string().min(1),
    email: z.email(),
  }),
});
export type Session = z.infer<typeof sessionSchema>;
const userSchema = sessionSchema.shape.user;
const loginResponse = z.object({
  accessToken: z.string().min(1),
  expiresIn: z.number().positive(),
  user: sessionSchema.shape.user,
});
async function request(path: string, body: object) {
  if (!authApiUrl)
    throw new Error(
      "Sign-in is not connected yet. You can explore the demo below.",
    );
  const url = new URL(authApiUrl);
  if (
    url.protocol !== "https:" &&
    !(typeof __DEV__ !== "undefined" && __DEV__ && url.protocol === "http:")
  )
    throw new Error("Sign-in requires a secure HTTPS server.");
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 15000);
  try {
    const response = await fetch(`${authApiUrl}/api/auth/${path}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
      signal: controller.signal,
    });
    if (!response.ok) {
      if (response.status === 401)
        throw new Error("Email or password is incorrect.");
      if (response.status === 409)
        throw new Error("That email is already registered. Please sign in.");
      if (response.status === 400)
        throw new Error("Check your details and try again.");
      throw new Error("Sign-in is temporarily unavailable. Please try again.");
    }
    return await response.json();
  } catch (e) {
    if (
      e instanceof Error &&
      (e.name === "AbortError" || e instanceof TypeError)
    )
      throw new Error(
        "Could not reach the sign-in server. Check your connection and try again.",
      );
    throw e;
  } finally {
    clearTimeout(timer);
  }
}
export async function signIn(
  email: string,
  password: string,
): Promise<Session> {
  const parsed = loginResponse.safeParse(
    await request("login", { email: email.trim().toLowerCase(), password }),
  );
  if (!parsed.success)
    throw new Error("The sign-in server returned an unexpected response.");
  return {
    accessToken: parsed.data.accessToken,
    user: parsed.data.user,
    expiresAt: Date.now() + parsed.data.expiresIn * 1000,
  };
}
export async function getCurrentUser(accessToken: string) {
  if (!authApiUrl) throw new Error("The sign-in server is not configured.");
  const url = new URL(authApiUrl);
  if (
    url.protocol !== "https:" &&
    !(typeof __DEV__ !== "undefined" && __DEV__ && url.protocol === "http:")
  )
    throw new Error("Sign-in requires a secure HTTPS server.");

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 15000);
  try {
    const response = await fetch(`${authApiUrl}/api/users/me`, {
      headers: { Authorization: `Bearer ${accessToken}` },
      signal: controller.signal,
    });
    if (response.status === 401 || response.status === 403) return null;
    if (!response.ok)
      throw new Error("Could not verify your session with the server.");
    const parsed = userSchema.safeParse(await response.json());
    if (!parsed.success)
      throw new Error("The server returned an unexpected user profile.");
    return parsed.data;
  } catch (e) {
    if (
      e instanceof Error &&
      (e.name === "AbortError" || e instanceof TypeError)
    )
      throw new Error("Could not reach the sign-in server.");
    throw e;
  } finally {
    clearTimeout(timer);
  }
}

export async function register(name: string, email: string, password: string) {
  await request("register", {
    name: name.trim(),
    email: email.trim().toLowerCase(),
    password,
  });
}
