# Architecture decision records

Each entry gives the context, the decision and its consequences. Newest decisions go at the bottom.

## ADR-001 Kotlin Multiplatform `:shared` module, JVM target only for now
- **Context.** Domain logic should be portable to iOS, but iOS builds need macOS hosts.
- **Decision.** Put all domain logic in `commonMain`, with no Android or `java.*` imports. Only the `jvm()` target is enabled today; it is consumed by `:app` and runs the tests.
- **Consequences.** iOS targets can be switched on later with no code moves (see IOS_ROADMAP.md). Hashing and Base64 are implemented in common code (`auth/Crypto.kt`).

## ADR-002 Manual dependency injection instead of Hilt
- **Context.** Hilt adds annotation processing, build time and Android-only coupling. This sandbox can only compile Android code in CI, so each extra code generator costs iteration time.
- **Decision.** One `AppContainer` constructs everything, using `lazy` where useful. Compose accesses it through `LocalAppContainer`, and ViewModels are created with `viewModel { … }` initialisers.
- **Consequences.** Wiring is checked at compile time with no codegen, and tests can construct the container directly. Revisit if the graph grows large or needs scoping beyond the app and ViewModels.

## ADR-003 AI identifies; a deterministic engine calculates
- **Context.** Model-produced calorie numbers are not reproducible and cannot be audited.
- **Decision.** Providers return identification and portion ranges only. Nutrition always comes from reference data via `NutritionEngine` and `MealEstimator`.
- **Consequences.** Results are reproducible and explainable, with provenance on every number, and benchmarks are deterministic.

## ADR-004 Demo (mock) provider is the default; no silent fallback
- **Context.** A fresh install has no AI connection, and the user must never be charged unexpectedly.
- **Decision.** Default to `MOCK`, with results visibly labelled as simulated. A real-provider failure never falls back to demo output. The paid API-key provider is used as a fallback only with explicit opt-in, and never after a plan usage limit.
- **Consequences.** The app is fully explorable offline. Users may initially see demo results, so the UI labels them clearly.

## ADR-005 Official Sign in with ChatGPT only
- **Decision.** Use OpenAI's documented OAuth flow (PKCE, dynamic client registration, loopback redirect) and the Responses API with plan-usage tokens. No cookie extraction, browser automation, scraping or subscription proxies.
- **Consequences.** The feature depends on OpenAI's eligibility rules and preview status, and protocol constants are centralised in `SiwcConfig` for easy updates.

## ADR-006 Room for records; one JSON document in DataStore for settings
- **Decision.** Use Room (KSP, schema export, explicit migrations, no destructive fallback) for diary and other records. Store settings as one `@Serializable AppSettings` JSON document in Preferences DataStore, updated atomically.
- **Consequences.** Settings evolve by adding fields with defaults (`ignoreUnknownKeys`), and nothing needs a migration for settings.

## ADR-007 Frozen nutrition snapshots
- **Decision.** Each diary entry stores the nutrients, provenance, precision and range used at logging time. Reference data updates never rewrite history.
- **Consequences.** History is stable and auditable. Edits rescale the frozen values explicitly.

## ADR-008 Ktor client + kotlinx.serialization
- **Decision.** Ktor (OkHttp engine on Android) for HTTP, with SSE parsed manually for the Responses API. kotlinx.serialization for JSON throughout.
- **Consequences.** The same networking code works in `:shared` for iOS later (Darwin engine), and tests use `ktor-client-mock`.

## ADR-009 CameraX + on-device ML Kit for barcodes and label OCR
- **Decision.** CameraX for capture, preview, tap-to-focus and exposure. ML Kit barcode scanning and text recognition, using bundled models, run on the device so label reading works offline. A provider is only asked to read a label if OCR fails and the user chooses it.
- **Status.** The dependencies are in place; the screens are in progress.

## ADR-010 Android Keystore AES-GCM for secrets in `noBackupFilesDir`
- **Decision.** Encrypt tokens and the API key with a non-exportable Keystore key, writing atomic blob files that are excluded from backups.
- **Consequences.** Secrets can't be restored onto another device; users sign in again. That is intended under the SIWC local-storage terms.

## ADR-011 Coarse confidence labels and ranges, not percentages
- **Decision.** Use three levels (Higher, Moderate, Low) plus energy ranges. Per-item uncertainty combines by root-sum-square.
- **Consequences.** The app avoids implying calibrated probabilities. The benchmark reports range coverage together with mean range width, so wide ranges can't game coverage.

## ADR-012 Bundled USDA SR28 data, generated code for core foods
- **Decision.** Commit the SR28 ASCII source. Generate Kotlin for the referenced foods (249 at present) (so it works offline on all platforms) and a TSV asset for full search on Android.
- **Consequences.** No runtime download and fully reproducible data (`scripts/fixtures.sh`). SR Legacy/FDC migration is planned.

## ADR-013 Android builds verified in CI, not locally
- **Context.** The development sandbox cannot reach Google Maven or the Android SDK (`dl.google.com`).
- **Decision.** Build and test `:shared` locally (`NOURI_SKIP_ANDROID=1`). Compile, unit-test, lint and assemble `:app` in GitHub Actions on every push.
- **Consequences.** Slower feedback for Android code. No on-device testing has happened yet (see BLOCKERS.md).
