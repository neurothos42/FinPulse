export const ageRanges = ["18–24", "25–34", "35–44", "45–60", "60+"] as const;
export const employments = [
  "Student",
  "Salaried",
  "Self-employed",
  "Business owner",
  "Homemaker",
  "Other",
] as const;
export const incomeSources = [
  "Salary",
  "Freelance",
  "Business",
  "Rental",
  "Family support",
  "Other",
] as const;
export const riskQuestion =
  "If your investments temporarily fell 15% in value, what would you most likely do?";
export const riskAnswers = [
  { label: "Sell immediately", profile: "Conservative" },
  { label: "Wait and watch", profile: "Moderate" },
  { label: "Invest more", profile: "Aggressive" },
] as const;
// Names from the reference prototype; no historical deposit rates are copied.
export const bankNames = [
  "State Bank of India",
  "HDFC Bank",
  "ICICI Bank",
  "Axis Bank",
  "Punjab National Bank",
  "Bank of Baroda",
  "Canara Bank",
  "Union Bank of India",
  "Bank of India",
  "Indian Bank",
  "Central Bank of India",
  "UCO Bank",
  "IDBI Bank",
  "Kotak Mahindra Bank",
  "IndusInd Bank",
  "IDFC FIRST Bank",
  "Yes Bank",
  "Federal Bank",
  "RBL Bank",
  "Bandhan Bank",
  "Karnataka Bank",
  "South Indian Bank",
  "DCB Bank",
  "AU Small Finance Bank",
  "Equitas Small Finance Bank",
  "Jana Small Finance Bank",
  "Ujjivan Small Finance Bank",
] as const;
