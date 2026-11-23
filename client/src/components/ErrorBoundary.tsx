import { Component, type ReactNode } from "react";
import { View, Text, Pressable } from "react-native";
export class ErrorBoundary extends Component<
  { children: ReactNode },
  { failed: boolean }
> {
  state = { failed: false };
  static getDerivedStateFromError() {
    return { failed: true };
  }
  render() {
    if (this.state.failed)
      return (
        <View
          style={{
            flex: 1,
            justifyContent: "center",
            padding: 28,
            backgroundColor: "#f5f7fb",
          }}
        >
          <Text style={{ fontSize: 25, fontWeight: "700", color: "#101828" }}>
            Something went wrong.
          </Text>
          <Text style={{ marginVertical: 16, color: "#667085" }}>
            Your saved records have not been deleted. Try opening the app again.
          </Text>
          <Pressable
            accessibilityRole="button"
            onPress={() => this.setState({ failed: false })}
            style={{
              padding: 16,
              borderRadius: 12,
              backgroundColor: "#12b76a",
            }}
          >
            <Text style={{ textAlign: "center", fontWeight: "700" }}>
              Try again
            </Text>
          </Pressable>
        </View>
      );
    return this.props.children;
  }
}
