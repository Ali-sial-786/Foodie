# Progress

_Last updated: 2026-10-20._

## Done

**Shared engine (`:shared`).** 226 tests pass locally and in CI.

- Units, regional cups, household portions.
- USDA SR28 data pipeline: 235 compiled core foods plus the full table as a search asset.
- Ingredient ontology (~190 ingredients, regional aliases, allergens, halal status, substitutions).
- ~85 regional dish compositions; 28 recipes across ~38 cuisines, including variations kept separate from the traditional versions.
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

## Written, awaiting a green CI run

- Home: today's ring and macros, quick actions, log again, recipe ideas, expiry banner, demo-mode banner.
- Diary: day navigation and date picker, meal sections, delete with undo, copy meal or previous day.
- Add food: unified search, recent foods, natural-language describe (offline, plus optional "Interpret with AI" with disclosure), quick add, duplicate warning.
- Food detail: unit and portion picker, nutrition table, confidence, "how this was worked out", source.
- Entry detail: rescale, change meal, delete, log again, provenance.
- Progress: weight chart (scale readings plus trend), daily energy bars with target line, range presets, observations, adaptive target suggestion (accept only on tap), log-weight dialog.

## In progress (being written now; target design)

- **Camera:**
  - CameraX preview with the three modes, flash, tap-to-focus and exposure;
  - capture plus gallery import (multi-photo);
  - photo review with rotate, crop and retake;
  - consent dialog before the first real-provider upload;
  - live ML Kit barcode detection in label mode;
  - on-device OCR for labels.
- **Scan review:**
  - meal review with editable items, clarifying question, range and confidence, logging and correction learning;
  - ingredient review with chips → pantry (explicit) and recipe suggestions;
  - label/barcode review → saved food → log.
- **Discover and recipes:** catalogue browsing with filters, recipe detail with nutrition and scaling, variations, cook mode, saving AI-generated recipes (labelled).
- **Pantry and grocery** screens.
- **Settings:**
  - AI provider status (account, billing mode, capabilities, restrictions, model);
  - Sign in with ChatGPT connect and disconnect, API key entry, fallback opt-in;
  - privacy (retention, barcode lookup, personalisation, revoke photo consent);
  - profile and targets, preferences, units, day start, theme;
  - export CSV, delete all data;
  - data sources and attribution.
- **Onboarding:** welcome, optional goals and profile, targets, food preferences, AI and privacy explanation.

## Not started

- Screen-level Robolectric/Compose tests beyond the first-launch test.
- Accessibility audit, baseline profile, performance measurement.
- ID-token signature verification against JWKS (the hook exists but isn't wired).
- Committing the Room schema JSON.
- iOS.

## Known limitations

- Android code has only been compiled and tested in CI. Nothing has run on a device or emulator.
- Sign in with ChatGPT and the Responses API have only been tested against mocked HTTP.
- Default OpenAI model names are placeholders, resolved at runtime from `GET /v1/models`.
- The benchmark uses synthetic fixtures and the deterministic demo provider, so it does not measure photo recognition.
