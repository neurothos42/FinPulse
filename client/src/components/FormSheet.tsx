import type { ReactNode } from "react";
import { View } from "react-native";
import { X } from "lucide-react-native";
import { Screen, Row, T, IconButton } from "./ui";
import { useTheme } from "../theme/ThemeProvider";

export function FormSheet({
  title,
  onClose,
  children,
}: {
  title: string;
  onClose: () => void;
  children: ReactNode;
}) {
  const c = useTheme();
  return (
    <View
      style={{
        flex: 1,
        backgroundColor: c.card,
        borderTopLeftRadius: 28,
        borderTopRightRadius: 28,
        overflow: "hidden",
      }}
    >
      <Screen sheet>
        <Row style={{ justifyContent: "space-between", marginBottom: 24 }}>
          <T size={27} bold style={{ flex: 1 }}>
            {title}
          </T>
          <IconButton label="Close form" onPress={onClose}>
            <X color={c.text} size={21} />
          </IconButton>
        </Row>
        {children}
      </Screen>
    </View>
  );
}
