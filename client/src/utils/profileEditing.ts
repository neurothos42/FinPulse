import { stateSchema, profileSchema, type AppState } from "../types/models";
import { amountValue, type SetupDraft, setupError } from "./onboarding";
import { riskAnswers } from "../data/onboarding";
import { uid } from "./date";
export function editProfile(
  state: AppState,
  draft: SetupDraft,
  accountBalances: Record<string, string>,
): AppState {
  for (const key of [
    "name",
    "age",
    "employment",
    "income",
    "sources",
    ...(draft.sources.includes("Salary") ? ["salaryDay"] : []),
    "sip",
    "risk",
  ]) {
    const error = setupError(key, draft);
    if (error) throw new Error(error);
  }
  const accounts = state.accounts.map((account) => {
    if (account.type !== "bank") return account;
    const openingBalance = amountValue(
      accountBalances[account.id] ?? String(account.openingBalance),
    );
    if (!Number.isFinite(openingBalance))
      throw new Error(`Enter a valid opening balance for ${account.name}.`);
    return { ...account, openingBalance };
  });
  for (const bank of new Set(draft.banks)) {
    if (accounts.some((a) => a.type === "bank" && a.name === bank)) continue;
    const openingBalance = amountValue(draft.balances[bank] ?? "0");
    if (!Number.isFinite(openingBalance))
      throw new Error(`Enter a valid opening balance for ${bank}.`);
    accounts.push({
      id: uid("bank"),
      name: bank,
      type: "bank",
      openingBalance,
      limit: 0,
    });
  }
  return stateSchema.parse({
    ...state,
    accounts,
    profile: profileSchema.parse({
      ...state.profile,
      name: draft.name.trim(),
      ageRange: draft.ageRange,
      employment: draft.employment,
      monthlyIncome: amountValue(draft.income),
      incomeSources: draft.sources,
      salaryDay: draft.sources.includes("Salary")
        ? Number(draft.salaryDay)
        : state.profile.salaryDay,
      banks: [...new Set(draft.banks)],
      sipTarget: amountValue(draft.sip),
      riskAnswer: draft.riskAnswer,
      risk: riskAnswers.find((a) => a.label === draft.riskAnswer)!.profile,
    }),
  });
}
