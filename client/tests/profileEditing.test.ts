import { test } from "node:test";
import assert from "node:assert/strict";
import { buildSetupState, type SetupDraft } from "../src/utils/onboarding";
import { editProfile } from "../src/utils/profileEditing";
const draft: SetupDraft = {
  name: "Priya",
  ageRange: "25–34",
  employment: "Salaried",
  income: "50000",
  sources: ["Salary"],
  salaryDay: "25",
  banks: ["HDFC Bank"],
  balances: { "HDFC Bank": "1000" },
  sip: "1000",
  riskAnswer: "Wait and watch",
};
test("editing every setup answer preserves ledger and account identity", () => {
  const state = buildSetupState(draft);
  const bank = state.accounts.find((a) => a.type === "bank")!;
  state.transactions.push({
    id: "expense",
    type: "expense",
    merchant: "Lunch",
    amount: 100,
    category: "Food",
    method: "Cash",
    date: "2026-09-30",
    accountId: bank.id,
    notes: "",
    source: "manual",
  });
  const result = editProfile(
    state,
    {
      ...draft,
      name: "Updated",
      ageRange: "35–44",
      employment: "Self-employed",
      income: "60000",
      sources: ["Freelance"],
      banks: ["ICICI Bank"],
      balances: { "ICICI Bank": "3000" },
      sip: "2500",
      riskAnswer: "Invest more",
    },
    { [bank.id]: "2000" },
  );
  assert.equal(result.profile.name, "Updated");
  assert.equal(result.profile.ageRange, "35–44");
  assert.equal(result.profile.employment, "Self-employed");
  assert.equal(result.profile.monthlyIncome, 60000);
  assert.equal(result.profile.sipTarget, 2500);
  assert.equal(result.profile.risk, "Aggressive");
  assert.deepEqual(result.profile.incomeSources, ["Freelance"]);
  assert.deepEqual(result.profile.banks, ["ICICI Bank"]);
  assert.equal(
    result.accounts.find((a) => a.id === bank.id)?.openingBalance,
    2000,
  );
  assert.equal(
    result.accounts.find((a) => a.name === "ICICI Bank")?.openingBalance,
    3000,
  );
  assert.deepEqual(result.transactions, state.transactions);
  assert.equal(
    editProfile(result, { ...draft, banks: ["ICICI Bank"], balances: {} }, {})
      .accounts.length,
    result.accounts.length,
  );
});
test("salary-day changes and invalid balances are validated before saving", () => {
  const state = buildSetupState(draft);
  assert.equal(
    editProfile(state, { ...draft, salaryDay: "31" }, {}).profile.salaryDay,
    31,
  );
  const bank = state.accounts.find((a) => a.type === "bank")!;
  assert.throws(() => editProfile(state, draft, { [bank.id]: "-10" }));
  assert.throws(() => editProfile(state, { ...draft, salaryDay: "32" }, {}));
  assert.equal(
    state.accounts.find((a) => a.id === bank.id)?.openingBalance,
    1000,
  );
});
