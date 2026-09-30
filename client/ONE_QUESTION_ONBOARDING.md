# One-question login and onboarding

The HTML reference supplies age ranges, employment and income-source options, 27 bank names, and the single 15% market-drop scenario. Login and setup present one question per screen with progress, validation and Back navigation.

- Login: email → password. Registration: name → email → password, then sign in using the existing API contract.
- Setup: name → age range → employment → average income → income sources → salary day (only with Salary selected) → banks → each bank's opening balance → monthly investment target → risk scenario.
- Searchable multiple-bank selection creates named accounts. Continue without a bank creates a cash-only profile; another bank can be added later in Money → Accounts. No bank credentials or account numbers are requested.
- Opening balances seed individual accounts. Expected income remains a planning figure and creates no transaction.
- Sell immediately / Wait and watch / Invest more map to Conservative / Moderate / Aggressive. This is an initial preference, not a full suitability assessment. Historical prototype rates are not copied.
- Optional new schema fields preserve existing records and backups. Me → Edit supports age, employment and the scenario; banks selected during setup are shown there.
- Details use existing per-user on-device storage. The authentication API is unchanged; new profile fields are not synchronized to the server.

Validation: TypeScript, 46 tests and web export. Tests cover serialization, bank balances, conditional questions, amount validation, zero-income/cash-only setup, risk mapping and legacy backups. Live backend login and native-device checks remain required before release.

A 390 × 844 browser check passed registration and login with mocked auth responses, validation, multiple bank selection, balance preservation with Back, risk selection, dashboard creation and profile-field display, with no page errors. Actual backend authentication was not exercised.
