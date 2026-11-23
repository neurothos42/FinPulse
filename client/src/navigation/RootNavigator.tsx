import {
  NavigationContainer,
  DefaultTheme,
  DarkTheme,
} from "@react-navigation/native";
import { createNativeStackNavigator } from "@react-navigation/native-stack";
import { createBottomTabNavigator } from "@react-navigation/bottom-tabs";
import {
  House,
  Wallet,
  Mic,
  ChartNoAxesCombined,
  UserRound,
} from "lucide-react-native";
import { View } from "react-native";
import { useNav } from "../hooks/useNav";
import { ExpenseScreen } from "../screens/ExpenseScreen";
import { IncomeScreen } from "../screens/IncomeScreen";
import { GoalCreateScreen } from "../screens/GoalCreateScreen";
import { InvestmentSuggestionsScreen } from "../screens/InvestmentSuggestionsScreen";
import { VoiceScreen } from "../screens/VoiceScreen";
import { useTheme } from "../theme/ThemeProvider";
import type { RootStackParams, TabParams } from "./types";
import { HomeScreen } from "../screens/HomeScreen";
import { MoneyScreen } from "../screens/MoneyScreen";
import { GoalsScreen } from "../screens/GoalsScreen";
import { InvestmentsScreen } from "../screens/InvestmentsScreen";
import { MeScreen } from "../screens/MeScreen";
import { TransactionScreen } from "../screens/TransactionScreen";
import { EntityScreen } from "../screens/EntityScreen";
import { GoalDetailScreen } from "../screens/GoalDetailScreen";
import { CalculatorScreen } from "../screens/CalculatorScreen";
import { ToolsScreen } from "../screens/ToolsScreen";
import { ImportScreen } from "../screens/ImportScreen";
import { CaptureScreen } from "../screens/CaptureScreen";
import { ProfileScreen } from "../screens/ProfileScreen";
import { DocumentsScreen } from "../screens/DocumentsScreen";
import { DocumentDetailScreen } from "../screens/DocumentDetailScreen";
const Stack = createNativeStackNavigator<RootStackParams>(),
  Tab = createBottomTabNavigator<TabParams>();
const icons = {
  Home: House,
  Money: Wallet,
  Voice: Mic,
  Investments: ChartNoAxesCombined,
  Me: UserRound,
};
function VoicePlaceholder() {
  return <View />;
}
function Tabs() {
  const c = useTheme(),
    nav = useNav();
  return (
    <Tab.Navigator
      screenOptions={({ route }) => ({
        headerShown: false,
        tabBarActiveTintColor: c.green,
        tabBarInactiveTintColor: c.muted,
        tabBarStyle: {
          backgroundColor: c.nav,
          borderTopColor: c.line,
          minHeight: 66,
          paddingTop: 9,
        },
        tabBarLabelStyle: {
          fontSize: 10,
          fontWeight: "600",
          marginHorizontal: 0,
        },
        tabBarIcon: ({ color, size }) => {
          const Icon = icons[route.name];
          return route.name === "Voice" ? (
            <View
              style={{
                width: 54,
                height: 54,
                borderRadius: 27,
                backgroundColor: "#0f766e",
                alignItems: "center",
                justifyContent: "center",
                marginTop: -22,
                borderWidth: 4,
                borderColor: c.nav,
              }}
            >
              <Mic color="white" size={25} />
            </View>
          ) : (
            <Icon color={color} size={size - 2} />
          );
        },
      })}
    >
      <Tab.Screen name="Home" component={HomeScreen} />
      <Tab.Screen name="Money" component={MoneyScreen} />
      <Tab.Screen
        name="Voice"
        component={VoicePlaceholder}
        options={{
          tabBarLabel: "Voice",
          tabBarAccessibilityLabel: "Add expense by voice",
        }}
        listeners={{
          tabPress: (e) => {
            e.preventDefault();
            nav.navigate("Voice");
          },
        }}
      />
      <Tab.Screen name="Investments" component={InvestmentsScreen} />
      <Tab.Screen name="Me" component={MeScreen} />
    </Tab.Navigator>
  );
}
export function RootNavigator() {
  const c = useTheme(),
    base = c.dark ? DarkTheme : DefaultTheme;
  return (
    <NavigationContainer
      theme={{
        ...base,
        colors: {
          ...base.colors,
          primary: c.green,
          background: c.bg,
          card: c.card,
          text: c.text,
          border: c.line,
        },
      }}
    >
      <Stack.Navigator
        screenOptions={{
          headerStyle: { backgroundColor: c.card },
          headerTintColor: c.text,
          headerShadowVisible: false,
          headerTitleStyle: { fontSize: 16 },
          contentStyle: { backgroundColor: c.bg },
          headerBackTitle: "Back",
        }}
      >
        <Stack.Screen
          name="Tabs"
          component={Tabs}
          options={{ headerShown: false }}
        />
        <Stack.Screen
          name="Expense"
          component={ExpenseScreen}
          options={{ headerShown: false, presentation: "modal" }}
        />
        <Stack.Screen
          name="Income"
          component={IncomeScreen}
          options={{ headerShown: false, presentation: "modal" }}
        />
        <Stack.Screen
          name="GoalCreate"
          component={GoalCreateScreen}
          options={{ headerShown: false, presentation: "modal" }}
        />
        <Stack.Screen
          name="Goals"
          component={GoalsScreen}
          options={{ title: "Your goals" }}
        />
        <Stack.Screen
          name="Voice"
          component={VoiceScreen}
          options={{ title: "Voice entry" }}
        />
        <Stack.Screen
          name="InvestmentSuggestions"
          component={InvestmentSuggestionsScreen}
          options={{ title: "Investment suggestions" }}
        />
        <Stack.Screen
          name="Transaction"
          component={TransactionScreen}
          options={{ title: "Transaction" }}
        />
        <Stack.Screen
          name="Entity"
          component={EntityScreen}
          options={{ title: "Manage" }}
        />
        <Stack.Screen
          name="GoalDetail"
          component={GoalDetailScreen}
          options={{ title: "Goal details" }}
        />
        <Stack.Screen
          name="Calculator"
          component={CalculatorScreen}
          options={{ title: "Calculators" }}
        />
        <Stack.Screen
          name="Tools"
          component={ToolsScreen}
          options={{ title: "Money tools" }}
        />
        <Stack.Screen
          name="Import"
          component={ImportScreen}
          options={{ title: "Import" }}
        />
        <Stack.Screen
          name="Documents"
          component={DocumentsScreen}
          options={{ title: "Statements" }}
        />
        <Stack.Screen
          name="DocumentDetail"
          component={DocumentDetailScreen}
          options={{ title: "Document" }}
        />
        <Stack.Screen
          name="Capture"
          component={CaptureScreen}
          options={{ title: "Capture expense" }}
        />
        <Stack.Screen
          name="Profile"
          component={ProfileScreen}
          options={{ title: "Profile" }}
        />
      </Stack.Navigator>
    </NavigationContainer>
  );
}
