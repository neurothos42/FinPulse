import { Pressable, View } from "react-native";
import { T, Row } from "./ui";
import { useTheme } from "../theme/ThemeProvider";
import { categoryIcons } from "../constants/catalog";
import { inr } from "../utils/format";
import { dateLabel } from "../utils/date";
import type { Transaction } from "../types/models";
export function TransactionRow({
  transaction: t,
  onPress,
}: {
  transaction: Transaction;
  onPress: () => void;
}) {
  const c = useTheme();
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={`${t.merchant}, ${t.type}, ${inr(t.amount)}, ${dateLabel(t.date)}`}
      onPress={onPress}
      style={{ paddingVertical: 13, borderBottomWidth: 1, borderColor: c.line }}
    >
      <Row>
        <View
          style={{
            height: 42,
            width: 42,
            borderRadius: 13,
            backgroundColor: c.input,
            alignItems: "center",
            justifyContent: "center",
          }}
        >
          <T size={21}>{categoryIcons[t.category]}</T>
        </View>
        <View style={{ flex: 1 }}>
          <T bold>{t.merchant}</T>
          <T muted size={11}>
            {t.category} · {t.method}
          </T>
        </View>
        <View style={{ alignItems: "flex-end" }}>
          <T bold style={{ color: t.type === "income" ? c.green : c.text }}>
            {t.type === "income" ? "+" : "−"}
            {inr(t.amount)}
          </T>
          <T size={10} muted>
            {dateLabel(t.date)}
          </T>
        </View>
      </Row>
    </Pressable>
  );
}
