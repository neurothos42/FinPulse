import type { EntityKey, Transaction } from "../types/models";
export type RootStackParams = {
  Tabs: undefined;
  Expense: undefined;
  Income: undefined;
  GoalCreate: { id?: string } | undefined;
  Goals: undefined;
  Voice: undefined;
  InvestmentSuggestions: undefined;
  Transaction:
    | { id?: string; type?: Transaction["type"]; draft?: Partial<Transaction> }
    | undefined;
  Entity: { kind: EntityKey; id?: string };
  GoalDetail: { id: string };
  Import: undefined;
  Documents: undefined;
  DocumentDetail: { id: string };
  Capture: { mode: "voice" | "receipt" };
  Calculator: { kind?: "SIP" | "EMI" | "Compound" | "FD" } | undefined;
  Tools: {
    tool:
      | "health"
      | "lessons"
      | "risk"
      | "ask"
      | "reports"
      | "notifications"
      | "festivals"
      | "splits"
      | "allocation"
      | "security"
      | "privacy";
  };
  Profile: undefined;
};
export type TabParams = {
  Home: undefined;
  Money: undefined;
  Voice: undefined;
  Investments: undefined;
  Me: undefined;
};
