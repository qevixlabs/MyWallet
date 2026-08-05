# MyWallet

A simple financial companion that tells you where your money goes, what you can
afford, and helps you plan — in plain language, with no jargon.

Free, no subscription, no account. Everything lives on your phone.

## What it does

- **Money in and out**, described in your own words rather than filed under
  fixed categories.
- **Multi-currency.** Get paid in USD, pay bills in NPR, read everything in one
  currency. The exchange rate is captured when you save an entry, so a closed
  month stays closed.
- **Accounts** — bank, wallet, cash — each with its own currency and balance.
  Cash spent outside the app is corrected with a balance adjustment rather than
  a made-up income entry.
- **Debts, deposits, policies and goals**, each with the arithmetic its own bank
  actually uses: a loan accrues by the day, a savings quarter pays a fixed slice
  of the year, a fixed deposit is simple interest across its term.
- **Bikram Sambat or Gregorian**, switchable. In BS mode a "month" really is a
  Nepali month, so salary and rent line up with the calendar you think in.
- **English and Nepali**, switchable in-app.
- **Backups** to a file or folder you choose, manual or scheduled. Restores
  merge rather than overwrite, so an old backup can never undo recent work.

## Privacy

There is no account, no server and no analytics. Your entries never leave the
device. The only network request the app makes is to fetch public exchange
rates, and it works offline from a cached copy.

## Building

Requires JDK 17 and the Android SDK.

```
./gradlew assembleDebug          # debug build
./gradlew testDebugUnitTest      # unit tests
./gradlew assembleRelease        # signed release, needs keystore.properties
```

Release signing reads `keystore.properties` in the project root, which is not
in this repository. Without it the release build still runs and produces an
unsigned APK.

## Licence

MIT — see [LICENSE](LICENSE).
