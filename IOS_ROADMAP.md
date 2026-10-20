# iOS roadmap

## Already portable

All of `:shared/commonMain` is pure Kotlin Multiplatform common code, with no Android or `java.*` APIs:

- nutrition engine, units, USDA core foods (compiled in), ontology, dishes;
- estimation pipeline, natural-language parser, label OCR parser, barcode mapping;
- recipes, ranking, pantry, diary model, progress, insights, CSV export;
- AI contracts, prompts, schemas, validation, the Responses client (Ktor);
- the SIWC protocol: PKCE, callback validation, refresh, revocation. SHA-256 and Base64url are implemented in common code.

## Steps

1. **Enable targets** in `shared/build.gradle.kts`: `iosArm64()` and `iosSimulatorArm64()` (commented in place), built on a macOS CI runner. Add the Ktor Darwin engine. Export an XCFramework (SKIE, or plain KMP framework export).
2. **Platform adapters**, each behind an existing common interface:
   - `SiwcStore` → Keychain (`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`, no iCloud sync).
   - `LoopbackRedirectReceiver` → `ASWebAuthenticationSession` with the loopback redirect, or a local `NWListener` on 127.0.0.1, whichever OpenAI's docs allow for iOS.
   - `FoodRepository` for the full USDA table: bundle the same TSV.
   - `IdTokenSignatureVerifier`: JWKS verification with CryptoKit.
3. **SwiftUI app** mirroring the Android navigation: Home, Discover, Scan, Diary, Progress.
4. **Camera:** AVFoundation capture, Vision for barcodes (`VNDetectBarcodesRequest`) and text recognition (`VNRecognizeTextRequest`), feeding the shared label parser.
5. **Persistence:** SQLite via SQLDelight (shared) or SwiftData. Diary snapshots use the same serialisable models.
6. **Health:** optional HealthKit export of energy and weight, opt-in only.

## Open questions

- Shared persistence (move to SQLDelight in `:shared`) versus native per platform.
- Whether SIWC on iOS uses the same open-source "token sharing" flow; to be confirmed against OpenAI's docs at the time.
