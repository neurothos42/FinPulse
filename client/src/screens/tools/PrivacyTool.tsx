import { useState } from "react";
import {
  Screen,
  T,
  Card,
  Section,
  Button,
  ErrorText,
  Note,
} from "../../components/ui";
import { useApp } from "../../store/AppProvider";
import { stateSchema, type AppState } from "../../types/models";
import { emptyState, demoState } from "../../data/seed";
import { pickTextFile, exportText } from "../../services/files";
import { loadState } from "../../services/storage";
import { api, errorMessage } from "../../services/api";
import { transactionsCsv } from "../../utils/imports";
export function PrivacyTool() {
  const { state, recover, online, reload } = useApp(),
    [error, setError] = useState<string | null>(null),
    [pending, setPending] = useState<AppState | null>(null),
    [reason, setReason] = useState(""),
    [mode, setMode] = useState<"replace" | "merge" | "delete">("replace"),
    [notice, setNotice] = useState<string | null>(null),
    [busy, setBusy] = useState(false);
  async function restore() {
    setError(null);
    try {
      const text = await pickTextFile(true);
      if (text === null) return;
      const data = stateSchema.safeParse(JSON.parse(text));
      if (!data.success)
        throw new Error(
          "This is not a valid FinPulse v1 backup. Your current data has not changed.",
        );
      setPending(data.data);
      setMode("replace");
      setReason(
        `Restore ${data.data.transactions.length} transactions, ${data.data.goals.length} goals and ${data.data.accounts.length} accounts?`,
      );
    } catch (e) {
      setError((e as Error).message);
    }
  }
  async function loadEarlierRecords() {
    setError(null);
    try {
      const earlier = await loadState();
      if (!earlier?.onboarded) {
        setError("No earlier on-device records were found.");
        return;
      }
      setPending(earlier);
      setMode("merge");
      setReason(
        `Copy ${earlier.transactions.length} earlier transactions and ${earlier.goals.length} goals into this profile? The original records are retained.`,
      );
    } catch {
      setError(
        "Earlier records could not be read. Restore a JSON backup instead.",
      );
    }
  }
  async function confirm() {
    if (!pending) return;
    setBusy(true);
    setNotice(null);
    try {
      if (online) {
        // Signed in: the server is the source of truth. Replace = erase then import; merge = import (safe to repeat).
        if (mode !== "merge") await api.del("/api/users/me/data");
        if (mode !== "delete") {
          const r = await api.post<{ imported: Record<string, number>; errors: unknown[] }>(
            "/api/users/me/import",
            pending,
            120000,
          );
          setNotice(
            `Imported ${r.imported.transactions ?? 0} transactions and ${r.imported.accounts ?? 0} accounts` +
              (r.errors.length ? `; ${r.errors.length} item(s) could not be imported.` : "."),
          );
        }
        await reload();
      } else await recover(pending);
      setPending(null);
    } catch (e) {
      if (online) {
        setError(errorMessage(e, "The change could not be saved."));
        return;
      }
      setError(
        "The new data could not be saved. Your existing records are still active.",
      );
    } finally {
      setBusy(false);
    }
  }
  async function downloadAll() {
    setError(null);
    try {
      const data = await api.get<unknown>("/api/users/me/export");
      await exportText("finpulse-backup.json", JSON.stringify(data, null, 2), "application/json");
    } catch (e) {
      setError(errorMessage(e, "Could not download your data."));
    }
  }
  async function csv() {
    try {
      await exportText(
        "finpulse-all-transactions.csv",
        transactionsCsv(
          state.transactions,
          (id) => state.accounts.find((a) => a.id === id)?.name ?? id,
        ),
        "text/csv",
      );
    } catch (e) {
      setError((e as Error).message);
    }
  }
  return (
    <Screen>
      <T size={25} bold>
        Privacy & data
      </T>
      <Note>
        {online
          ? "Your records are stored in your FinPulse account on the server and cached on this device. Voice, receipt and statement reading are processed by the FinPulse server and an AI service when you use them. Bank sync is not connected."
          : "Your records are stored locally. Bank sync and cloud backup are not connected. Voice recognition may use your device or browser speech service. Receipt uploads happen only when you use a configured service."}
      </Note>
      <Card>
        <T bold>
          {state.transactions.length} transactions · {state.goals.length} goals
        </T>
        <T size={12} muted style={{ marginTop: 10 }}>
          Local records and exported backups are not encrypted by this app.
          Device authentication controls access to the UI; it does not encrypt
          the ledger. Keep your device and backups private.
        </T>
      </Card>
      <ErrorText message={error} />
      {notice ? <T size={12} style={{ marginBottom: 10 }}>{notice}</T> : null}
      {online ? (
        <Button
          title="Download all my data (JSON)"
          variant="secondary"
          style={{ marginBottom: 12 }}
          onPress={() => void downloadAll()}
        />
      ) : null}
      <Button
        title="Export every transaction as CSV"
        variant="secondary"
        onPress={() => void csv()}
      />
      <Button
        title="Restore a JSON backup"
        variant="secondary"
        onPress={() => void restore()}
        style={{ marginTop: 12 }}
      />
      <Button
        title="Import earlier on-device records"
        variant="secondary"
        onPress={() => void loadEarlierRecords()}
        style={{ marginTop: 12 }}
      />
      <Section title="Start again" />
      {!online ? (
        <Button
          title="Replace with demo data"
          variant="secondary"
          onPress={() => {
            setMode("replace");
            setReason(
              "Replace all current financial records with the sample demo?",
            );
            setPending(demoState());
          }}
        />
      ) : null}
      <Button
        title="Delete my financial data"
        variant="danger"
        style={{ marginTop: 12 }}
        onPress={() => {
          setReason(
            "Delete all financial records and return to setup? Export a backup first if you need to keep them.",
          );
          setMode("delete");
          setPending(emptyState());
        }}
      />
      {pending ? (
        <Card style={{ marginTop: 20 }}>
          <T bold style={{ marginBottom: 12 }}>
            {reason}
          </T>
          <T size={12} muted style={{ marginBottom: 16 }}>
            {online ? (mode === "merge" ? "Existing records on your account are kept." : "This permanently deletes the current records on your account.") : "This replaces your current records on this device."}
          </T>
          <Button
            title={busy ? "Saving…" : "Confirm replacement"}
            disabled={busy}
            variant="danger"
            onPress={() => void confirm()}
          />
          <Button
            title="Cancel"
            variant="secondary"
            style={{ marginTop: 10 }}
            onPress={() => setPending(null)}
          />
        </Card>
      ) : null}
    </Screen>
  );
}
