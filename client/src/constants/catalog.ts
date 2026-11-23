import type { Risk } from "../types/models";
export const categoryIcons: Record<string, string> = {
  Food: "🍔",
  Groceries: "🛒",
  Shopping: "🛍️",
  Transport: "🚕",
  Entertainment: "🎬",
  Household: "🏠",
  Bills: "💡",
  Personal: "👤",
  Medical: "💊",
  Travel: "✈️",
  Education: "📚",
  Other: "💳",
  Income: "💰",
  Investment: "📈",
};
export const chartColors = [
  "#12b76a",
  "#3b82f6",
  "#7c3aed",
  "#f79009",
  "#06b6d4",
  "#f04438",
  "#ec4899",
];
export const riskProfiles: Record<
  Risk,
  {
    rate: number;
    allocation: { Equity: number; Debt: number; Gold: number };
    description: string;
  }
> = {
  Conservative: {
    rate: 8,
    allocation: { Equity: 25, Debt: 60, Gold: 15 },
    description:
      "A stability-focused planning mix, with a larger debt allocation.",
  },
  Moderate: {
    rate: 11,
    allocation: { Equity: 50, Debt: 40, Gold: 10 },
    description: "A balance between long-term growth and stability.",
  },
  Aggressive: {
    rate: 14,
    allocation: { Equity: 75, Debt: 15, Gold: 10 },
    description: "A growth-focused mix that can experience substantial losses.",
  },
};
export const lessons = [
  {
    icon: "📈",
    title: "How a SIP works",
    body: "A systematic investment plan contributes a fixed amount regularly. The number of units purchased changes with the price. It encourages consistency but does not guarantee a profit.",
  },
  {
    icon: "💳",
    title: "The minimum due trap",
    body: "A minimum credit-card payment leaves the rest of the balance unpaid. Interest may continue to accrue under your card terms. Check the statement and understand the full repayment cost.",
  },
  {
    icon: "🛟",
    title: "Your emergency buffer",
    body: "Money reserved for unexpected essential expenses should be accessible. Choose your buffer using your monthly needs, job stability and responsibilities.",
  },
  {
    icon: "🌱",
    title: "Give compounding time",
    body: "Compounding adds returns to the original amount so future returns apply to a larger balance. A constant return is a calculator assumption; actual investment returns fluctuate.",
  },
  {
    icon: "🧩",
    title: "Understand your allocation",
    body: "Different asset classes respond differently to economic conditions. Diversification spreads exposure but does not eliminate investment risk.",
  },
];
export const investmentLibrary = [
  {
    name: "Mutual Funds",
    risk: "Varies",
    horizon: "Depends on fund",
    body: "A pooled portfolio managed under a stated strategy. Compare the asset mix, fees, liquidity and scheme documents. Equity and debt funds have different risks.",
  },
  {
    name: "Stocks & ETFs",
    risk: "Market risk",
    horizon: "Usually long term",
    body: "Stocks represent company ownership. ETFs hold a basket and trade on an exchange. Prices can fall and diversification varies by product.",
  },
  {
    name: "FD / RD",
    risk: "Issuer risk",
    horizon: "Chosen deposit term",
    body: "Bank deposits can have agreed interest and maturity terms. Check current rates, withdrawal penalties and applicable deposit protection with the provider.",
  },
  {
    name: "Gold",
    risk: "Price risk",
    horizon: "Depends on objective",
    body: "Gold prices fluctuate. Physical gold, funds and other instruments have different costs, liquidity and custody arrangements.",
  },
  {
    name: "PPF / EPF / NPS",
    risk: "Product-specific",
    horizon: "Generally long term",
    body: "These savings or retirement arrangements have distinct eligibility, lock-in, contribution and withdrawal rules. Verify current official terms before investing.",
  },
  {
    name: "Bonds",
    risk: "Credit & interest-rate risk",
    horizon: "Bond-specific",
    body: "A bond is a loan to an issuer. Market value, repayment risk and ease of selling vary. A fixed coupon does not make every bond risk-free.",
  },
];
