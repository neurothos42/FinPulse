import { useCallback, useEffect, useRef, useState } from "react";
import { View } from "react-native";
import type { NativeStackScreenProps } from "@react-navigation/native-stack";
import type { RootStackParams } from "../navigation/types";
import { Screen, T, Card, Row, Section, Button, Chip, Chips, Field, ErrorText, Note, Empty } from "../components/ui";
import { useApp } from "../store/AppProvider";
import { errorMessage } from "../services/api";
import {
  approveAll,
  assignAccount,
  deleteDocument,
  deleteRow,
  getDocument,
  getJob,
  getJobReport,
  isTerminal,
  patchRow,
  reprocessDocument,
  reviewRows,
  type DocumentItem,
  type Job,
  type JobReport,
  type ReviewRow,
} from "../services/documentsApi";
import { categories } from "../types/models";
import { inr } from "../utils/format";
import { statusLabel, kb } from "../utils/documents";

const directions = ["DEBIT", "CREDIT"] as const;
type Direction = (typeof directions)[number];

export function DocumentDetailScreen({
  route,
  navigation,
}: NativeStackScreenProps<RootStackParams, "DocumentDetail">) {
  const id = route.params.id;
  const { state, reload } = useApp();
  const [doc, setDoc] = useState<DocumentItem | null>(null),
    [job, setJob] = useState<Job | null>(null),
    [report, setReport] = useState<JobReport | null>(null),
    [rows, setRows] = useState<ReviewRow[]>([]),
    [loading, setLoading] = useState(true),
    [busy, setBusy] = useState(false),
    [error, setError] = useState<string | null>(null),
    [message, setMessage] = useState<string | null>(null),
    [accountId, setAccountId] = useState<string>(state.accounts[0]?.id ?? ""),
    [editing, setEditing] = useState<string | null>(null),
    [confirm, setConfirm] = useState<string | null>(null);
  const mounted = useRef(true);
  const polls = useRef(0);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  const load = useCallback(async () => {
    try {
      const d = await getDocument(id);
      const j = d.jobId ? await getJob(d.jobId) : null;
      let r: JobReport | null = null;
      let review: ReviewRow[] = [];
      if (j && isTerminal(j.status) && j.status !== "FAILED") {
        r = await getJobReport(j.id).catch(() => null); // 404 until the worker has written it
        review = (await reviewRows(id)).content;
      }
      if (!mounted.current) return;
      setDoc(d);
      setJob(j);
      setReport(r);
      setRows(review);
      setError(null);
    } catch (e) {
      if (mounted.current) setError(errorMessage(e, "Could not load this document."));
    } finally {
      if (mounted.current) setLoading(false);
    }
  }, [id]);
  useEffect(() => {
    void load();
  }, [load]);

  const finished = job ? isTerminal(job.status) : false;
  useEffect(() => {
    if (finished || !doc || error) return;
    const delay = Math.min(10000, 2500 + polls.current * 1000);
    const timer = setTimeout(() => {
      polls.current += 1;
      void load();
    }, delay);
    return () => clearTimeout(timer);
  }, [doc, job, finished, error, load]);

  async function run(action: () => Promise<string | void>) {
    setBusy(true);
    setError(null);
    setMessage(null);
    try {
      const done = await action();
      if (done && mounted.current) setMessage(done);
      await reload(); // approved rows now belong in Money, Home and Reports
      await load();
    } catch (e) {
      if (mounted.current) setError(errorMessage(e));
    } finally {
      if (mounted.current) setBusy(false);
      setConfirm(null);
    }
  }

  const missingAccount = rows.some((r) => !r.accountId);
  const account = state.accounts.find((a) => a.id === accountId);

  if (loading) return <Screen title="Document"><T muted>Loading…</T></Screen>;
  if (!doc)
    return (
      <Screen title="Document">
        <ErrorText message={error} />
        <Button title="Try again" onPress={() => void load()} />
      </Screen>
    );
  const discarded = Object.values<number>(report?.discarded ?? {}).reduce((s, n) => s + n, 0);
  return (
    <Screen title={doc.originalFilename} subtitle={`${kb(doc.sizeBytes)} · ${doc.contentType.split("/")[1]?.toUpperCase()}`}>
      <Card>
        <T bold>{statusLabel({ ...doc, ...(job ? { jobStatus: job.status, extractedCount: job.extractedCount ?? undefined, duplicateCount: job.duplicateCount ?? undefined, errorMessage: job.errorMessage ?? undefined } : {}) })}</T>
        {job && !finished ? (
          <T size={11} muted style={{ marginTop: 6 }}>
            Attempt {Math.max(1, job.attempts)} of {job.maxAttempts}
            {job.status === "PENDING" && job.attempts === 0 ? " · waiting for the processing service" : ""}
          </T>
        ) : null}
        {report ? (
          <View style={{ marginTop: 10 }}>
            <T size={12} muted>
              {report.extracted ?? 0} added for review · {report.duplicates ?? 0} duplicates skipped
              {discarded ? ` · ${discarded} lines ignored` : ""}
              {report.ocrConfidence ? ` · photo clarity ${Math.round(report.ocrConfidence)}%` : ""}
            </T>
          </View>
        ) : null}
      </Card>
      <ErrorText message={error} />
      {message ? <T size={12} style={{ marginBottom: 8 }}>{message}</T> : null}

      {finished && job?.status !== "FAILED" ? (
        <>
          <Section title={rows.length ? `Review ${rows.length} transaction${rows.length === 1 ? "" : "s"}` : "Review"} />
          {rows.length ? (
            <>
              <Note>
                Check the transactions below. Approved ones are added to your records. A “possible duplicate” may
                already be in your records, so delete it if it is.
              </Note>
              <T size={11} bold muted style={{ marginBottom: 6 }}>PUT THEM IN ACCOUNT</T>
              <View style={{ flexDirection: "row", flexWrap: "wrap", gap: 8, marginBottom: 14 }}>
                {state.accounts.map((a) => (
                  <Chip key={a.id} label={a.name} active={a.id === accountId} onPress={() => setAccountId(a.id)} />
                ))}
              </View>
              <Button
                title={busy ? "Working…" : `Approve all${missingAccount && account ? ` into ${account.name}` : ""}`}
                disabled={busy || (missingAccount && !accountId)}
                onPress={() =>
                  void run(async () => {
                    if (missingAccount) await assignAccount(id, accountId);
                    const r = await approveAll(id);
                    return r.stillNeedReview
                      ? `${r.approved} added. ${r.stillNeedReview} still need your attention (missing date/direction or possible duplicates).`
                      : `${r.approved} transactions added to your records.`;
                  })
                }
              />
              {rows.map((r) => (
                <ReviewCard
                  key={r.id}
                  row={r}
                  busy={busy}
                  editing={editing === r.id}
                  confirming={confirm === r.id}
                  onEdit={() => setEditing(editing === r.id ? null : r.id)}
                  onApprove={() =>
                    void run(async () => {
                      await patchRow(r.id, { needsReview: false, ...(r.accountId ? {} : { accountId }) });
                    })
                  }
                  onSave={(body) =>
                    void run(async () => {
                      await patchRow(r.id, { ...body, ...(r.accountId ? {} : { accountId }) });
                      setEditing(null);
                    })
                  }
                  onDelete={() => (confirm === r.id ? void run(() => deleteRow(r.id)) : setConfirm(r.id))}
                />
              ))}
            </>
          ) : (
            <Empty
              title="Nothing left to review"
              body={job?.extractedCount ? "Everything from this document is already in your records." : "No new transactions were found in this document."}
            />
          )}
        </>
      ) : null}

      {finished ? (
        <>
          <Section title="Document" />
          {job?.status !== "COMPLETED" ? (
            <Button title="Read it again" variant="secondary" disabled={busy} onPress={() => void run(async () => { await reprocessDocument(id); polls.current = 0; return "Queued again."; })} />
          ) : null}
        </>
      ) : null}
      <Button
        title={confirm === "doc" ? "Confirm delete document" : "Delete document"}
        variant="danger"
        style={{ marginTop: 12 }}
        disabled={busy}
        onPress={() => {
          if (confirm !== "doc") return setConfirm("doc");
          void (async () => {
            setBusy(true);
            try {
              await deleteDocument(id);
              navigation.goBack();
            } catch (e) {
              setError(errorMessage(e));
              setConfirm(null);
              setBusy(false);
            }
          })();
        }}
      />
      <T size={11} muted style={{ marginTop: 8 }}>
        Deleting the document does not remove transactions that were already added to your records.
      </T>
    </Screen>
  );
}

