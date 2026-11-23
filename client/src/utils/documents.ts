import type { DocumentItem } from "../services/documentsApi";

/** Single display state derived from (document.status, job.status): the two can disagree (see audit S1). */
export function statusLabel(d: Pick<DocumentItem, "status" | "jobStatus" | "extractedCount" | "duplicateCount" | "errorMessage">): string {
  switch (d.jobStatus) {
    case "PENDING":
      return "Queued";
    case "PROCESSING":
      return "Reading your statement…";
    case "COMPLETED":
      return `${d.extractedCount ?? 0} transactions found${d.duplicateCount ? ` (${d.duplicateCount} duplicates skipped)` : ""}`;
    case "PARTIALLY_COMPLETED":
      return `Found ${d.extractedCount ?? 0}; some lines could not be read. Please review.`;
    case "FAILED":
      return d.errorMessage || "Processing failed.";
    default:
      return d.status === "FAILED" ? d.errorMessage || "Processing failed." : d.status === "PROCESSED" ? "Processed" : "Queued";
  }
}

export const isActive = (d: Pick<DocumentItem, "status" | "jobStatus">) =>
  d.jobStatus === "PENDING" || d.jobStatus === "PROCESSING" || (!d.jobStatus && (d.status === "UPLOADED" || d.status === "PROCESSING"));

export const kb = (bytes: number) => (bytes >= 1048576 ? `${(bytes / 1048576).toFixed(1)} MB` : `${Math.max(1, Math.round(bytes / 1024))} KB`);
