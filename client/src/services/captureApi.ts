import { z } from "zod";
import { api, errorMessage, hasApiSession, apiBaseUrl } from "./api";

/** Kept for screens that check whether a capture service exists. */
export const captureApiUrl = apiBaseUrl;
const responseSchema = z.object({ text: z.string().min(1).max(20000) });

/**
 * Voice recording -> POST /api/speech/transcribe, receipt photo -> POST /api/receipts/parse.
 * Both return {text}; the app then shows its normal review form. Requires a signed-in session.
 */
export async function extractText(
  kind: "voice" | "receipt",
  uri: string,
  mime: string,
  name: string,
): Promise<string> {
  if (!hasApiSession())
    throw new Error(
      "Sign in to read receipts and recordings. You can type or paste the transaction below.",
    );
  try {
    const json = responseSchema.safeParse(
      await api.upload<unknown>(
        kind === "voice" ? "/api/speech/transcribe" : "/api/receipts/parse",
        "file",
        { uri, type: mime, name },
        60000,
      ),
    );
    if (!json.success)
      throw new Error("The service returned an invalid response. Expected a text field.");
    return json.data.text;
  } catch (e) {
    throw new Error(errorMessage(e, "Could not read that file. Try again or enter the details manually."));
  }
}
