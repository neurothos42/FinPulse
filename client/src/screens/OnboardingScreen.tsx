import { useState } from "react";
import { View } from "react-native";
import { LinearGradient } from "expo-linear-gradient";
import {
  Screen,
  T,
  Button,
  Field,
  Progress,
  ErrorText,
  Row,
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
import { useAuth } from "../store/AuthProvider";
import {
  buildSetupState,
  setupQuestions,
  setupError,
  type SetupDraft,
} from "../utils/onboarding";

export function OnboardingScreen({
  initialStep = 0,
  onGetStarted,
  initialName = "",
}: {
  initialStep?: number;
  onGetStarted?: () => void;
  initialName?: string;
}) {
  const { dispatch } = useApp();
  const auth = useAuth();
  const [started, setStarted] = useState(initialStep > 0);
  const [question, setQuestion] = useState("name");
  const [search, setSearch] = useState("");
  const [draft, setDraft] = useState<SetupDraft>({
    name: initialName,
    ageRange: "",
    employment: "",
    income: "",
    sources: [],
    salaryDay: "1",
    banks: [],
    balances: {},
    sip: "0",
    riskAnswer: "",
  });
  const [error, setError] = useState<string | null>(null);
  const questions = setupQuestions(draft),
    index = questions.indexOf(question);
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
  function next() {
    const problem = setupError(question, draft);
    if (problem) return setError(problem);
    if (index < questions.length - 1) {
      setQuestion(questions[index + 1]);
      setError(null);
      return;
    }
    try {
      dispatch({ type: "REPLACE", state: buildSetupState(draft) });
    } catch (e) {
      setError(
        e instanceof Error ? e.message : "Check your answers and try again.",
      );
    }
  }
  const bank = question.startsWith("balance:") ? question.slice(8) : "";
  const titles: Record<string, string> = {
    name: "What should we call you?",
    age: "Which age range are you in?",
    employment: "What do you do?",
    income: "What do you earn each month?",
    sources: "Where does your income come from?",
    salaryDay: "When does your salary arrive?",
    banks: "Which banks do you use?",
    sip: "What would you like to invest monthly?",
    risk: riskQuestion,
  };
  const hints: Record<string, string> = {
    name: "Let’s make FinPulse feel like yours.",
    age: "Your age range helps put your plans in context.",
    employment: "Choose the option that best describes you today.",
    income: "An average is fine. Enter 0 if you do not have an income yet.",
    sources:
      "Choose all that apply. With no income, you can continue without a source.",
    salaryDay: "Choose a day from 1 to 31. Shorter months use their last day.",
    banks:
      "Choose all that apply. These create your account labels; this does not connect to your bank.",
    sip: "This is a planning target. Enter 0 if you want to decide later.",
    risk: "One scenario to set an initial planning preference. This is not a full investment suitability assessment.",
  };
  return (
    <Screen tab key={started ? question : "welcome"}>
      <View style={{ paddingTop: 15 }}>
        <T size={13} bold style={{ letterSpacing: 3, marginBottom: 24 }}>
          FINPULSE
        </T>
        {!started ? (
          <>
            <LinearGradient
              colors={["#0b1220", "#154c3c"]}
              style={{ borderRadius: 28, padding: 28, marginBottom: 30 }}
            >
              <T size={52}>🌱</T>
              <T
                size={35}
                bold
                style={{ color: "white", lineHeight: 42, marginVertical: 16 }}
              >
                A little clarity.{"\n"}A better money life.
              </T>
              <T style={{ color: "#c8ded5" }}>
                Spend mindfully. Build your buffer. Make room for what matters.
              </T>
            </LinearGradient>
            <T size={14} muted style={{ marginBottom: 24 }}>
              Your expense manager, goals and investment planner, together in
              one place.
            </T>
            <Button
              title="Get started"
              onPress={onGetStarted ?? (() => setStarted(true))}
            />
            <T muted size={11} style={{ marginTop: 16, textAlign: "center" }}>
              Your expense manager, savings goals and investment plans.
            </T>
          </>
        ) : (
          <View key={question}>
            <Row style={{ justifyContent: "space-between", marginBottom: 12 }}>
              <T muted size={12}>
                ONE QUESTION AT A TIME
              </T>
              <T muted size={12}>
                {index + 1} / {questions.length}
              </T>
            </Row>
            <Progress value={((index + 1) / questions.length) * 100} />
            <T
              accessibilityRole="header"
              size={28}
              bold
              style={{ marginTop: 26, marginBottom: 12 }}
            >
              {bank ? `How much is in your ${bank} account?` : titles[question]}
            </T>
            <T muted style={{ marginBottom: 24 }}>
              {bank
                ? "Enter the current balance once. It becomes the opening balance, not income. Enter 0 if you prefer to update it later."
                : hints[question]}
            </T>
            {question === "name" && (
              <Field
                label="Your name"
                value={draft.name}
                onChangeText={(name) => update({ name })}
                autoComplete="name"
                maxLength={50}
                onSubmitEditing={next}
              />
            )}
            {question === "age" && (
              <QuestionChoices
                options={ageRanges}
                selected={[draft.ageRange]}
                onSelect={(ageRange) => update({ ageRange })}
              />
            )}
            {question === "employment" && (
              <QuestionChoices
                options={employments}
                selected={[draft.employment]}
                onSelect={(employment) => update({ employment })}
              />
            )}
            {question === "income" && (
              <Field
                label="Average monthly income (₹)"
                value={draft.income}
                onChangeText={(income) => update({ income })}
                placeholder="e.g. 50000"
                keyboardType="decimal-pad"
                onSubmitEditing={next}
              />
            )}
            {question === "sources" && (
              <QuestionChoices
                multiple
                options={incomeSources}
                selected={draft.sources}
                onSelect={(v) => toggle("sources", v)}
              />
            )}
            {question === "salaryDay" && (
              <Field
                label="Salary credit day (1–31)"
                value={draft.salaryDay}
                onChangeText={(salaryDay) => update({ salaryDay })}
                keyboardType="number-pad"
                onSubmitEditing={next}
              />
            )}
            {question === "banks" && (
              <>
                <Field
                  label="Search banks"
                  placeholder="e.g. HDFC or State Bank"
                  value={search}
                  onChangeText={setSearch}
                />
                <T muted size={12} style={{ marginBottom: 12 }}>
                  {draft.banks.length
                    ? `Selected: ${draft.banks.join(", ")}`
                    : "No banks selected. You can continue with cash only."}
                </T>
                <QuestionChoices
                  multiple
                  options={bankNames.filter((b) =>
                    b.toLowerCase().includes(search.toLowerCase()),
                  )}
                  selected={draft.banks}
                  onSelect={(v) => toggle("banks", v)}
                />
                {!bankNames.some((b) =>
                  b.toLowerCase().includes(search.toLowerCase()),
                ) && (
                  <T muted>
                    No matching bank. You can add another bank from Money →
                    Accounts after setup.
                  </T>
                )}
              </>
            )}
            {bank !== "" && (
              <Field
                label="Current bank balance (₹)"
                value={draft.balances[bank] ?? ""}
                onChangeText={(value) =>
                  update({ balances: { ...draft.balances, [bank]: value } })
                }
                keyboardType="decimal-pad"
                placeholder="0"
                onSubmitEditing={next}
              />
            )}
            {question === "sip" && (
              <Field
                label="Monthly investment target (₹)"
                value={draft.sip}
                onChangeText={(sip) => update({ sip })}
                keyboardType="decimal-pad"
                onSubmitEditing={next}
              />
            )}
            {question === "risk" && (
              <QuestionChoices
                options={riskAnswers.map((a) => a.label)}
                selected={[draft.riskAnswer]}
                onSelect={(riskAnswer) => update({ riskAnswer })}
              />
            )}
            <ErrorText message={error} />
            <Button
              style={{ marginTop: 24 }}
              title={
                index === questions.length - 1
                  ? "Open my dashboard"
                  : question === "banks" && !draft.banks.length
                    ? "Continue without a bank"
                    : "Continue"
              }
              onPress={next}
            />
            {index > 0 && (
              <Button
                title="Back"
                variant="secondary"
                style={{ marginTop: 12 }}
                onPress={() => {
                  setQuestion(questions[index - 1]);
                  setError(null);
                }}
              />
            )}
            <Button
              title="Sign out"
              variant="secondary"
              style={{ marginTop: 12 }}
              onPress={() =>
                void auth
                  .signOut()
                  .catch(() =>
                    setError("Could not sign out. Please try again."),
                  )
              }
            />
          </View>
        )}
      </View>
    </Screen>
  );
}
