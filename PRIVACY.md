# Privacy

Nouri is **local-first**. There are no Nouri accounts, servers, analytics, advertising SDKs or crash-reporting SDKs. Diary, weight, recipes, pantry and settings stay on the device.

## Stored on the device

| Data | Where | Protection |
|---|---|---|
| Diary, weights, measurements, recipes (including your ratings and favourites), pantry, grocery, user foods, scan sessions | Room database (app-private) | Android app sandbox |
| Settings (profile, targets, preferences, AI choice, privacy choices, correction memory) | DataStore, a single JSON document | App sandbox |
| Sign in with ChatGPT tokens, optional OpenAI API key | `noBackupFilesDir/secrets/*.bin` | AES-256-GCM with a non-exportable Android Keystore key (`KeystoreCipher`) |
| Processed food photos | `filesDir/scans` | App sandbox; retention setting below |

**Backups are disabled.** `allowBackup="false"`, `fullBackupContent="false"`, and `data_extraction_rules.xml` excludes every domain from cloud backup and device transfer. Health data and credentials never leave the device by that route. Use export instead.

Credentials and tokens are never logged, and no secrets are hard-coded. `scripts/lint.sh` greps for common mistakes.

## Photos

- Before analysis, `ImageProcessor`:
  - applies EXIF orientation and any rotation or crop the user chose;
  - downscales to at most **1600 px** on the long edge;
  - re-encodes as **JPEG quality 85**, which drops all metadata (GPS, device, timestamps).

  Only this processed copy is ever sent anywhere. Originals are not uploaded.
- **Retention** (`ImageRetention`): **Delete after analysis** (default), **Keep for 30 days**, or **Keep until I delete them**. The daily maintenance worker enforces it.

## What leaves the device, and when

| Action | Sent to | What is sent |
|---|---|---|
| Analyse a meal, ingredient or label photo with a real provider | The selected AI provider (OpenAI via ChatGPT plan or the user's API key) | The processed photo(s), optional portion hints, and (if personalisation is on) up to 6 favourite cuisines and 15 logged dish names |
| "Interpret with AI" for a typed meal | The selected provider | The typed text and saved recipe names |
| Generate recipes with AI | The selected provider | Chosen ingredients, preferences, dietary requirements and allergies to avoid |
| Barcode lookup | Open Food Facts | The barcode number only (can be turned off) |
| "Search packaged products" / "Find … product" | Open Food Facts | The search words only (e.g. "deep naan"), and only when tapped; same off switch as barcode lookup |
| "Clean up with AI" when importing a recipe | The selected provider | The pasted text and the chosen or shared screenshots (screenshots need photo consent) |
| "Import on my phone" | Nothing is sent | Text recognition for screenshots runs on the device; screenshots are deleted after saving |
| Sign in with ChatGPT | OpenAI's auth server, in the user's browser | Standard OAuth parameters; the app never sees the password |

Rules:

- **Consent first.** Photo analysis with a non-demo provider requires explicit consent (`AiSettings.photoConsent`), shown with a disclosure before the first upload. Consent can be revoked in settings.
- **Demo mode sends nothing.** The default provider is simulated and makes no network calls.
- Nouri cannot access ChatGPT conversation history or memories, and does not request them.
- One account's tokens are only used for that user's own requests on that device.
- Offline: search, the diary, the natural-language interpreter, recipes, the pantry and progress all work without a network connection.

## Your controls

- Export the diary and weights as CSV (`export/` in `:shared`; settings UI in progress).
- Delete everything: database, settings, photos, and the Keystore key (which makes any leftover encrypted blobs unreadable). Settings UI in progress.
- Disconnect AI: revokes the ChatGPT refresh token remotely where possible and clears local tokens.

## Future cloud sync

Not implemented. If it is ever added, it will be opt-in, end-to-end documented here, and off by default.
