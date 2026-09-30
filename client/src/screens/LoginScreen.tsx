import { useState } from "react";
import {
  Screen,
  T,
  Field,
  Button,
  ErrorText,
  Note,
  Progress,
} from "../components/ui";
import { useAuth } from "../store/AuthProvider";

export function LoginScreen({ onBack }: { onBack: () => void }) {
  const auth = useAuth();
  const [step, setStep] = useState(0);
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  function next() {
    if (!(step === 0 ? email.trim() : password.trim())) {
      setError(
        step === 0
          ? "Enter any email to continue."
          : "Enter any password to continue.",
      );
      return;
    }
    setError(null);
    if (step === 0) {
      setStep(1);
      return;
    }
    setPassword("");
    auth.enterLocal();
  }
  return (
    <Screen tab key={step}>
      <T bold size={13} style={{ letterSpacing: 3, marginVertical: 24 }}>
        FINPULSE
      </T>
      <Progress value={(step + 1) * 50} />
      <T size={32} bold style={{ marginVertical: 24 }}>
        {step === 0 ? "What’s your email?" : "Enter any password."}
      </T>
      <Note>
        Static login for trying the app. Any email and password work. These
        entries are not verified or saved. This device uses one shared local
        preview profile.
      </Note>
      {step === 0 ? (
        <Field
          label="Email"
          value={email}
          onChangeText={setEmail}
          keyboardType="email-address"
          autoCapitalize="none"
          autoCorrect={false}
          onSubmitEditing={next}
        />
      ) : (
        <Field
          label="Password"
          value={password}
          onChangeText={setPassword}
          secureTextEntry
          autoCapitalize="none"
          autoCorrect={false}
          onSubmitEditing={next}
        />
      )}
      <ErrorText message={error} />
      <Button
        title={step === 0 ? "Continue" : "Continue to setup"}
        onPress={next}
      />
      <Button
        title={step === 0 ? "Back to welcome" : "Back"}
        variant="secondary"
        style={{ marginTop: 12 }}
        onPress={() => {
          setError(null);
          if (step) setStep(0);
          else onBack();
        }}
      />
    </Screen>
  );
}
