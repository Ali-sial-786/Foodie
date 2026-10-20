# Architecture

## Modules

```
:shared   Kotlin Multiplatform; commonMain only (JVM target today, iOS later)
          Pure domain logic: no Android, no java.* in commonMain.
:app      Android application (Compose, Room, DataStore, CameraX, Keystore)
          Depends on :shared. Excluded from the build when NOURI_SKIP_ANDROID=1.
```

Build: Gradle 9.7.1 (wrapper is checksum-pinned), AGP 9.4.1 with built-in Kotlin, Kotlin 2.4.21, KSP 2.3.12. compileSdk 37, targetSdk 36, minSdk 26, Java 17 bytecode. All plugins load once on the root buildscript classpath. Versions live in [gradle/libs.versions.toml](gradle/libs.versions.toml), and `scripts/check-latest-versions.sh` checks them.

## Shared module (`app.nouri.core`)

| Package | Responsibility |
|---|---|
| `model` | `Nutrients` (unknown optional nutrients are `null`, not 0), `Provenance`/`NutritionSource`, `ConfidenceLevel`, `EstimateRange`, `FoodPreferences` |
| `units` | Quantities, household units, region-aware cups and spoons, `UnitConverter.toGrams` (every non-exact conversion carries a stated assumption) |
| `fooddb` | `FoodReference` (per 100 g edible portion), USDA SR28 factory, generated core foods, repositories (in-memory and composite) |
| `ontology` | ~190 canonical ingredients with regional aliases, allergens, halal status, substitutions; `IngredientNormalizer` |
| `nutrition` | `NutritionEngine` (line, recipe and food calculation), `NutritionRounding`/`Precision` |
| `dishes` | ~85 reference dish compositions, `DishNutrition`, `DishMatcher` |
| `scan` | Meal estimation pipeline: `FoodMatcher`, `MealEstimator`, `ScanEdit` corrections, `CorrectionMemory`, diary conversion |
| `parse` | Offline natural-language meal parser and resolver |
| `label` | Nutrition-label OCR text parser (US, Canada, EU/UK) |
| `barcode` | GTIN validation, Open Food Facts mapping |
| `recipes` | Recipe model, cuisine catalogue, 28 built-in recipes, scaling, variations, pantry matching, shopping lists |
| `recommend` | Explainable `RecipeRanker` (hard filters plus scored reasons) |
| `pantry` | Pantry and grocery models; deduction proposals (never applied silently) |
| `diary` | `DiaryEntry` with a frozen `NutritionSnapshot`, daily summaries, diary-day boundary, duplicate guard |
| `progress` | Weight trend, energy estimates, target calculator, adaptive target advisor |
| `insights` | Intake statistics and descriptive insights |
| `export` | CSV exporters |
| `ai` | Provider contracts, prompts, JSON schemas, validation, `AiService`; `openai` (Responses client and provider), `mock` (demo provider) |
| `auth` | Sign in with ChatGPT protocol (PKCE, callback validation, ID token checks, refresh, revocation) |
| `benchmark` | Reproducible estimation benchmark and fixtures |
| `NouriCore` | Facade that wires the engine together for a platform |

Domain code takes dates and the clock as parameters. Only `util/SystemTime` reads the wall clock, for platform callers.

## Android app (`app.nouri`)

```
NouriApplication ─ creates AppContainer (manual DI), warms caches, schedules maintenance
MainActivity     ─ edge-to-edge; collects settings; NouriTheme + NouriApp
ui/              ─ Compose screens + ViewModels (viewModel { … } initialisers)
  navigation/    ─ @Serializable type-safe routes, bottom bar (Home · Discover · Scan · Diary · Progress)
  components/    ─ design-system pieces, formatting, Canvas charts
data/
  db/            ─ Room: diary_entries, weight_entries, body_measurements, recipes (+ingredients, steps, usage),
                   pantry_items, grocery_items, user_foods, scan_sessions, saved_meals
  prefs/         ─ DataStore: one JSON AppSettings document
  secure/        ─ Android Keystore AES-256-GCM; encrypted blob files in noBackupFilesDir
  repo/          ─ repositories (Flow-based), Open Food Facts lookup, bundled USDA asset search
ai/ChatGptSignIn ─ loopback redirect receiver + sign-in orchestration
image/           ─ photo processing (orientation, crop, downscale, re-encode, hash)
work/            ─ daily maintenance worker (image retention, purge soft-deleted rows, optional expiry reminder)
```

### Data flow: photo to diary (target design; review UI in progress)

1. **Capture.** CameraX or the gallery produces images. `ImageProcessor` orients, crops, downscales to 1600 px and re-encodes them as JPEG, which drops metadata. Captures pass between screens through the in-memory `ScanSessions`.
2. **Identify.** `AiService.analyzeMeal` calls the selected provider. Photo consent is required for non-demo providers. The provider returns dish candidates, components and gram ranges. It never returns calories.
3. **Estimate.** `MealEstimator` matches each item to a reference (correction memory, saved foods, dishes, ingredients, search), computes nutrients deterministically, and combines uncertainty into ranges.
4. **Correct.** Each `ScanEdit` triggers a full deterministic recalculation. Logged corrections update `CorrectionMemory` on the device.
5. **Log.** `ScanLogging.toDiaryEntries` freezes a `NutritionSnapshot` with provenance and precision. Repositories store it with an idempotency key.

### Dependency injection

`AppContainer` builds every dependency once, using `lazy` where helpful. Compose reads it through `LocalAppContainer`. Tests construct it with an in-memory database, a test cipher and zero mock latency. See DECISIONS.md, ADR-002.

### Persistence rules

- Diary entries never recalculate. Editing an entry creates a new snapshot by rescaling the frozen per-gram values.
- Deletes are soft (with undo). Rows deleted more than 30 days ago are purged by the maintenance worker.
- The Room schema is at version 1 with `exportSchema = true` and no destructive fallback. Migrations must be added to `NouriDatabase.MIGRATIONS` before any schema change ships.
