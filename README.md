# Nouri

Nouri (working name) is a camera-first Android app for tracking food and nutrition. You photograph a meal, your ingredients or a nutrition label. An AI provider identifies what is there, and an on-device engine calculates calories and macros from reference data. Each estimate shows its range, its confidence and where its numbers came from. The same engine powers recipes, a pantry, a diary and weight trends.

> **Status: early development, not released.** The shared domain engine is implemented and has 250 passing JVM tests. All Android screens (camera and scan review, diary, search and logging, progress, recipes, pantry, settings, onboarding) are written and built in GitHub Actions, with Robolectric tests for the main flows. The debug APK has been installed on one phone, where Sign in with ChatGPT and "Interpret with AI" for typed meals work. Nothing else has been verified on a device yet. See [PROGRESS.md](PROGRESS.md).

## Quick start

Requirements: JDK 17+ (CI uses 21). For the Android app you also need the Android SDK and network access to Google Maven.

```bash
# Shared engine only (no Android SDK needed)
NOURI_SKIP_ANDROID=1 ./gradlew :shared:jvmTest

# Everything (needs Android SDK + Google Maven)
scripts/test.sh          # shared + app unit tests
scripts/debug-apk.sh     # builds and prints the debug APK path
scripts/benchmark.sh     # deterministic estimation benchmark
```

Every push to GitHub runs CI ([.github/workflows/ci.yml](.github/workflows/ci.yml)), which uploads a `nouri-debug-apk` artifact.

## Features and honest status

| Area | What exists | Status |
|---|---|---|
| Nutrition engine | Unit conversion, USDA SR28 data, ~190 canonical ingredients, ~85 regional dish compositions, deterministic recipe calculation, rounding policy, provenance | Implemented, tested (shared) |
| Meal estimation pipeline | Identification → portion → match → deterministic nutrition → ranges/confidence → corrections → on-device learning | Implemented, tested (shared); review UI built, end-to-end Robolectric test with the demo provider |
| AI providers | Provider interface; Sign in with ChatGPT (plan usage) and API-key providers via the OpenAI Responses API; deterministic demo provider | Implemented and unit-tested against mocks; **never tested against live OpenAI** |
| Diary | Room storage with frozen nutrition snapshots, soft delete and undo, duplicate guard, copy meal/day | Implemented; UI compiled in CI |
| Food search and logging | Unified search (your foods, dishes, ingredients, full USDA table), portion picker, natural-language logging (offline parser; optional AI), quick add | Implemented; UI compiled in CI |
| Progress | Weight trend (smoothed), intake charts, plain-language insights, optional target suggestions | Implemented; UI compiled in CI |
| Camera / scanning | CameraX with three modes, flash, tap-to-focus, exposure, gallery import, multi-photo, rotate/crop, on-device barcode and label OCR | Built in CI; **not yet run on a device** |
| Recipes / pantry / grocery | 80 built-in recipes across ~38 cuisines, scaling, variations, pantry matching, explainable ranking, cook mode | Engine tested; UI built in CI |
| Settings / onboarding / privacy controls | AI provider status, ChatGPT connect/disconnect, API key, privacy and retention, export, delete everything, onboarding | Built in CI; onboarding flow tested |
| iOS | Shared module is pure Kotlin Multiplatform common code | Planned ([IOS_ROADMAP.md](IOS_ROADMAP.md)) |

## Principles

- **AI identifies, the engine calculates.** Models never supply calorie numbers. Nutrition always comes from reference data through deterministic code ([NUTRITION_ENGINE.md](NUTRITION_ENGINE.md)).
- **Honest numbers.** Estimates are shown as ranges with coarse confidence labels, never as false-precision percentages.
- **No silent charges.** Signing in with ChatGPT does not by itself allow the app to use your plan. A separately billed API key is never used unless you opt in explicitly, and never when a plan usage limit is reached ([AI_INTEGRATION.md](AI_INTEGRATION.md)).
- **Local-first privacy.** No analytics, no accounts, no cloud sync, backups disabled ([PRIVACY.md](PRIVACY.md)).

## Documentation

| Doc | Contents |
|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | Modules, layers, data flow |
| [AI_INTEGRATION.md](AI_INTEGRATION.md) | Providers, Sign in with ChatGPT, Responses API, billing safeguards |
| [NUTRITION_ENGINE.md](NUTRITION_ENGINE.md) | Units, cooking yields, rounding, ranges, confidence |
| [DATA_SOURCES.md](DATA_SOURCES.md) | USDA SR28, Open Food Facts, approximate values, licences |
| [PRIVACY.md](PRIVACY.md) | What is stored, what leaves the device, retention |
| [TESTING.md](TESTING.md) | Test suites, CI, benchmark, simulated vs real-device testing |
| [DECISIONS.md](DECISIONS.md) | Architecture decision records |
| [PROJECT_PLAN.md](PROJECT_PLAN.md) | Phases and scope |
| [PROGRESS.md](PROGRESS.md) | What's done, in progress, next |
| [BLOCKERS.md](BLOCKERS.md) | Items that need a human (accounts, network access, signing) |
| [IOS_ROADMAP.md](IOS_ROADMAP.md) | Plan for the iOS app |
| [CLAUDE.md](CLAUDE.md) | Guidance for AI coding agents working on this repo |

## Licence and data

Code licence: not yet chosen (see [BLOCKERS.md](BLOCKERS.md)). Bundled nutrition data comes from USDA SR28, a public-domain U.S. Government work. Barcode lookups use Open Food Facts (ODbL), with attribution shown in the app. See [DATA_SOURCES.md](DATA_SOURCES.md).
