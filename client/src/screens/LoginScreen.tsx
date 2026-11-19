import { useState } from "react";
import {
  Screen,
  T,
  Field,
  Button,
  ErrorText,
  Note,
} from "../components/ui";
import { useAuth } from "../store/AuthProvider";
import { register, signIn } from "../services/auth";

export function LoginScreen({ onBack }: { onBack: () => void }) {
  const auth = useAuth();
  const [mode, setMode] = useState<"login" | "register">("login");
  const [name, setName] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  async function submit() {
    setError(null);
    const normalizedEmail = email.trim();
    if (mode === "register" && name.trim().length < 2) {
      setError("Enter your name (at least 2 characters).");
      return;
    }
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(normalizedEmail)) {
      setError("Enter a valid email address.");
      return;
    }
    if (password.length < (mode === "register" ? 8 : 1)) {
      setError(
        mode === "register"
          ? "Password must be at least 8 characters."
          : "Enter your password.",
      );
      return;
    }

    setLoading(true);
    try {
      if (mode === "register") {
        await register(name, normalizedEmail, password);
      }
      const session = await signIn(normalizedEmail, password);
      await auth.accept(session);
    } catch (e) {
      setError(
        e instanceof Error
          ? e.message
          : "Something went wrong. Please try again.",
      );
    } finally {
      setLoading(false);
    }
  }

  return (
    <Screen tab>
      <T bold size={13} style={{ letterSpacing: 3, marginVertical: 24 }}>
        FINPULSE
      </T>
      <T size={32} bold style={{ marginBottom: 8 }}>
        {mode === "login" ? "Welcome back." : "Create your account."}
      </T>
      <T muted style={{ marginBottom: 16 }}>
        {mode === "login"
          ? "Sign in to continue to your financial dashboard."
          : "Your account keeps your FinPulse profile and records separate."}
      </T>
      <Note>
        {mode === "login"
          ? "Use the email and password associated with your FinPulse account."
          : "Use a valid email and a password with at least 8 characters."}
      </Note>
      {mode === "register" ? (
        <Field
          label="Name"
          value={name}
          onChangeText={setName}
          autoCapitalize="words"
          autoCorrect={false}
          autoComplete="name"
          maxLength={50}
          editable={!loading}
          returnKeyType="next"
        />
      ) : null}
      <Field
        label="Email"
        value={email}
        onChangeText={setEmail}
        keyboardType="email-address"
        autoCapitalize="none"
        autoCorrect={false}
        autoComplete="email"
        editable={!loading}
        returnKeyType="next"
      />
      <Field
        label="Password"
        value={password}
        onChangeText={setPassword}
        secureTextEntry
        autoCapitalize="none"
        autoCorrect={false}
        autoComplete={mode === "login" ? "current-password" : "new-password"}
        editable={!loading}
        onSubmitEditing={() => void submit()}
        returnKeyType="go"
      />
      <ErrorText message={error} />
      <Button
        title={
          loading
            ? mode === "login"
              ? "Signing in…"
              : "Creating account…"
            : mode === "login"
              ? "Sign in"
              : "Create account"
        }
        onPress={() => void submit()}
        disabled={loading}
      />
      <Button
        title={
          mode === "login"
            ? "New to FinPulse? Create an account"
            : "Already have an account? Sign in"
        }
        variant="secondary"
        style={{ marginTop: 12 }}
        disabled={loading}
        onPress={() => {
          setMode(mode === "login" ? "register" : "login");
          setError(null);
        }}
      />
      <Button
        title="Back to welcome"
        variant="secondary"
        style={{ marginTop: 12 }}
        disabled={loading}
        onPress={onBack}
      />
    </Screen>
  );
}
