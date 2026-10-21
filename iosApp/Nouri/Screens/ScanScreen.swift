import SwiftUI
import PhotosUI
import VisionKit
import NouriShared

/// Photo of a meal or a barcode → editable estimate or product → log.
struct ScanSection: View {
    @Environment(AppStore.self) private var store
    let dateIso: String
    let mealId: String
    @State private var pickerItem: PhotosPickerItem?
    @State private var showCamera = false
    @State private var showBarcode = false
    @State private var working = false
    @State private var result: ScanResultView?
    @State private var pendingImage: UIImage?
    @State private var askConsent = false
    @State private var product: ProductView?
    @State private var barcodeText = ""

    @State private var productHit: Token?

    var body: some View {
        let settings = store.model.settings()
        Section {
            if UIImagePickerController.isSourceTypeAvailable(.camera) {
                Button { showCamera = true } label: { Label("Take a photo of your meal", systemImage: "camera") }
            }
            PhotosPicker(selection: $pickerItem, matching: .images) { Label("Choose a photo", systemImage: "photo") }
            Button { showBarcode = true } label: { Label("Scan a barcode", systemImage: "barcode.viewfinder") }
                .disabled(!DataScannerViewController.isSupported)
            HStack {
                TextField("Or type a barcode", text: $barcodeText).keyboardType(.numberPad)
                Button("Look up") { lookup(barcodeText) }.disabled(barcodeText.count < 8)
            }
        } footer: {
            Text(settings.provider == "MOCK"
                 ? "Demo mode: photos are not sent anywhere and results are simulated. Choose an AI provider in Settings for real analysis."
                 : "A resized copy of the photo, without location data, goes only to the AI provider you chose. Nouri calculates the nutrition itself.")
        }
        .sheet(isPresented: $showCamera) {
            CameraPicker { image in analyze(image) }.ignoresSafeArea()
        }
        .sheet(isPresented: $showBarcode) {
            BarcodeScanner { code in
                showBarcode = false
                lookup(code)
            }
            .ignoresSafeArea()
        }
        .sheet(item: $productHit) { token in
            FoodLogSheet(hit: token.hit, dateIso: dateIso, mealId: mealId)
        }
        .onChange(of: pickerItem) { _, item in
            guard let item else { return }
            Task { @MainActor in
                if let data = try? await item.loadTransferable(type: Data.self), let image = UIImage(data: data) { analyze(image) }
                pickerItem = nil
            }
        }
        .alert("Send this photo?", isPresented: $askConsent) {
            Button("Agree and continue") {
                store.model.setPhotoConsent(granted: true)
                store.changed()
                if let image = pendingImage { analyze(image) }
            }
            Button("Not now", role: .cancel) { pendingImage = nil }
        } message: {
            Text("A resized copy without location data will be sent to \(settings.provider == "CHATGPT_PLAN" ? "ChatGPT" : "OpenAI") to identify the food. You can withdraw this in Settings › Privacy.")
        }
        if working { Section { ProgressView("Working…") } }
        if let product {
            Section("Product") {
                Text(product.title).font(.headline)
                if !product.subtitle.isEmpty { Text(product.subtitle).font(.caption).foregroundStyle(.secondary) }
                if let kcal = product.kcalPer100gText { Text(kcal).font(.caption) }
                if let token = product.token {
                    Button("Choose amount and log") {
                        productHit = Token(hit: FoodHitView(token: token, title: product.title, subtitle: product.subtitle, kcalPer100gText: product.kcalPer100gText ?? ""))
                    }
                }
                Text("Product data from Open Food Facts (ODbL).").font(.caption2).foregroundStyle(.secondary)
            }
        }
        if let result {
            ScanReview(result: result, dateIso: dateIso, mealId: mealId) { self.result = $0 }
        }
    }

    private func analyze(_ image: UIImage) {
        let settings = store.model.settings()
        if settings.provider != "MOCK" && !settings.photoConsent {
            pendingImage = image
            askConsent = true
            return
        }
        guard let processed = ImageProcessing.process(image) else { store.show("Couldn't read that photo"); return }
        working = true
        product = nil
        Task { @MainActor in
            result = try? await store.model.analyzePhoto(jpeg: processed.bytes, widthPx: processed.width, heightPx: processed.height, demoScenario: nil)
            working = false
            pendingImage = nil
        }
    }

