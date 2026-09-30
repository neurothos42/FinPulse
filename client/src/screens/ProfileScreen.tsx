import { useState } from "react";
import type { NativeStackScreenProps } from "@react-navigation/native-stack";
import type { RootStackParams } from "../navigation/types";
import {
  Screen,
  T,
  Field,
  Select,
  Card,
  Button,
  ErrorText,
  Note,
} from "../components/ui";
import { QuestionChoices } from "../components/QuestionChoices";
import {
  ageRanges,
  employments,
  incomeSources,
  bankNames,
  riskQuestion,
  riskAnswers,
} from "../data/onboarding";
import { useApp } from "../store/AppProvider";
import type { SetupDraft } from "../utils/onboarding";
import { editProfile } from "../utils/profileEditing";
export function ProfileScreen({
  navigation,
}: NativeStackScreenProps<RootStackParams, "Profile">) {
  const { state, dispatch } = useApp(),
    p = state.profile;
  const [draft, setDraft] = useState<SetupDraft>({
    name: p.name,
    ageRange: p.ageRange ?? "",
    employment: p.employment ?? "",
    income: String(p.monthlyIncome),
    sources: p.incomeSources ?? [],
    salaryDay: String(p.salaryDay),
    banks:
      p.banks ??
      state.accounts.filter((a) => a.type === "bank").map((a) => a.name),
    balances: {},
    sip: String(p.sipTarget),
    riskAnswer:
      p.riskAnswer ?? riskAnswers.find((a) => a.profile === p.risk)!.label,
  });
  const [balances, setBalances] = useState<Record<string, string>>(() =>
    Object.fromEntries(
      state.accounts
        .filter((a) => a.type === "bank")
        .map((a) => [a.id, String(a.openingBalance)]),
    ),
  );
  const [search, setSearch] = useState("");
  const [error, setError] = useState<string | null>(null);
  function update(patch: Partial<SetupDraft>) {
    setDraft((d) => ({ ...d, ...patch }));
    setError(null);
  }
  function toggle(key: "sources" | "banks", value: string) {
    update({
      [key]: draft[key].includes(value)
        ? draft[key].filter((v) => v !== value)
        : [...draft[key], value],
    });
  }
  function save() {
    try {
      dispatch({ type: "REPLACE", state: editProfile(state, draft, balances) });
      navigation.goBack();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Check your answers.");
    }
  }
  const availableBanks = [
    ...new Set([
      ...bankNames,
      ...draft.banks,
      ...state.accounts.filter((a) => a.type === "bank").map((a) => a.name),
    ]),
  ];
  return (
    <Screen title="Your profile" subtitle="Update any answer from your setup.">
      <Card>
        <Field
          label="Full name"
          value={draft.name}
          onChangeText={(name) => update({ name })}
          maxLength={50}
        />
        <Select
          label="Age range"
          value={draft.ageRange}
          options={[...ageRanges]}
          onChange={(ageRange) => update({ ageRange })}
        />
        <Select
          label="Employment"
          value={draft.employment}
          options={[...employments]}
          onChange={(employment) => update({ employment })}
        />
        <Field
          label="Expected monthly income (₹)"
          value={draft.income}
          onChangeText={(income) => update({ income })}
          keyboardType="decimal-pad"
        />
        <T bold style={{ marginBottom: 12 }}>
          Income sources
        </T>
        <QuestionChoices
          multiple
          options={incomeSources}
          selected={draft.sources}
          onSelect={(v) => toggle("sources", v)}
        />
        {draft.sources.includes("Salary") && (
          <Field
            label="Salary day (1–31)"
            value={draft.salaryDay}
            onChangeText={(salaryDay) => update({ salaryDay })}
            keyboardType="number-pad"
          />
        )}
      </Card>
      <Card>
        <T bold size={20}>
          Your banks
        </T>
        <Field label="Search banks" value={search} onChangeText={setSearch} />
        <T muted style={{ marginBottom: 12 }}>
          Selected: {draft.banks.join(", ") || "None"}
        </T>
        <QuestionChoices
          multiple
          options={availableBanks.filter((b) =>
            b.toLowerCase().includes(search.toLowerCase()),
          )}
          selected={draft.banks}
          onSelect={(v) => toggle("banks", v)}
        />
        <Note>
          Changing this selection updates your bank preferences. Existing
          accounts and their transactions are kept. Remove unused accounts from
          Money → Accounts.
        </Note>
      </Card>
      <Card>
        <T bold size={20} style={{ marginBottom: 12 }}>
          Bank opening balances
        </T>
        <Note>
          These are your starting balances, not today's balances. Editing them
          changes the calculated balance without adding a transaction.
        </Note>
        {state.accounts
          .filter((a) => a.type === "bank")
          .map((a) => (
            <Field
              key={a.id}
              label={`${a.name} opening balance (₹)`}
              value={balances[a.id]}
              onChangeText={(v) => setBalances((b) => ({ ...b, [a.id]: v }))}
              keyboardType="decimal-pad"
            />
          ))}
        {draft.banks
          .filter(
            (b) =>
              !state.accounts.some((a) => a.type === "bank" && a.name === b),
          )
          .map((bank) => (
            <Field
              key={bank}
              label={`${bank} opening balance (₹)`}
              value={draft.balances[bank] ?? "0"}
              onChangeText={(v) =>
                update({ balances: { ...draft.balances, [bank]: v } })
              }
              keyboardType="decimal-pad"
            />
          ))}
      </Card>
      <Card>
        <Field
          label="Monthly investment target (₹)"
          value={draft.sip}
          onChangeText={(sip) => update({ sip })}
          keyboardType="decimal-pad"
        />
        <T bold style={{ marginBottom: 16 }}>
          {riskQuestion}
        </T>
        <QuestionChoices
          options={riskAnswers.map((a) => a.label)}
          selected={[draft.riskAnswer]}
          onSelect={(riskAnswer) => update({ riskAnswer })}
        />
      </Card>
      <ErrorText message={error} />
      <Button title="Save profile" onPress={save} />
    </Screen>
  );
}
