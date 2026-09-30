import { useRef, useState } from "react";
import { View } from "react-native";
import {
  Screen,
  T,
  Card,
  Field,
  Button,
  ErrorText,
  Note,
  Progress,
} from "../components/ui";
import { useAuth } from "../store/AuthProvider";
import { signIn, register } from "../services/auth";

export function LoginScreen({ onBack }: { onBack: () => void }) {
  const auth = useAuth();
  const [creating, setCreating] = useState(false),
    [step, setStep] = useState(0);
  const [email, setEmail] = useState(""),
    [password, setPassword] = useState(""),
    [name, setName] = useState("");
  const [busy, setBusy] = useState(false),
    [error, setError] = useState<string | null>(null),
    [registered, setRegistered] = useState(false),
    [showPassword, setShowPassword] = useState(false);
  const submitting = useRef(false);
  const fields = creating
    ? ["name", "email", "password"]
    : ["email", "password"];
  const field = fields[step];
  async function next() {
    if (submitting.current) return;
    setError(null);
    if (field === "name" && (name.trim().length < 2 || name.trim().length > 50))
      return setError("Enter your name using 2–50 characters.");
    if (field === "email" && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim()))
      return setError("Enter a valid email address.");
    if (
      field === "password" &&
      (!password ||
        (creating && (password.length < 8 || password.length > 100)))
    )
      return setError(
        creating
          ? "Use 8–100 characters for your password."
          : "Enter your password.",
      );
    if (step < fields.length - 1) {
      setStep((s) => s + 1);
      return;
    }
    submitting.current = true;
    setBusy(true);
    try {
      if (creating) {
        await register(name, email, password);
        setCreating(false);
        setRegistered(true);
        setStep(1);
      } else {
        await auth.accept(await signIn(email, password));
      }
      setPassword("");
      setShowPassword(false);
    } catch (e) {
      setError(
        e instanceof Error ? e.message : "Could not sign in. Please try again.",
      );
    } finally {
      submitting.current = false;
      setBusy(false);
    }
  }
  function back() {
    setError(null);
    if (step > 0) setStep((s) => s - 1);
    else onBack();
  }
  return (
    <Screen tab>
      <View style={{ paddingTop: 30 }}>
        <T bold size={12} style={{ letterSpacing: 3, marginBottom: 28 }}>
          FINPULSE
        </T>
        <T muted size={12} style={{ marginBottom: 12 }}>
          {creating ? "CREATE YOUR ACCOUNT" : "WELCOME BACK"} · {step + 1} /{" "}
          {fields.length}
        </T>
        <Progress value={((step + 1) / fields.length) * 100} />
        <T size={32} bold accessibilityRole="header" style={{ marginTop: 24 }}>
          {field === "name"
            ? "What should we call you?"
            : field === "email"
              ? "What’s your email?"
              : creating
                ? "Create your password."
                : "Enter your password."}
        </T>
        <T muted style={{ marginTop: 10, marginBottom: 26 }}>
          {field === "password"
            ? creating
              ? "Use at least 8 characters to protect your account."
              : email.trim()
            : "One question at a time. Your money journey starts here."}
        </T>
        {registered && (
          <Note>
            Account created. Sign in with your new password, then personalize
            your profile.
          </Note>
        )}
        <Card key={field}>
          {field === "name" && (
            <Field
              label="Your name"
              value={name}
              onChangeText={setName}
              autoComplete="name"
              maxLength={50}
              editable={!busy}
              onSubmitEditing={() => void next()}
            />
          )}
          {field === "email" && (
            <Field
              label="Email"
              value={email}
              onChangeText={setEmail}
              placeholder="you@example.com"
              keyboardType="email-address"
              autoCapitalize="none"
              autoCorrect={false}
              autoComplete="email"
              editable={!busy}
              onSubmitEditing={() => void next()}
            />
          )}
          {field === "password" && (
            <>
              <Field
                label="Password"
                value={password}
                onChangeText={setPassword}
                secureTextEntry={!showPassword}
                autoCapitalize="none"
                autoCorrect={false}
                autoComplete={creating ? "new-password" : "current-password"}
                editable={!busy}
                onSubmitEditing={() => void next()}
              />
              <Button
                title={showPassword ? "Hide password" : "Show password"}
                variant="secondary"
                disabled={busy}
                onPress={() => setShowPassword((v) => !v)}
              />
            </>
          )}
          <ErrorText message={error ?? auth.error} />
          <Button
            style={{ marginTop: 12 }}
            title={
              busy
                ? "Please wait…"
                : field !== "password"
                  ? "Continue"
                  : creating
                    ? "Create account"
                    : "Log in"
            }
            disabled={busy}
            onPress={() => void next()}
          />
        </Card>
        <Button
          title={step ? "Back" : "Back to welcome"}
          disabled={busy}
          variant="secondary"
          onPress={back}
        />
        <Button
          title={
            creating
              ? "Already have an account? Log in"
              : "New here? Create account"
          }
          disabled={busy}
          variant="secondary"
          style={{ marginTop: 12 }}
          onPress={() => {
            setCreating((v) => !v);
            setStep(0);
            setPassword("");
            setShowPassword(false);
            setError(null);
            setRegistered(false);
          }}
        />
        <Button
          title="Explore the demo"
          disabled={busy}
          variant="secondary"
          style={{ marginTop: 12 }}
          onPress={auth.exploreDemo}
        />
      </View>
    </Screen>
  );
}