    private func lookup(_ code: String) {
        working = true
        result = nil
        Task { @MainActor in
            product = try? await store.model.lookupBarcode(code: code)
            working = false
        }
    }
}

private struct ScanReview: View {
    @Environment(AppStore.self) private var store
    let result: ScanResultView
    let dateIso: String
    let mealId: String
    let onChange: (ScanResultView?) -> Void

    var body: some View {
        Section("Estimate") {
            if result.simulated { SimulatedBanner() }
            if let error = result.error {
                Label(error, systemImage: "exclamationmark.triangle").foregroundStyle(.red)
            } else {
                Text(result.explanation).font(.subheadline)
                HStack {
                    Text(result.total.kcalText).font(.headline)
                    Spacer()
                    Text(result.rangeText).font(.caption).foregroundStyle(.secondary)
                }
                Text(result.confidenceLabel).font(.caption).foregroundStyle(.secondary)
            }
        }
        if result.error == nil {
            Section("Items — adjust the amounts") {
                ForEach(result.items, id: \.id) { item in
                    VStack(alignment: .leading, spacing: 4) {
                        Toggle(isOn: Binding(get: { item.included }, set: { onChange(store.model.setScanItemIncluded(itemId: item.id, included: $0)) })) {
                            VStack(alignment: .leading) {
                                Text(item.name)
                                Text("\(item.nutrition.kcalText) · \(item.rangeText)").font(.caption).foregroundStyle(.secondary)
                            }
                        }
                        Stepper(item.portionText, onIncrement: {
                            onChange(store.model.setScanItemGrams(itemId: item.id, grams: item.grams + 10))
                        }, onDecrement: {
                            onChange(store.model.setScanItemGrams(itemId: item.id, grams: max(5, item.grams - 10)))
                        })
                        .font(.caption)
                    }
                }
                ForEach(result.warnings, id: \.self) { Text($0).font(.caption2).foregroundStyle(.secondary) }
                Button("Log meal") {
                    let outcome = store.model.logScan(mealId: mealId, dateIso: dateIso)
                    store.changed(outcome.logged ? "Logged \(outcome.count) item(s)" : outcome.error ?? "Not logged")
                    if outcome.logged { onChange(nil) }
                }
            }
        }
    }
}

/// The system camera, for a single meal photo.
struct CameraPicker: UIViewControllerRepresentable {
    let onImage: (UIImage) -> Void
    @Environment(\.dismiss) private var dismiss

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let parent: CameraPicker
        init(_ parent: CameraPicker) { self.parent = parent }

        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            if let image = info[.originalImage] as? UIImage { parent.onImage(image) }
            parent.dismiss()
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { parent.dismiss() }
    }
}

/// Live barcode scanning with VisionKit, on the device only.
struct BarcodeScanner: UIViewControllerRepresentable {
    let onCode: (String) -> Void

    func makeUIViewController(context: Context) -> DataScannerViewController {
        let scanner = DataScannerViewController(
            recognizedDataTypes: [.barcode(symbologies: [.ean13, .ean8, .upce, .code128])],
            qualityLevel: .balanced, recognizesMultipleItems: false, isHighFrameRateTrackingEnabled: false,
            isHighlightingEnabled: true
        )
        scanner.delegate = context.coordinator
        return scanner
    }

    func updateUIViewController(_ uiViewController: DataScannerViewController, context: Context) {
        if !uiViewController.isScanning { try? uiViewController.startScanning() }
    }

    func makeCoordinator() -> Coordinator { Coordinator(onCode: onCode) }

    final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        let onCode: (String) -> Void
        private var done = false
        init(onCode: @escaping (String) -> Void) { self.onCode = onCode }

        func dataScanner(_ dataScanner: DataScannerViewController, didAdd addedItems: [RecognizedItem], allItems: [RecognizedItem]) {
            guard !done else { return }
            for item in addedItems {
                if case .barcode(let barcode) = item, let value = barcode.payloadStringValue {
                    done = true
                    dataScanner.stopScanning()
                    onCode(value)
                    return
                }
            }
        }
    }
}
