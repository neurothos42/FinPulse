import { api, type FilePart } from "./api";

export type JobStatus = "PENDING" | "PROCESSING" | "COMPLETED" | "PARTIALLY_COMPLETED" | "FAILED";
export type DocumentItem = {
  id: string;
  originalFilename: string;
  contentType: string;
  sizeBytes: number;
  status: "UPLOADED" | "PROCESSING" | "PROCESSED" | "FAILED";
  createdAt: string;
  updatedAt: string;
  jobId?: string;
  jobStatus?: JobStatus;
  extractedCount?: number;
  duplicateCount?: number;
  errorMessage?: string;
};
export type DocumentPage = { content: DocumentItem[]; page: number; size: number; totalElements: number; totalPages: number };
export type Job = {
  id: string;
  documentId: string;
  status: JobStatus;
  attempts: number;
  maxAttempts: number;
  errorMessage: string | null;
  extractedCount: number | null;
  duplicateCount: number | null;
};
export type JobReport = {
  extracted?: number;
  duplicates?: number;
  discarded?: Record<string, number>;
  mappingErrors?: number;
  needsReview?: number;
  unknownDirection?: number;
  ocrConfidence?: number;
  partial?: boolean;
};
export type ReviewRow = {
  id: string;
  date: string | null;
  amount: number;
  direction: "DEBIT" | "CREDIT" | "UNKNOWN";
  merchant: string | null;
  category: string;
  description: string | null;
  accountId: string | null;
  needsReview: boolean;
  possibleDuplicateOf: string | null;
  categoryConfidence: number | null;
};

export const MAX_UPLOAD_BYTES = 10 * 1024 * 1024;
export const ALLOWED_TYPES = ["application/pdf", "image/jpeg", "image/png"];
export const isTerminal = (s?: JobStatus) => s === "COMPLETED" || s === "PARTIALLY_COMPLETED" || s === "FAILED";

/** Client-side pre-check so a bad file never costs an upload. Returns a message or null. */
export function checkUpload(file: { size?: number; mimeType?: string; name: string }): string | null {
  const type = (file.mimeType ?? "").toLowerCase();
  const byName = /\.(pdf|jpe?g|png)$/i.test(file.name);
  if (!ALLOWED_TYPES.includes(type) && !byName) return "Choose a PDF, JPG or PNG file.";
  if ((file.size ?? 0) > MAX_UPLOAD_BYTES) return "That file is larger than 10 MB.";
  if (file.size === 0) return "That file is empty.";
  return null;
}

export const uploadDocument = (file: FilePart) => api.upload<DocumentItem>("/api/documents", "file", file, 90000);
export const listDocuments = (page = 0, size = 20) => api.get<DocumentPage>("/api/documents/page", { page, size });
export const getDocument = (id: string) => api.get<DocumentItem>(`/api/documents/${id}`);
export const getJob = (id: string) => api.get<Job>(`/api/jobs/${id}`);
export const getJobReport = (id: string) => api.get<JobReport>(`/api/jobs/${id}/report`);
export const deleteDocument = (id: string) => api.del(`/api/documents/${id}`);
export const reprocessDocument = (id: string) => api.post<DocumentItem>(`/api/documents/${id}/reprocess`);
export const reviewRows = (documentId: string, page = 0) =>
  api.get<{ content: ReviewRow[]; totalElements: number; totalPages: number }>("/api/transactions", {
    documentId,
    needsReview: true,
    page,
    size: 100,
  });
export const assignAccount = (documentId: string, accountId: string) =>
  api.post<{ updated: number }>(`/api/documents/${documentId}/assign-account`, { accountId });
export const approveAll = (documentId: string) =>
  api.post<{ approved: number; stillNeedReview: number }>(`/api/documents/${documentId}/approve`);
export const patchRow = (id: string, body: Record<string, unknown>) => api.patch<ReviewRow>(`/api/transactions/${id}`, body);
export const deleteRow = (id: string) => api.del(`/api/transactions/${id}`);

export type VoiceResult = {
  id: string;
  speechToText: string;
  status: "COMPLETED" | "PARTIALLY_COMPLETED" | "NO_TRANSACTIONS";
  transactions: { id: string; merchant: string | null; amount: number; date: string | null; category: string }[];
  skipped: { index: number; reason: string }[];
};
/** Gemini turns what the user said into one cash transaction per expense and saves them. */
export const saveVoiceExpenses = (speechToText: string) => api.post<VoiceResult>("/api/voice/expenses", { speechToText });
