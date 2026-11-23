import type { ReactNode } from "react";
import { useState } from "react";
import {
  View,
  Text,
  Pressable,
  TextInput,
  ScrollView,
  StyleSheet,
  Modal,
  KeyboardAvoidingView,
  Platform,
  type TextProps,
  type StyleProp,
  type ViewStyle,
  type TextInputProps,
} from "react-native";
import { SafeAreaView } from "react-native-safe-area-context";
import { ChevronDown, X } from "lucide-react-native";
import { useTheme } from "../theme/ThemeProvider";
import { clamp } from "../utils/format";

export function T({
  muted = false,
  size = 14,
  bold = false,
  style,
  ...props
}: TextProps & { muted?: boolean; size?: number; bold?: boolean }) {
  const c = useTheme();
  return (
    <Text
      {...props}
      style={[
        {
          color: muted ? c.muted : c.text,
          fontSize: size,
          lineHeight: size * 1.45,
          fontWeight: bold ? "700" : "400",
        },
        style,
      ]}
    />
  );
}
export function Card({
  children,
  style,
  onPress,
  label,
}: {
  children: ReactNode;
  style?: StyleProp<ViewStyle>;
  onPress?: () => void;
  label?: string;
}) {
  const c = useTheme(),
    s = [styles.card, { backgroundColor: c.card, borderColor: c.line }, style];
  return onPress ? (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      onPress={onPress}
      style={({ pressed }) => [s, { opacity: pressed ? 0.8 : 1 }]}
    >
      {children}
    </Pressable>
  ) : (
    <View style={s}>{children}</View>
  );
}
export function Row({
  children,
  style,
}: {
  children: ReactNode;
  style?: StyleProp<ViewStyle>;
}) {
  return <View style={[styles.row, style]}>{children}</View>;
}
export function Section({
  title,
  action,
  onPress,
}: {
  title: string;
  action?: string;
  onPress?: () => void;
}) {
  const c = useTheme();
  return (
    <Row
      style={{
        justifyContent: "space-between",
        marginTop: 22,
        marginBottom: 12,
      }}
    >
      <T size={17} bold style={{ flex: 1 }}>
        {title}
      </T>
      {action ? (
        <Pressable
          accessibilityRole="button"
          onPress={onPress}
          style={{ minHeight: 44, justifyContent: "center", paddingLeft: 12 }}
        >
          <T size={12} bold style={{ color: c.green }}>
            {action}
          </T>
        </Pressable>
      ) : null}
    </Row>
  );
}
export function Button({
  title,
  onPress,
  variant = "primary",
  disabled = false,
  style,
  icon,
}: {
  title: string;
  onPress: () => void;
  variant?: "primary" | "secondary" | "danger";
  disabled?: boolean;
  style?: StyleProp<ViewStyle>;
  icon?: ReactNode;
}) {
  const c = useTheme(),
    bg =
      variant === "primary"
        ? "#0f766e"
        : variant === "danger"
          ? c.redBg
          : c.greenBg;
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityState={{ disabled }}
      disabled={disabled}
      onPress={onPress}
      style={({ pressed }) => [
        {
          backgroundColor: bg,
          borderRadius: 14,
          minHeight: 48,
          padding: 13,
          alignItems: "center",
          justifyContent: "center",
          flexDirection: "row",
          gap: 8,
          opacity: disabled ? 0.45 : pressed ? 0.75 : 1,
        },
        style,
      ]}
    >
      {icon}
      <T
        bold
        style={{
          color:
            variant === "primary"
              ? "#ffffff"
              : variant === "danger"
                ? c.red
                : c.green,
        }}
      >
        {title}
      </T>
    </Pressable>
  );
}
export function IconButton({
  children,
  label,
  onPress,
}: {
  children: ReactNode;
  label: string;
  onPress: () => void;
}) {
  const c = useTheme();
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      onPress={onPress}
      style={{
        width: 44,
        height: 44,
        alignItems: "center",
        justifyContent: "center",
        borderRadius: 22,
        backgroundColor: c.card,
        borderWidth: 1,
        borderColor: c.line,
      }}
    >
      {children}
    </Pressable>
  );
}
export function Chip({
  label,
  active = false,
  onPress,
}: {
  label: string;
  active?: boolean;
  onPress: () => void;
}) {
  const c = useTheme();
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityState={{ selected: active }}
      onPress={onPress}
      style={{
        minHeight: 40,
        paddingHorizontal: 15,
        paddingVertical: 10,
        borderRadius: 12,
        backgroundColor: active ? c.greenBg : c.card,
        borderWidth: 1,
        borderColor: active ? c.greenBright : c.line,
      }}
    >
      <T size={12} bold={active} style={{ color: active ? c.green : c.muted }}>
        {label}
      </T>
    </Pressable>
  );
}
export function Chips<TValue extends string>({
  options,
  value,
  onChange,
}: {
  options: readonly TValue[];
  value: TValue;
  onChange: (value: TValue) => void;
}) {
  return (
    <View
      style={{
        flexDirection: "row",
        flexWrap: "wrap",
        gap: 8,
        marginBottom: 14,
      }}
    >
      {options.map((o) => (
        <Chip
          key={o}
          label={o}
          active={o === value}
          onPress={() => onChange(o)}
        />
      ))}
    </View>
  );
}
export function Progress({ value, color }: { value: number; color?: string }) {
  const c = useTheme();
  return (
    <View
      accessible
      accessibilityRole="progressbar"
      accessibilityValue={{ min: 0, max: 100, now: Math.round(clamp(value)) }}
      style={{
        height: 7,
        borderRadius: 8,
        backgroundColor: c.line,
        overflow: "hidden",
      }}
    >
      <View
        style={{
          height: "100%",
          width: `${clamp(value)}%`,
          backgroundColor: color ?? c.greenBright,
          borderRadius: 8,
        }}
      />
    </View>
  );
}
export function Field({
  label,
  error,
  ...props
}: TextInputProps & { label: string; error?: string }) {
  const c = useTheme();
  return (
    <View style={{ marginBottom: 16 }}>
      <T size={11} bold muted style={{ letterSpacing: 0.8, marginBottom: 7 }}>
        {label.toUpperCase()}
      </T>
      <TextInput
        accessibilityLabel={label}
        placeholderTextColor={c.muted}
        {...props}
        style={[
          {
            backgroundColor: c.input,
            borderWidth: 1,
            borderColor: error ? c.red : c.line,
            borderRadius: 12,
            paddingHorizontal: 14,
            paddingVertical: 12,
            color: c.text,
            fontSize: 15,
            minHeight: 48,
            textAlignVertical: props.multiline ? "top" : "center",
          },
          props.style,
        ]}
      />
      {error ? (
        <T size={12} style={{ color: c.red }}>
          {error}
        </T>
      ) : null}
    </View>
  );
}
export function Select({
  label,
  value,
  options,
  onChange,
}: {
  label: string;
  value: string;
  options: readonly (string | { label: string; value: string })[];
  onChange: (v: string) => void;
}) {
  const [open, setOpen] = useState(false),
    c = useTheme();
  const normalized = options.map((o) =>
    typeof o === "string" ? { label: o, value: o } : o,
  );
  return (
    <View style={{ marginBottom: 16 }}>
      <T size={11} bold muted style={{ letterSpacing: 0.8, marginBottom: 7 }}>
        {label.toUpperCase()}
      </T>
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={`${label}: ${normalized.find((o) => o.value === value)?.label ?? "Choose"}`}
        onPress={() => setOpen(true)}
        style={{
          minHeight: 48,
          padding: 13,
          borderRadius: 12,
          borderWidth: 1,
          borderColor: c.line,
          backgroundColor: c.input,
          flexDirection: "row",
          justifyContent: "space-between",
        }}
      >
        <T style={{ flex: 1 }}>
          {normalized.find((o) => o.value === value)?.label ?? "Choose"}
        </T>
        <ChevronDown size={18} color={c.muted} />
      </Pressable>
      <Modal
        visible={open}
        transparent
        animationType="slide"
        onRequestClose={() => setOpen(false)}
      >
        <View
          style={{
            flex: 1,
            justifyContent: "flex-end",
            backgroundColor: "#0008",
          }}
        >
          <Pressable
            accessibilityLabel="Close selection"
            onPress={() => setOpen(false)}
            style={{ flex: 1 }}
          />
          <SafeAreaView
            edges={["bottom"]}
            style={{
              backgroundColor: c.card,
              borderTopLeftRadius: 24,
              borderTopRightRadius: 24,
              maxHeight: "70%",
              padding: 20,
            }}
          >
            <Row style={{ justifyContent: "space-between", marginBottom: 12 }}>
              <T size={20} bold>
                {label}
              </T>
              <IconButton label="Close" onPress={() => setOpen(false)}>
                <X size={20} color={c.text} />
              </IconButton>
            </Row>
            <ScrollView>
              {normalized.map((o) => (
                <Pressable
                  key={o.value}
                  accessibilityRole="button"
                  accessibilityState={{ selected: o.value === value }}
                  onPress={() => {
                    onChange(o.value);
                    setOpen(false);
                  }}
                  style={{
                    padding: 15,
                    borderBottomWidth: 1,
                    borderColor: c.line,
                  }}
                >
                  <T
                    bold={o.value === value}
                    style={{ color: o.value === value ? c.green : c.text }}
                  >
                    {o.label}
                  </T>
                </Pressable>
              ))}
            </ScrollView>
          </SafeAreaView>
        </View>
      </Modal>
    </View>
  );
}
export function ErrorText({ message }: { message: string | null }) {
  const c = useTheme();
  return message ? (
    <View
      accessibilityRole="alert"
      style={{
        padding: 12,
        backgroundColor: c.redBg,
        borderRadius: 10,
        marginBottom: 14,
      }}
    >
      <T size={13} style={{ color: c.red }}>
        {message}
      </T>
    </View>
  ) : null;
}
export function Note({ children }: { children: ReactNode }) {
  const c = useTheme();
  return (
    <View
      style={{
        padding: 13,
        borderRadius: 12,
        backgroundColor: c.greenBg,
        marginVertical: 10,
      }}
    >
      <T size={12} style={{ color: c.green }}>
        {children}
      </T>
    </View>
  );
}
export function Empty({ title, body }: { title: string; body: string }) {
  return (
    <View style={{ paddingVertical: 24, alignItems: "center", gap: 5 }}>
      <T bold>{title}</T>
      <T size={13} muted style={{ textAlign: "center" }}>
        {body}
      </T>
    </View>
  );
}
export function Screen({
  children,
  title,
  subtitle,
  right,
  tab = false,
  sheet = false,
}: {
  children: ReactNode;
  title?: string;
  subtitle?: string;
  right?: ReactNode;
  tab?: boolean;
  sheet?: boolean;
}) {
  const c = useTheme();
  return (
    <SafeAreaView
      edges={sheet ? ["top", "bottom"] : tab ? ["top"] : ["bottom"]}
      style={{ flex: 1, backgroundColor: sheet ? c.card : c.bg }}
    >
      <KeyboardAvoidingView
        style={{ flex: 1 }}
        behavior={Platform.OS === "ios" ? "padding" : undefined}
        keyboardVerticalOffset={95}
      >
        <ScrollView
          keyboardShouldPersistTaps="handled"
          contentContainerStyle={{
            paddingHorizontal: 18,
            paddingTop: title ? 16 : 20,
            paddingBottom: 32,
            width: "100%",
            maxWidth: 680,
            alignSelf: "center",
          }}
        >
          {title ? (
            <Row style={{ justifyContent: "space-between", marginBottom: 23 }}>
              <View style={{ flex: 1 }}>
                {subtitle ? (
                  <T size={12} muted>
                    {subtitle}
                  </T>
                ) : null}
                <T size={25} bold style={{ letterSpacing: -0.6 }}>
                  {title}
                </T>
              </View>
              {right}
            </Row>
          ) : null}
          {children}
        </ScrollView>
      </KeyboardAvoidingView>
    </SafeAreaView>
  );
}
const styles = StyleSheet.create({
  row: { flexDirection: "row", alignItems: "center", gap: 12 },
  card: { borderWidth: 1, borderRadius: 20, padding: 17, marginBottom: 12 },
});
