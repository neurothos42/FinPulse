import { emptyState } from "../data/seed";
import { profileSchema, stateSchema } from "../types/models";
import { riskAnswers } from "../data/onboarding";
export type SetupDraft = {
  name: string;
  ageRange: string;
  employment: string;
  income: string;
  sources: string[];
  salaryDay: string;
  banks: string[];
  balances: Record<string, string>;
  sip: string;
  riskAnswer: string;
};
export function amountValue(value: string) {
  if (!/^\d+(\.\d{1,2})?$/.test(value.trim())) return NaN;
  const n = Number(value);
  return n <= 1e12 ? n : NaN;
}
export function setupQuestions(d: SetupDraft) {
  return [
    "name",
    "age",
    "employment",
    "income",
    "sources",
    ...(d.sources.includes("Salary") ? ["salaryDay"] : []),
    "banks",
    ...d.banks.map((b) => `balance:${b}`),
    "sip",
    "risk",
  ];
}
export function setupError(key: string, d: SetupDraft): string | null {
  if (key === "name" && (d.name.trim().length < 2 || d.name.trim().length > 50))
    return "Enter your name using 2–50 characters.";
  if (key === "age" && !d.ageRange) return "Choose your age range.";
  if (key === "employment" && !d.employment)
    return "Choose your employment status.";
  if (key === "sources" && Number(d.income) > 0 && !d.sources.length)
    return "Choose at least one income source.";
  if (
    key === "salaryDay" &&
    (!/^\d+$/.test(d.salaryDay) ||
      Number(d.salaryDay) < 1 ||
      Number(d.salaryDay) > 31)
  )
    return "Choose a salary day from 1 to 31.";
  if (key === "risk" && !riskAnswers.some((a) => a.label === d.riskAnswer))
    return "Choose how you would respond to this scenario.";
  const amount =
    key === "income"
      ? d.income
      : key === "sip"
        ? d.sip
        : key.startsWith("balance:")
          ? (d.balances[key.slice(8)] ?? "")
          : null;
  if (amount !== null && !Number.isFinite(amountValue(amount)))
    return "Enter zero or a positive amount, with at most two decimal places (up to ₹1 trillion).";
  return null;
}
export function buildSetupState(d: SetupDraft) {
  for (const key of setupQuestions(d)) {
    const error = setupError(key, d);
    if (error) throw new Error(error);
  }
  const fresh = emptyState();
  const profile = profileSchema.parse({
    ...fresh.profile,
    name: d.name.trim(),
    ageRange: d.ageRange,
    employment: d.employment,
    monthlyIncome: amountValue(d.income),
    incomeSources: d.sources,
    salaryDay: d.sources.includes("Salary") ? Number(d.salaryDay) : 1,
    banks: [...new Set(d.banks)],
    sipTarget: amountValue(d.sip),
    riskAnswer: d.riskAnswer,
    risk: riskAnswers.find((a) => a.label === d.riskAnswer)!.profile,
  });
  return stateSchema.parse({
    ...fresh,
    profile,
    onboarded: true,
    accounts: [
      ...fresh.accounts,
      ...profile.banks!.map((bank, i) => ({
        id: `bank-${i + 1}`,
        name: bank,
        type: "bank",
        openingBalance: amountValue(d.balances[bank] ?? ""),
        limit: 0,
      })),
    ],
  });
}
