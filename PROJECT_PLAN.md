# Project plan

**Goal:** a polished, maintainable, camera-first food, recipe and nutrition app for Android that can be released on Google Play. Domain logic is shared so an iOS app can follow.

## Scope (v1)

- **Camera.** Three modes: scan meal, scan ingredients, scan label/barcode. Plus flash, tap-to-focus, exposure, gallery import, multi-photo, review, retake, crop/rotate, compression, progress and error states, and permission recovery.
- **Estimation.** Identification → portion → nutrition match → deterministic calculation → ranges and confidence → corrections → on-device learning. A reproducible benchmark.
- **Diary.** Meals, search, natural-language logging, quick add, copy meal or day, undo, snapshots with provenance.
- **Recipes.** Authentic regional recipes, with variations kept separate from the traditional version. Scaling, cook mode, AI-generated recipes clearly labelled, nutrition from the engine.
- **Pantry and grocery.** Ingredient scans fill the pantry only after confirmation. Shopping lists come from missing ingredients. The pantry is never changed silently.
- **Progress.** Weight trend, intake charts, descriptive insights, configurable targets with no guilt framing, optional adaptive suggestions.
- **AI.** Official Sign in with ChatGPT (plan usage), an optional separately billed API key (off until configured), a demo provider, and a future on-device provider. An AI status screen.
- **Privacy.** Local-first, no analytics, consent before AI image processing, image retention control, export and delete-all.
- **Accessibility.** TalkBack labels, never colour alone, dynamic type, dark and light themes.

## Phases

| Phase | Content | State |
|---|---|---|
| 0 | Gradle foundation, version catalog, CI | Done |
| 1 | Shared engine: units, USDA data, ontology, dishes, nutrition, rounding | Done |
| 2 | Shared AI layer: contracts, mock, Responses client, SIWC protocol | Done (live verification pending) |
| 3 | Shared pipeline: estimator, parsers, label, barcode, recipes, ranking, progress, insights, export, benchmark | Done |
| A | Android data layer, DI, theme, navigation shell | Done (CI green) |
| B | Home, Diary, Add food/search/NL/quick add, Food detail, Entry detail, Progress | Written; CI compile in progress |
| C | Camera (CameraX + ML Kit) and scan review flows with the demo provider | In progress |
| D | Discover/recipes, recipe detail, scaling, cook mode, pantry, grocery | In progress |
| E | Settings (AI status, SIWC connect/disconnect, API key, privacy, data export/delete), onboarding | In progress |
| F | Screen tests, accessibility pass, performance (baseline profile), docs, release prep | Not started |
| G | iOS app (see IOS_ROADMAP.md) | Not started |

## Performance budget (targets, not yet measured)

| Metric | Target |
|---|---|
| Cold start to first frame | < 1 s on a mid-range device |
| Camera preview ready | < 500 ms after opening the screen |
| Shutter to review screen (excluding AI) | < 1 s |
| Deterministic recalculation after an edit | < 16 ms |
| Food search results | < 100 ms after typing pauses |
| APK size (release, minified) | < 30 MB |

## Release checklist (later)

- Real-device QA matrix and live SIWC verification.
- Committed Room schema JSON.
- Release signing.
- Play listing: data-safety form matching PRIVACY.md, health-app declarations.
- Licence choice.
- Open Food Facts attribution visible in the app.
