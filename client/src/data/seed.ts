import type { AppState, Transaction } from "../types/models";
import { isoDate, shiftMonths } from "../utils/date";

export function emptyState(): AppState {
  return {
    version: 1,
    onboarded: false,
    demo: false,
    profile: {
      name: "You",
      monthlyIncome: 0,
      salaryDay: 1,
      risk: "Moderate",
      darkMode: false,
      sipTarget: 0,
      allocation: {
        Essentials: 50,
        Savings: 20,
        Investments: 20,
        Lifestyle: 10,
      },
    },
    accounts: [
      { id: "cash", name: "Cash", type: "cash", openingBalance: 0, limit: 0 },
    ],
    transactions: [],
    budgets: [],
    goals: [],
    holdings: [],
    bills: [],
    loans: [],
    splits: [],
    festivals: [],
    readNotifications: [],
  };
}
export function demoState(): AppState {
  const s = emptyState(),
    today = isoDate(),
    m = today.slice(0, 7),
    day = (d: number) =>
      `${m}-${String(Math.min(Number(today.slice(8)), d)).padStart(2, "0")}`;
  s.demo = true;
  s.onboarded = true;
  s.profile = {
    ...s.profile,
    name: "Arjun Sharma",
    monthlyIncome: 50000,
    sipTarget: 7000,
    allocation: { Essentials: 44, Savings: 20, Investments: 14, Lifestyle: 22 },
  };
  const raw: [
    string,
    number,
    Transaction["category"],
    Transaction["method"],
    number,
  ][] = [
    ["Swiggy", 420, "Food", "UPI", 16],
    ["Zepto", 780, "Groceries", "UPI", 16],
    ["Amazon", 1899, "Shopping", "Credit Card", 15],
    ["Café Coffee Day", 340, "Food", "UPI", 15],
    ["Uber", 310, "Transport", "UPI", 14],
    ["PVR Cinemas", 1562, "Entertainment", "Credit Card", 13],
    ["Zomato", 1150, "Food", "UPI", 12],
    ["Myntra", 1301, "Shopping", "Credit Card", 11],
    ["Barbeque Nation", 1686, "Food", "Credit Card", 10],
    ["JioFiber", 999, "Bills", "UPI", 10],
    ["Netflix", 649, "Entertainment", "Credit Card", 8],
    ["Airtel", 799, "Bills", "UPI", 7],
    ["Swiggy", 680, "Food", "UPI", 7],
    ["Haldiram’s", 520, "Food", "Cash", 6],
    ["BigBasket", 1400, "Groceries", "Debit Card", 5],
    ["House rent", 15000, "Household", "Bank Transfer", 1],
    ["Petrol", 2100, "Transport", "UPI", 3],
    ["Pharmacy", 640, "Medical", "Cash", 4],
  ];
  s.transactions = raw.map(([merchant, amount, category, method, d], i) => ({
    id: `demo-t${i}`,
    merchant,
    amount,
    category,
    method,
    date: day(d),
    type: "expense",
    accountId:
      method === "Credit Card" ? "card" : method === "Cash" ? "cash" : "hdfc",
    notes: "",
    source: "manual",
  }));
  s.transactions.push(
    {
      id: "salary",
      merchant: "Monthly salary",
      amount: 50000,
      type: "income",
      category: "Income",
      date: day(1),
      method: "Bank Transfer",
      accountId: "hdfc",
      notes: "",
      source: "manual",
    },
    {
      id: "sip",
      merchant: "Mutual Fund SIP",
      amount: 5000,
      type: "investment",
      category: "Investment",
      date: day(12),
      method: "Bank Transfer",
      accountId: "hdfc",
      notes: "",
      source: "manual",
    },
  );
  s.transactions.push(
    ...s.transactions.slice().map((t) => ({
      ...t,
      id: `prev-${t.id}`,
      date: shiftMonths(t.date, -1),
    })),
  );
  s.accounts = [
    {
      id: "hdfc",
      name: "HDFC Savings",
      type: "bank",
      openingBalance: 0,
      limit: 0,
    },
    {
      id: "sbi",
      name: "SBI Savings",
      type: "bank",
      openingBalance: 32600,
      limit: 0,
    },
    { id: "cash", name: "Cash", type: "cash", openingBalance: 0, limit: 0 },
    {
      id: "card",
      name: "ICICI Coral",
      type: "credit",
      openingBalance: 0,
      limit: 25500,
    },
  ];
  const desired: Record<string, number> = {
    hdfc: 84500,
    sbi: 32600,
    cash: 7400,
    card: -18400,
  };
  s.accounts = s.accounts.map((a) => ({
    ...a,
    openingBalance:
      desired[a.id] -
      s.transactions
        .filter((t) => t.accountId === a.id)
        .reduce(
          (sum, t) => sum + (t.type === "income" ? t.amount : -t.amount),
          0,
        ),
  }));
  s.budgets = (
    [
      ["Food", 8000],
      ["Shopping", 5000],
      ["Transport", 4000],
      ["Entertainment", 2500],
      ["Groceries", 4000],
      ["Bills", 4000],
      ["Household", 16000],
    ] as const
  ).map(([category, limit]) => ({ id: category, category, limit }));
  s.goals = [
    {
      id: "emergency",
      name: "Emergency Fund",
      icon: "🛟",
      target: 180000,
      current: 60000,
      targetDate: shiftMonths(today, 6),
      emergency: true,
    },
    {
      id: "car",
      name: "New Car",
      icon: "🚗",
      target: 500000,
      current: 320000,
      targetDate: shiftMonths(today, 33),
      emergency: false,
    },
    {
      id: "travel",
      name: "Europe Vacation",
      icon: "✈️",
      target: 250000,
      current: 85000,
      targetDate: shiftMonths(today, 15),
      emergency: false,
    },
    {
      id: "laptop",
      name: "New Laptop",
      icon: "💻",
      target: 120000,
      current: 72000,
      targetDate: shiftMonths(today, 6),
      emergency: false,
    },
  ];
  s.holdings = [
    {
      id: "mf",
      name: "Mutual Funds",
      assetClass: "Mutual Funds",
      invested: 220000,
      current: 260600,
      startDate: shiftMonths(today, -48),
    },
    {
      id: "stocks",
      name: "Stocks",
      assetClass: "Stocks",
      invested: 75400,
      current: 91650,
      startDate: shiftMonths(today, -40),
    },
    {
      id: "gold",
      name: "Gold",
      assetClass: "Gold",
      invested: 56300,
      current: 62700,
      startDate: shiftMonths(today, -30),
    },
    {
      id: "fd",
      name: "FD / RD",
      assetClass: "FD/RD",
      invested: 63200,
      current: 67700,
      startDate: shiftMonths(today, -24),
    },
  ];
  s.bills = [
    {
      id: "electric",
      name: "Electricity",
      amount: 1840,
      dueDate: `${m}-22`,
      recurring: true,
      paid: false,
      category: "Bills",
      kind: "bill",
    },
    {
      id: "gym",
      name: "Gym membership",
      amount: 1200,
      dueDate: `${m}-15`,
      recurring: true,
      paid: false,
      category: "Personal",
      kind: "subscription",
    },
    {
      id: "netflix",
      name: "Netflix",
      amount: 649,
      dueDate: shiftMonths(`${m}-08`, 1),
      recurring: true,
      paid: false,
      category: "Entertainment",
      kind: "subscription",
    },
    {
      id: "rent",
      name: "House rent",
      amount: 15000,
      dueDate: shiftMonths(`${m}-01`, 1),
      recurring: true,
      paid: false,
      category: "Household",
      kind: "bill",
    },
  ];
  s.loans = [
    {
      id: "loan",
      name: "Personal Loan",
      principal: 458000,
      outstanding: 284000,
      emi: 9500,
      rate: 13.5,
    },
  ];
  s.festivals = [
    {
      id: "diwali",
      name: "Diwali",
      targetDate: shiftMonths(today, 2),
      budget: 22000,
      saved: 6000,
    },
    {
      id: "newyear",
      name: "New Year",
      targetDate: shiftMonths(today, 3),
      budget: 12000,
      saved: 0,
    },
  ];
  return s;
}
