import {
  ageRanges,
  employments,
  riskQuestion,
  riskAnswers,
} from "../data/onboarding";
import { QuestionChoices } from "../components/QuestionChoices";
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
} from "../components/ui";
import { useApp } from "../store/AppProvider";
import { profileSchema, risks, type Risk } from "../types/models";
import { numberInput } from "../utils/format";
export function ProfileScreen({
  navigation,
}: NativeStackScreenProps<RootStackParams, "Profile">) {
  const { state, dispatch } = useApp(),
    p = state.profile;
  const [name, setName] = useState(p.name),
    [ageRange, setAgeRange] = useState(p.ageRange ?? ""),
    [employment, setEmployment] = useState(p.employment ?? ""),
    [riskAnswer, setRiskAnswer] = useState(p.riskAnswer),
    [income, setIncome] = useState(String(p.monthlyIncome)),
    [salaryDay, setSalaryDay] = useState(String(p.salaryDay)),
    [sip, setSip] = useState(String(p.sipTarget)),
    [risk, setRisk] = useState<Risk>(p.risk),
    [error, setError] = useState<string | null>(null);
  function save() {
    const result = profileSchema.safeParse({
      ...p,
      name: name.trim(),
      ageRange: ageRange || undefined,
      employment: employment || undefined,
      riskAnswer,
      monthlyIncome: numberInput(income),
      salaryDay: Number(salaryDay),
      sipTarget: numberInput(sip),
      risk,
    });
    if (!result.success) {
      setError("Enter your name, valid amounts and a salary day from 1 to 31.");
      return;
    }
    dispatch({ type: "PROFILE", profile: result.data });
    navigation.goBack();
  }
  return (
    <Screen>
      <T size={25} bold style={{ marginBottom: 20 }}>
        Your profile
      </T>
      <Card>
        <Field label="Full name" value={name} onChangeText={setName} />
        <Select
          label="Age range"
          value={ageRange}
          options={[
            { value: "", label: "Not specified" },
            ...ageRanges.map((v) => ({ value: v, label: v })),
          ]}
          onChange={setAgeRange}
        />
        <Select
          label="Employment"
          value={employment}
          options={[
            { value: "", label: "Not specified" },
            ...employments.map((v) => ({ value: v, label: v })),
          ]}
          onChange={setEmployment}
        />
        <T muted style={{ marginBottom: 16 }}>
          Banks selected during setup: {p.banks?.join(", ") || "Not specified"}.
          Manage account names and balances in Money → Accounts.
        </T>
        <Field
          label="Expected monthly income (₹)"
          value={income}
          onChangeText={setIncome}
          keyboardType="decimal-pad"
        />
        <Field
          label="Salary day (1–31)"
          value={salaryDay}
          onChangeText={setSalaryDay}
          keyboardType="number-pad"
        />
        <Field
          label="Monthly investment target (₹)"
          value={sip}
          onChangeText={setSip}
          keyboardType="decimal-pad"
        />
        <Select
          label="Planning risk profile"
          value={risk}
          options={risks}
          onChange={(v) => {
            setRisk(v as Risk);
            setRiskAnswer(undefined);
          }}
        />
        <T bold style={{ marginVertical: 16 }}>
          {riskQuestion}
        </T>
        <QuestionChoices
          options={riskAnswers.map((a) => a.label)}
          selected={riskAnswer ? [riskAnswer] : []}
          onSelect={(v) => {
            const answer = riskAnswers.find((a) => a.label === v)!;
            setRiskAnswer(answer.label);
            setRisk(answer.profile);
          }}
        />
      </Card>
      <T muted size={12} style={{ marginBottom: 15 }}>
        Expected income is used for planning. Dashboard income comes only from
        recorded transactions.
      </T>
      <ErrorText message={error} />
      <Button title="Save profile" onPress={save} />
    </Screen>
  );
}
