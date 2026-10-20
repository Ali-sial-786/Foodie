# Blockers and items that need you

These need a human: an account, a decision or a device. None blocks continued development, because work proceeds with the demo provider and CI.

| # | Item | Why | What to do |
|---|---|---|---|
| 1 | **Local Android builds in the cloud dev environment** | The sandbox can't reach Google Maven or the Android SDK (`dl.google.com`, `maven.google.com`), so Android code is only compiled in GitHub Actions. That slows iteration. | Allow `dl.google.com` and `maven.google.com` in the environment's network settings: https://code.claude.com/docs/en/cloud-environments#network-access |
| 2 | **Real-device testing** | Nothing has run on a phone or emulator yet. Camera, ML Kit and UI behaviour are unverified. | Install the `nouri-debug-apk` artifact from a CI run on an Android 8+ device and report issues. Results go in TESTING.md. |
| 3 | **Sign in with ChatGPT, end to end** | The OpenAI hosts are unreachable from the sandbox. The protocol was implemented from the official docs and tested only against mocks. Sign-in must be done by you, in your browser. | On a device: Settings → AI provider → Connect ChatGPT. Confirm plan usage is granted, run a meal scan, then disconnect and check revocation. |
| 4 | **SIWC eligibility** | OpenAI's terms let open-source/local apps self-serve. Commercial or hosted apps need the partner programme. | Decide how Nouri will be distributed (open source, or a commercial Play listing) and apply to the programme if needed. See https://openai.com/policies/sign-in-with-chatgpt-terms |
| 5 | **Licence** | No licence file has been chosen. This affects SIWC eligibility and contributions. | Pick a licence (for example Apache-2.0 or GPL-3.0). |
| 6 | **Play Store** | A release needs a signing key, a developer account, a data-safety form and a health-app declaration. | Create them when ready. Keep the keystore out of the repo (`.gitignore` already excludes `*.jks` and `keystore.properties`). |
| 7 | **Open Food Facts usage** | The public API is free but asks apps to identify themselves and respect rate limits. Data is ODbL and needs attribution. | Nothing needed for development. Before release, check Open Food Facts' current API terms and update the User-Agent contact details if needed. |
| 8 | **OpenAI API key (optional)** | Only needed to test the separately billed API provider. Usage is charged to your account. | Enter a key in Settings only if you want that provider. It is never used automatically. |
