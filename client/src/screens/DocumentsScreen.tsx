import { useCallback, useEffect, useRef, useState } from "react";
import { View } from "react-native";
import * as DocumentPicker from "expo-document-picker";
import { useFocusEffect } from "@react-navigation/native";
import { Screen, T, Card, Row, Section, Button, ErrorText, Note, Empty } from "../components/ui";
import { useApp } from "../store/AppProvider";
import { useNav } from "../hooks/useNav";
import { errorMessage } from "../services/api";
import {
  ALLOWED_TYPES,
  checkUpload,
  listDocuments,
  uploadDocument,
  type DocumentItem,
} from "../services/documentsApi";
import { dateLabel } from "../utils/date";
import { statusLabel, isActive, kb } from "../utils/documents";

export function DocumentsScreen() {
  const { online } = useApp(),
    nav = useNav();
  const [items, setItems] = useState<DocumentItem[]>([]),
    [loading, setLoading] = useState(true),
    [uploading, setUploading] = useState(false),
    [error, setError] = useState<string | null>(null),
    [info, setInfo] = useState<string | null>(null);
  const mounted = useRef(true);
  const polls = useRef(0);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  const load = useCallback(async (silent = false) => {
    if (!silent) setLoading(true);
    try {
      const page = await listDocuments(0, 50);
      if (!mounted.current) return;
      setItems(page.content);
      setError(null);
    } catch (e) {
      if (mounted.current) setError(errorMessage(e, "Could not load your documents."));
    } finally {
      if (mounted.current && !silent) setLoading(false);
    }
  }, []);

  useFocusEffect(
    useCallback(() => {
      if (online) void load();
      else setLoading(false);
    }, [online, load]),
  );

  // Poll while something is still being processed: 3 s, backing off to 10 s.
  const active = items.some(isActive);
  useEffect(() => {
    if (!online || !active) {
      polls.current = 0;
      return;
    }
    const delay = Math.min(10000, 3000 + polls.current * 1000);
    const timer = setTimeout(() => {
      polls.current += 1;
      void load(true);
    }, delay);
    return () => clearTimeout(timer);
  }, [items, active, online, load]);

  async function pick() {
    setError(null);
    setInfo(null);
    try {
      const result = await DocumentPicker.getDocumentAsync({
        type: ALLOWED_TYPES,
        copyToCacheDirectory: true,
        multiple: false,
      });
      if (result.canceled) return;
      const asset = result.assets[0];
      const problem = checkUpload({ size: asset.size, mimeType: asset.mimeType, name: asset.name });
      if (problem) return setError(problem);
      setUploading(true);
      const doc = await uploadDocument({
        uri: asset.uri,
        name: asset.name,
        type: asset.mimeType ?? (asset.name.toLowerCase().endsWith(".pdf") ? "application/pdf" : "image/jpeg"),
      });
      polls.current = 0;
      setInfo("Uploaded. We’re reading it now.");
      await load(true);
      if (mounted.current) nav.navigate("DocumentDetail", { id: doc.id });
    } catch (e) {
      if (mounted.current) setError(errorMessage(e, "Upload failed."));
    } finally {
      if (mounted.current) setUploading(false);
    }
  }

  if (!online)
    return (
      <Screen title="Statements" subtitle="Bank statements and receipts">
        <Empty
          title="Sign in to upload statements"
          body="Statement and receipt reading runs on the FinPulse server, so it needs a signed-in account."
        />
      </Screen>
    );
  return (
    <Screen title="Statements" subtitle="Upload a bank statement PDF or a receipt photo.">
      <Button
        title={uploading ? "Uploading…" : "＋ Upload statement or receipt"}
        disabled={uploading}
        onPress={() => void pick()}
      />
      <Note>
        PDF, JPG or PNG up to 10 MB. We read the transactions, skip ones you already have, and let you review
        anything unclear before it is added to your records.
      </Note>
      <ErrorText message={error} />
      {info ? (
        <T size={12} muted style={{ marginBottom: 8 }}>
          {info}
        </T>
      ) : null}
      <Section title="History" />
      {loading ? <T muted>Loading…</T> : null}
      {error && !items.length && !loading ? (
        <Button title="Try again" variant="secondary" onPress={() => void load()} />
      ) : null}
      {!loading && !error && !items.length ? (
        <Empty title="No documents yet" body="Upload your first statement to see it here." />
      ) : null}
      {items.map((d) => (
        <Card key={d.id} onPress={() => nav.navigate("DocumentDetail", { id: d.id })}>
          <Row style={{ justifyContent: "space-between" }}>
            <View style={{ flex: 1 }}>
              <T bold numberOfLines={1}>
                {d.originalFilename}
              </T>
              <T size={11} muted>
                {dateLabel(d.createdAt.slice(0, 10))} · {kb(d.sizeBytes)}
              </T>
            </View>
          </Row>
          <T size={12} style={{ marginTop: 8 }} muted={d.jobStatus !== "FAILED"}>
            {statusLabel(d)}
          </T>
        </Card>
      ))}
    </Screen>
  );
}
