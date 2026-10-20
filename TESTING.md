# Testing

## What exists

| Suite | Location | Count | Runs where |
|---|---|---|---|
| Shared unit tests | `shared/src/commonTest` | 223 | Locally and in CI (`:shared:jvmTest`) |
| Shared JVM-only tests (benchmark, dish table printer) | `shared/src/jvmTest` | 3 | Locally and in CI |
| App Robolectric + Compose UI tests | `app/src/test` | 1 (first-launch screen) | CI only (`:app:testDebugUnitTest`) |
| Android lint | — | — | CI only (`:app:lintDebug`; reports uploaded, non-blocking) |

That makes **226 shared tests**. They cover:

- unit conversions and regional cups;
- the ontology, aliases and allergen/halal flags;
- recipe and dish calculation;
- the meal estimation pipeline and corrections;
- the natural-language parser;
- the label OCR parser (US, Canada and EU samples);
- barcode/GTIN handling and Open Food Facts mapping;
- the recipe engine, scaling and ranking;
- weight trend, energy, targets and the adaptive advisor;
- insights, intake statistics and CSV export;
- the AI layer (consent gating, no silent fallback, SSE parsing, error mapping);
- SIWC (PKCE, callback validation, ID token checks, refresh rotation, revocation).

App screen tests beyond the first-launch check are still to be written (see PROGRESS.md).

## Running

```bash
NOURI_SKIP_ANDROID=1 ./gradlew :shared:jvmTest   # no Android SDK needed
scripts/test.sh                                  # shared + app unit tests (needs Android SDK)
scripts/benchmark.sh                             # estimation benchmark report
scripts/lint.sh                                  # Android lint + secret/logging scan
```

Robolectric runs at SDK 36 (`app/src/test/resources/robolectric.properties`). JDK 17+ needs the `--add-opens` flags configured in `app/build.gradle.kts`.

## CI

`.github/workflows/ci.yml` runs on every push and pull request, in this order:

1. shared tests
2. app unit, Robolectric and Compose tests
3. assemble the debug APK
4. lint
5. the benchmark, rerun every time

It uploads `test-reports`, including `benchmark.md`, and `nouri-debug-apk`.

## Benchmark

`BenchmarkTest` runs 20 synthetic meal cases (`benchmark/BenchmarkFixtures.kt`) through the real matching, portion and nutrition pipeline, using **fixed provider outputs**. It measures the deterministic pipeline, **not** image recognition. The latest run:

| Metric | Value |
|---|---|
| Identification accuracy | 90.9% |
| Median meal energy error | 6.7% |
| Truth inside the shown range | 100% (mean range half-width **±37.5%**) |
| Corrections needed per meal | 0.2 |

Read coverage together with range width: wide ranges make coverage easy. The hard cases (an oversized pasta portion, a missed drink) are asserted to show up as errors. Real-photo accuracy has **not** been measured. That needs a labelled photo set and a live provider.

## Simulated vs real-device testing

| Claim | Status |
|---|---|
| Shared logic correct for the tested cases | Verified (JVM tests) |
| App compiles, first screen renders under Robolectric | Verified in CI |
| UI on a real phone or emulator | **Not done** |
| CameraX capture, ML Kit barcode and OCR on hardware | **Not done** |
| Sign in with ChatGPT and Responses API against live OpenAI | **Not done**; mocked HTTP only |
| Open Food Facts live lookups | **Not done**; mapper tested with sample JSON |

Do not describe a feature as working on devices until it has been tried on one. Record real-device results here, with device model and Android version.
