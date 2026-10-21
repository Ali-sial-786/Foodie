# Progress

_Last updated: 2026-10-21._

## Done

**Shared engine (`:shared`).** 256 tests pass locally; CI runs the same suite on every push.

- Units, regional cups, household portions.
- USDA SR28 data pipeline: 249 compiled core foods plus the full table as a search asset.
- Ingredient ontology (~205 ingredients, regional aliases, allergens, halal status, substitutions).
- ~85 regional dish compositions; 80 recipes across ~38 cuisines (including butter chicken, nihari, haleem, pad thai, pho, shawarma, falafel, bolognese, lasagne, tacos and jerk chicken), including variations kept separate from the traditional versions. Every built-in recipe meets the recipe-library standard (`data/recipes/README.md`): one course, diet labels calculated rather than declared, and a step-by-step method with heat levels, times and doneness cues. Nutrition was unchanged by the rewrite.
- Nutrition engine with yields, edible and consumed fractions, rounding policy and provenance.
- Meal estimation pipeline with ranges, confidence, corrections and correction memory.
- Offline natural-language meal parser and resolver.
- Nutrition-label OCR parser (US, Canada and EU/UK).
- GTIN handling and Open Food Facts mapping.
- Recipe scaling, variations, pantry matching, shopping lists, explainable ranking.
- Weight trend, energy estimate, target calculator, adaptive advisor; insights; CSV export.
- AI provider contracts, demo provider, OpenAI Responses client and provider, `AiService` safeguards.
- Sign in with ChatGPT protocol logic.
- Reproducible benchmark (20 synthetic cases).

**Android app (`:app`).** Compiled, unit-tested and assembled in GitHub Actions.

- Room database (v1, schema export on), DataStore settings, Keystore-encrypted secret store.
- Repositories; Open Food Facts lookup with cache; bundled USDA search asset.
- Image processing; loopback sign-in receiver; daily maintenance worker; manual DI container.
- Compose theme (light and dark), bottom navigation with a central Scan button, app-wide snackbar with undo.
- Manifest: permissions, `nouri://auth-complete` deep link, backups disabled.
- CI uploads a debug APK (`nouri-debug-apk`).

**Android screens.** All written; see the CI status line below for what has compiled and passed.

- Home: today's ring and macros, quick actions, log again, recipe ideas, expiry banner, demo-mode banner.
- Diary: day navigation and date picker, meal sections, delete with undo, copy meal or previous day.
- Add food: unified search, recent foods, natural-language describe (offline, plus optional "Interpret with AI" with disclosure), quick add, duplicate warning.
- Food detail and entry detail: unit/portion picker, nutrition table, confidence, "how this was worked out", provenance, rescale, delete, log again.
- Progress: weight chart (scale readings plus trend), daily energy bars with target line, range presets, observations, adaptive target suggestion (applied only on tap).
- Camera: CameraX preview with meal / ingredients / label-barcode modes, flash, tap-to-focus with focus ring, exposure, pinch zoom, capture, gallery import (multi-photo), photo review with rotate, centre crop and retake, optional portion details, consent dialog before the first real-provider upload, live on-device ML Kit barcode reading, on-device label OCR, camera-permission recovery (rationale, settings link, gallery fallback).
- Scan review: meal review with honest analysis phases, editable items (change food, amount, smaller/larger, preparation, added fats, include/remove, add missed item), clarifying question, ranges and confidence, logging and on-device correction learning; ingredient review with confirm-before-pantry chips, recipe matches and optional AI recipe generation (labelled); barcode/label review with Open Food Facts lookup, on-device label reading, optional AI reading with consent, editable values, saved food → portion → log.
- Discover and recipes: catalogue with search and filters, explainable ranking, recipe detail with non-naive scaling, pantry availability, variations, per-line nutrition, diet/allergen/halal notes, portion logging by servings, grams or share of the batch, cook mode with timers, recipe editor.
- Pantry and grocery: storage locations, expiry, add/edit with undo, confirm-only deductions after cooking, grocery list with share and move-to-pantry.
- Settings: AI provider status (account, billing mode, capabilities, restrictions, model), Sign in with ChatGPT connect/disconnect, API key entry, explicit fallback opt-in, privacy (retention, barcode lookup, personalisation, revoke consent, delete photos), profile, targets, preferences, units, day start, theme, CSV/JSON export, delete everything, data sources and attribution.
- Onboarding: welcome, optional goal/profile/targets/preferences (every step skippable), AI and privacy explanation.

**App tests (Robolectric, simulated — no device):** diary repository (idempotency, duplicate guard, soft delete/undo, snapshot round-trip, copy day), user-food index, settings defaults and reset, encrypted API-key storage, export mapping, recipe line parser, first launch, full onboarding skip flow, quick add, and an end-to-end meal scan (demo provider → estimator → review UI → diary).

**Added after device feedback:** recipe import from pasted text, screenshots or the Android share sheet (AI clean-up or on-device), brand-aware meal descriptions with Open Food Facts product search, counts that are never dropped, 52 more built-in recipes (each checked to calculate fully from USDA data), and a better pot-weight estimate for recipes that start with dry rice, pasta or pulses.

## Not started

- Instrumented tests on an emulator or device (none yet; all UI tests are Robolectric).
- Accessibility audit, baseline profile, performance measurement.
- ID-token signature verification against JWKS (the hook exists but isn't wired). The ID token arrives directly from the token endpoint over TLS, which OpenID Connect allows in place of a signature check; its claims are only used to label the account.
- Committing the Room schema JSON. CI prints it (step "Room schema is committed") until it is in git; commit it from a local build with the Android SDK, after which CI fails on any uncommitted schema change. The check only runs when Room code generation actually executes; when Gradle restores it from the build cache, no schema is written and the step just leaves a notice.
- iOS.

## Known limitations

- Android code is compiled and tested in CI. The debug APK has been installed on one real phone (model not recorded); most screens have not been tried there yet.
- Sign in with ChatGPT works on a real device (after fixing the host-id format and Android 15's background-network block), and "Interpret with AI" has been used live. Meal-photo analysis and recipe import with a live provider are not yet confirmed.
- Default OpenAI model names are placeholders, resolved at runtime from `GET /v1/models`.
- The benchmark uses synthetic fixtures and the deterministic demo provider, so it does not measure photo recognition.
