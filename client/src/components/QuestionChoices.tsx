import { Pressable, View } from "react-native";
import { T } from "./ui";
import { useTheme } from "../theme/ThemeProvider";
export function QuestionChoices({
  options,
  selected,
  onSelect,
  multiple = false,
}: {
  options: readonly string[];
  selected: readonly string[];
  onSelect: (value: string) => void;
  multiple?: boolean;
}) {
  const c = useTheme();
  return (
    <View style={{ gap: 10 }}>
      {options.map((option) => {
        const checked = selected.includes(option);
        return (
          <Pressable
            key={option}
            accessibilityRole={multiple ? "checkbox" : "radio"}
            accessibilityLabel={option}
            accessibilityState={{ checked }}
            aria-checked={checked}
            onPress={() => onSelect(option)}
            style={({ pressed }) => ({
              borderRadius: 16,
              borderWidth: 1.5,
              borderColor: checked ? c.green : c.line,
              backgroundColor: checked ? c.greenBg : c.card,
              padding: 17,
              minHeight: 54,
              opacity: pressed ? 0.7 : 1,
              flexDirection: "row",
              justifyContent: "space-between",
            })}
          >
            <T bold={checked} style={{ flex: 1 }}>
              {option}
            </T>
            <T style={{ color: c.green }}>
              {checked ? "✓" : multiple ? "+" : "○"}
            </T>
          </Pressable>
        );
      })}
    </View>
  );
}
