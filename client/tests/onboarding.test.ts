import { test } from "node:test";
import assert from "node:assert/strict";
import {
  buildSetupState,
  setupError,
  setupQuestions,
  type SetupDraft,
} from "../src/utils/onboarding";
import { stateSchema } from "../src/types/models";
import { emptyState } from "../src/data/seed";
const draft = (): SetupDraft => ({
  name: "  Priya Sharma  ",
  ageRange: "25–34",
  employment: "Salaried",
  income: "50000",
  sources: ["Salary"],
  salaryDay: "31",
  banks: ["HDFC Bank", "State Bank of India"],
  balances: { "HDFC Bank": "1200.50", "State Bank of India": "800" },
  sip: "5000",
  riskAnswer: "Wait and watch",
});
test("setup persists demographics and selected bank balances without inventing transactions", () => {
  const result = buildSetupState(draft());
  assert.equal(result.profile.name, "Priya Sharma");
  assert.equal(result.profile.ageRange, "25–34");
  assert.equal(result.profile.employment, "Salaried");
  assert.deepEqual(result.profile.banks, draft().banks);
  assert.equal(
    result.accounts.find((a) => a.name === "HDFC Bank")?.openingBalance,
    1200.5,
  );
  assert.equal(
    result.accounts.find((a) => a.name === "State Bank of India")
      ?.openingBalance,
    800,
  );
  assert.equal(result.transactions.length, 0);
  assert.deepEqual(
    stateSchema.parse(JSON.parse(JSON.stringify(result))),
    result,
  );
});
test("salary and per-bank questions respond to choices and preserve bank order", () => {
  const d = draft();
  assert.ok(setupQuestions(d).includes("salaryDay"));
  assert.ok(setupQuestions(d).includes("balance:HDFC Bank"));
  d.sources = ["Freelance"];
  d.banks = [];
  assert.ok(!setupQuestions(d).includes("salaryDay"));
  assert.ok(!setupQuestions(d).some((q) => q.startsWith("balance:")));
});
test("zero-income cash-only setup succeeds", () => {
  const d = { ...draft(), income: "0", sources: [], banks: [], sip: "0" };
  const result = buildSetupState(d);
  assert.equal(result.accounts.length, 1);
  assert.equal(result.accounts[0].type, "cash");
});
test("negative, malformed, over-limit and over-precision money is rejected at its question", () => {
  for (const income of [
    "",
    "-1",
    "NaN",
    "1e4",
    "1.234",
    "1000000000001",
    "50abc",
  ])
    assert.ok(setupError("income", { ...draft(), income }));
  for (const salaryDay of ["0", "32", "1.5", ""])
    assert.ok(setupError("salaryDay", { ...draft(), salaryDay }));
  assert.ok(setupError("age", { ...draft(), ageRange: "" }));
  assert.ok(setupError("employment", { ...draft(), employment: "" }));
  assert.ok(setupError("sources", { ...draft(), sources: [] }));
  assert.ok(setupError("risk", { ...draft(), riskAnswer: "" }));
});
test("scenario answers map consistently to planning preferences", () => {
  for (const [riskAnswer, risk] of [
    ["Sell immediately", "Conservative"],
    ["Wait and watch", "Moderate"],
    ["Invest more", "Aggressive"],
  ])
    assert.equal(
      buildSetupState({ ...draft(), riskAnswer }).profile.risk,
      risk,
    );
});
test("existing backups without new optional fields remain valid", () => {
  assert.ok(stateSchema.safeParse(emptyState()).success);
});