function ReviewCard({
  row,
  busy,
  editing,
  confirming,
  onEdit,
  onApprove,
  onSave,
  onDelete,
}: {
  row: ReviewRow;
  busy: boolean;
  editing: boolean;
  confirming: boolean;
  onEdit: () => void;
  onApprove: () => void;
  onSave: (body: Record<string, unknown>) => void;
  onDelete: () => void;
}) {
  const [merchant, setMerchant] = useState(row.merchant ?? ""),
    [date, setDate] = useState(row.date ?? ""),
    [direction, setDirection] = useState<Direction>(row.direction === "CREDIT" ? "CREDIT" : "DEBIT"),
    [category, setCategory] = useState<string>("");
  const incomplete = !row.date || row.direction === "UNKNOWN";
  return (
    <Card>
      <Row style={{ justifyContent: "space-between" }}>
        <View style={{ flex: 1 }}>
          <T bold>{row.merchant || (row.description ?? "Transaction").slice(0, 40)}</T>
          <T size={11} muted>
            {row.date ?? "No date"} · {row.category}
            {row.direction === "UNKNOWN" ? " · direction unclear" : ""}
          </T>
        </View>
        <T bold>
          {row.direction === "CREDIT" ? "+" : row.direction === "DEBIT" ? "−" : ""}
          {inr(row.amount)}
        </T>
      </Row>
      {row.possibleDuplicateOf ? (
        <T size={11} style={{ marginTop: 6 }}>⚠️ Possible duplicate of a transaction you already have.</T>
      ) : null}
      {incomplete ? <T size={11} style={{ marginTop: 6 }}>Add the missing details, then approve.</T> : null}
      {editing ? (
        <View style={{ marginTop: 12 }}>
          <Field label="Merchant" value={merchant} onChangeText={setMerchant} maxLength={200} />
          <Field label="Date (YYYY-MM-DD)" value={date} onChangeText={setDate} autoCapitalize="none" maxLength={10} />
          <Chips options={directions} value={direction} onChange={setDirection} />
          <Chips options={["Keep category", ...categories]} value={category || "Keep category"} onChange={(c) => setCategory(c === "Keep category" ? "" : c)} />
          <Button
            title="Save changes"
            disabled={busy || !/^\d{4}-\d{2}-\d{2}$/.test(date) || !merchant.trim()}
            onPress={() => onSave({ merchant: merchant.trim(), date, direction, ...(category ? { category } : {}) })}
          />
        </View>
      ) : null}
      <Row style={{ marginTop: 12, flexWrap: "wrap" }}>
        <Button title="Looks right" disabled={busy || incomplete} onPress={onApprove} style={{ flex: 1 }} />
        <Button title={editing ? "Close" : "Edit"} variant="secondary" disabled={busy} onPress={onEdit} style={{ flex: 1 }} />
        <Button title={confirming ? "Sure?" : "Delete"} variant="danger" disabled={busy} onPress={onDelete} style={{ flex: 1 }} />
      </Row>
    </Card>
  );
}
