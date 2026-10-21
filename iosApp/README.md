# Nouri for iPhone

SwiftUI app on the shared Kotlin engine (`:shared`, exported as the `NouriShared` XCFramework). All app logic — diary, search, typed meals, photo scans, barcodes, recipes, progress, settings, AI providers and Sign in with ChatGPT — lives in `NouriAppModel` (shared/src/commonMain/.../app); the Swift code renders it and supplies storage (JSON files in Application Support, excluded from backup), the Keychain, the camera and the loopback sign-in listener.

## Build (macOS with Xcode 16 or later)

```bash
./gradlew :shared:assembleNouriSharedReleaseXCFramework   # from the repository root
brew install xcodegen
cd iosApp && xcodegen generate && open Nouri.xcodeproj
```

Pick your team under Signing & Capabilities to run on a phone. CI (`.github/workflows/ios.yml`) builds and tests on the simulator and produces an unsigned `.ipa`.

## Installing on an iPhone

An iPhone app must be signed by an Apple account before it can be installed:
- **With a Mac:** open the project in Xcode, choose your Apple ID as the team, connect the phone and press Run (a free Apple ID works; the app expires after 7 days).
- **Without a Mac:** sideload the unsigned `Nouri-unsigned.ipa` artifact with a tool that re-signs it with your Apple ID (for example AltStore or Sideloadly).
- **TestFlight:** needs a paid Apple Developer Program membership; CI can then sign and upload builds once signing secrets are added.
