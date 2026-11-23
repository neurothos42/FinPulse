import { useEffect, useRef, useState } from "react";
import { AppState, View } from "react-native";
import { Mic } from "lucide-react-native";
import type { NativeStackScreenProps } from "@react-navigation/native-stack";
import type { RootStackParams } from "../navigation/types";
import {
  Screen,
  T,
  Card,
  Field,
  Button,
  ErrorText,
  Note,
} from "../components/ui";
import { speech } from "../services/speech";
import { useApp } from "../store/AppProvider";
import { saveVoiceExpenses, type VoiceResult } from "../services/documentsApi";
import { errorMessage } from "../services/api";
import { inr } from "../utils/format";
import { parseAlert } from "../utils/imports";
import { useTheme } from "../theme/ThemeProvider";

export function VoiceScreen({
  navigation,
}: NativeStackScreenProps<RootStackParams, "Voice">) {
  const { state, online, reload } = useApp(),
    c = useTheme();
  const [text, setText] = useState(""),
    [listening, setListening] = useState(false),
    [busy, setBusy] = useState(false),
    [error, setError] = useState<string | null>(null),
    [saving, setSaving] = useState(false),
    [result, setResult] = useState<VoiceResult | null>(null);
  const mounted = useRef(true);
  const available = !!speech?.isRecognitionAvailable();
  useEffect(() => {
    mounted.current = true;
    const listeners = speech
      ? [
          speech.addListener("start", () => {
            setListening(true);
            setBusy(false);
          }),
          speech.addListener("end", () => {
            setListening(false);
            setBusy(false);
          }),
          speech.addListener("result", (e) => {
            const transcript = e.results[0]?.transcript;
            if (transcript) setText(transcript);
          }),
          speech.addListener("error", (e) => {
            setListening(false);
            setBusy(false);
            if (e.error !== "aborted")
              setError(
                e.error === "not-allowed"
                  ? "Allow microphone and speech access in your device settings, or type below."
                  : e.error === "no-speech"
                    ? "No speech heard. Try again or type your expense."
                    : "Could not recognise that. Try again or type your expense.",
              );
          }),
        ]
      : [];
    const sub = AppState.addEventListener("change", (value) => {
      if (value !== "active") {
        speech?.abort();
        setListening(false);
        setBusy(false);
      }
    });
    return () => {
      mounted.current = false;
      listeners.forEach((l) => l.remove());
      sub.remove();
      speech?.abort();
    };
  }, []);
  async function toggle() {
    if (!speech) return;
    setError(null);
    if (listening) {
      speech.stop();
      return;
    }
    setBusy(true);
    try {
      const permission = await speech.requestPermissionsAsync();
      if (!mounted.current) return;
      if (!permission.granted) {
        setBusy(false);
        setError(
          "Microphone or speech permission was denied. You can still type below.",
        );
        return;
      }
      speech.start({
        lang: "en-IN",
        interimResults: true,
        continuous: false,
        maxAlternatives: 1,
      });
    } catch {
      if (mounted.current) {
        setBusy(false);
        setError(
          "Voice recognition is unavailable here. Try typing your expense.",
        );
      }
    }
  }
  /** Online: Gemini splits what was said into one cash expense each and the server saves them. */
  async function saveAll() {
    setError(null);
    setSaving(true);
    try {
      const r = await saveVoiceExpenses(text.trim());
      if (!mounted.current) return;
      setResult(r);
      await reload(); // the new cash transactions now show up everywhere
    } catch (e) {
      if (mounted.current) setError(errorMessage(e, "Could not save that."));
    } finally {
      if (mounted.current) setSaving(false);
    }
  }
  function review() {
    const row = parseAlert(text, state.accounts[0].id);
    if (!row)
      return setError(
        "Include an amount and merchant, for example: Paid 420 rupees to Swiggy by UPI.",
      );
    const account = state.accounts.find(
      (a) =>
        a.type ===
        (row.method === "Cash"
          ? "cash"
          : row.method === "Credit Card"
            ? "credit"
            : "bank"),
    );
    navigation.navigate("Transaction", {
      draft: {
        ...row,
        accountId: account?.id ?? state.accounts[0].id,
        source: "voice",
        importKey: undefined,
      },
    });
  }
  return (
    <Screen
      title="Say it. Then review it."
      subtitle="A quick way to capture an expense."
    >
      <Card>
        <View style={{ alignItems: "center", paddingVertical: 26 }}>
          <View
            style={{
              padding: 24,
              borderRadius: 50,
              backgroundColor: c.greenBg,
            }}
          >
            <Mic size={44} color={c.green} />
          </View>
          <T bold size={18} style={{ marginTop: 18 }}>
            {listening ? "Listening…" : "What did you spend?"}
          </T>
          <T muted size={12} style={{ textAlign: "center", marginTop: 8 }}>
            “Paid four hundred and twenty rupees to Swiggy by UPI.”
          </T>
        </View>
        <Button
          title={
            busy ? "Starting…" : listening ? "Stop listening" : "Start speaking"
          }
          disabled={!available || busy}
          onPress={() => void toggle()}
        />
      </Card>
      <Note>
        {available
          ? "Your device or browser speech service turns your voice into text and may process audio online. Nothing is added until you review and save."
          : "Speech recognition is unavailable in this app or browser. You can type below or use your keyboard’s dictation button."}
      </Note>
      <Field
        label="Your transaction"
        value={text}
        onChangeText={setText}
        multiline
        style={{ minHeight: 120 }}
        placeholder="Paid 420 rupees to Swiggy by UPI"
        editable={!listening}
      />
      <ErrorText message={error} />
      {result ? (
        <Card>
          <T bold>
            {result.transactions.length
              ? `Added ${result.transactions.length} cash expense${result.transactions.length === 1 ? "" : "s"}`
              : "No expenses found in that"}
          </T>
          {result.transactions.map((t) => (
            <T key={t.id} size={12} muted style={{ marginTop: 4 }}>
              {t.merchant} · {inr(t.amount)} · {t.category}
            </T>
          ))}
          {result.skipped.map((s) => (
            <T key={s.index} size={11} muted style={{ marginTop: 4 }}>
              Skipped one item: {s.reason}
            </T>
          ))}
          <Button title="Done" style={{ marginTop: 12 }} onPress={() => navigation.goBack()} />
        </Card>
      ) : null}
      {online && !result ? (
        <Button
          title={saving ? "Adding…" : "Add expenses with AI"}
          disabled={!text.trim() || listening || busy || saving}
          onPress={() => void saveAll()}
          style={{ marginBottom: 12 }}
        />
      ) : null}
      <Button
        title="Review transaction"
        disabled={!text.trim() || listening || busy}
        onPress={review}
      />
      <Button
        title="Enter expense manually"
        variant="secondary"
        style={{ marginTop: 12 }}
        disabled={listening || busy}
        onPress={() => navigation.replace("Expense")}
      />
    </Screen>
  );
}
