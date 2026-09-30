# One-question login and onboarding

The HTML reference supplies age ranges, employment and income-source options, 27 bank names, and the single 15% market-drop scenario. Login and setup present one question per screen with progress, validation and Back navigation.

- Static login: any non-empty email → any non-empty password → local preview setup. No auth request is made and neither input is stored. All entries share one separate on-device preview profile; they are not real accounts. Returning preview users retain their completed setup and can edit it from Me.
- Setup: name → age range → employment → average income → income sources → salary day (only with Salary selected) → banks → each bank's opening balance → monthly investment target → risk scenario.
- Searchable multiple-bank selection creates named accounts. Continue without a bank creates a cash-only profile; another bank can be added later in Money → Accounts. No bank credentials or account numbers are requested.
- Opening balances seed individual accounts. Expected income remains a planning figure and creates no transaction.
- Sell immediately / Wait and watch / Invest more map to Conservative / Moderate / Aggressive. This is an initial preference, not a full suitability assessment. Historical prototype rates are not copied.
- Optional new schema fields preserve existing records and backups. Me → Edit supports every setup answer: name, age, employment, income, income sources, salary day, bank selections, individual bank opening balances, investment target and risk scenario. Bank deselection retains existing accounts and transactions; newly selected banks are added without duplicate names.
- Details use existing per-user on-device storage. The authentication API is unchanged; new profile fields are not synchronized to the server.

Validation: TypeScript, 48 tests and web export. Tests cover serialization, bank balances, conditional questions, amount validation, zero-income/cash-only setup, risk mapping and legacy backups. Live backend login and native-device checks remain required before release.


Browser verification (390 × 844): arbitrary email/password, zero auth requests, all setup questions, Back retention, profile edits and saved risk answer passed without page errors.
