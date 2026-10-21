import SwiftUI
import SafariServices
import UniformTypeIdentifiers
import NouriShared

struct SettingsScreen: View {
    @Environment(AppStore.self) private var store
    @State private var providers: [ProviderView] = []
    @State private var apiKey = ""
    @State private var signInURL: URL?
    @State private var server: LoopbackServer?
    @State private var confirmDelete = false
    @State private var exportFile: ExportFile?

    var body: some View {
        let _ = store.revision
        let s = store.model.settings()
        NavigationStack {
            Form {
                Section("Profile") {
                    NavigationLink("Body and goal") { ProfileForm(settings: s) }
                    NavigationLink("Daily targets") { TargetsForm(settings: s) }
                    Picker("Weight unit", selection: Binding(get: { s.weightUnit }, set: { store.model.setWeightUnit(unitId: $0); store.changed() })) {
                        Text("Kilograms").tag("KG"); Text("Pounds").tag("LB")
                    }
                    Picker("Cups and spoons", selection: Binding(get: { s.measurementSystem }, set: { store.model.setMeasurementSystem(systemId: $0); store.changed() })) {
                        Text("Metric").tag("METRIC"); Text("US").tag("US_CUSTOMARY")
                    }
                }
                Section {
                    NavigationLink("Diets and allergies") { PreferencesForm(settings: s) }
                } header: { Text("Food preferences") } footer: { Text("Recipes that don't fit are hidden from Discover.") }

                Section {
                    ForEach(providers, id: \.id) { p in
                        Button {
                            store.model.selectProvider(providerId: p.id)
                            store.changed()
                            Task { await reloadProviders() }
                        } label: {
                            HStack {
                                VStack(alignment: .leading) {
                                    Text(p.label).foregroundStyle(.primary)
                                    Text(providerDetail(p)).font(.caption).foregroundStyle(.secondary)
                                }
                                Spacer()
                                if p.selected { Image(systemName: "checkmark").accessibilityLabel("Selected") }
                            }
                        }
                        .disabled(!p.connected && !p.simulated)
                    }
                    Button("Connect ChatGPT") { Task { await signIn() } }
                    Button("Disconnect ChatGPT", role: .destructive) {
                        Task { @MainActor in
                            let message = (try? await store.model.signOutChatGpt()) ?? "Signed out"
                            store.changed(message)
                            await reloadProviders()
                        }
                    }
                    SecureField("OpenAI API key (billed to your API account)", text: $apiKey)
                    Button("Save API key") {
                        store.model.setApiKey(key: apiKey)
                        apiKey = ""
                        store.changed(s.hasApiKey ? "API key updated" : "API key saved")
                        Task { await reloadProviders() }
                    }
                    .disabled(apiKey.isEmpty)
                    Toggle("Use my API key if ChatGPT is unavailable", isOn: Binding(get: { s.allowApiKeyFallback }, set: { store.model.setApiKeyFallback(allowed: $0); store.changed() }))
                } header: { Text("AI provider") } footer: {
                    Text("The demo is the default and is always labelled as simulated. Signing in with ChatGPT lets eligible requests use your plan only if you grant plan usage. An API key is billed separately and is never used unless you choose it, or turn on the fallback above — never after a plan usage limit. AI only identifies food; Nouri calculates the nutrition.")
                }

                Section("Privacy") {
                    Toggle("Send meal photos to the AI provider", isOn: Binding(get: { s.photoConsent }, set: { store.model.setPhotoConsent(granted: $0); store.changed() }))
                    Toggle("Look up products online (Open Food Facts)", isOn: Binding(get: { s.onlineProductLookup }, set: { store.model.setOnlineProductLookup(on: $0); store.changed() }))
                    Button("Export diary (CSV)") { exportFile = ExportFile(text: store.model.exportCsv()) }
                    Button("Delete everything on this phone", role: .destructive) { confirmDelete = true }
                }
                Section {
                    Text("Nouri keeps your data on this phone. No accounts, analytics or ads. \(store.model.recipeCount) recipes; nutrition from USDA SR28 (public domain) and Open Food Facts (ODbL).")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Settings")
            .task { await reloadProviders() }
            .sheet(item: Binding(get: { signInURL.map(URLToken.init) }, set: { if $0 == nil { signInURL = nil } })) { token in
                SafariView(url: token.url).ignoresSafeArea()
            }
            .fileExporter(isPresented: Binding(get: { exportFile != nil }, set: { if !$0 { exportFile = nil } }), document: exportFile, contentType: .commaSeparatedText, defaultFilename: "nouri-diary.csv") { _ in exportFile = nil }
            .alert("Delete everything?", isPresented: $confirmDelete) {
                Button("Delete", role: .destructive) { store.model.deleteEverything(); store.changed("Deleted") }
                Button("Cancel", role: .cancel) {}
            } message: { Text("Your diary, weights, recipes, settings, API key and sign-in are removed from this phone. This can't be undone.") }
        }
    }

    private func providerDetail(_ p: ProviderView) -> String {
        var parts: [String] = []
        if p.simulated { parts.append("Simulated results") }
        else { parts.append(p.connected ? (p.accountLabel ?? "Connected") : "Not connected") }
        if p.billing == "API_BILLED" { parts.append("Billed to your OpenAI API account") }
        if p.billing == "PLAN_USAGE" { parts.append("Uses your ChatGPT plan limits") }
        return parts.joined(separator: " · ")
    }

    @MainActor
    private func reloadProviders() async {
        providers = (try? await store.model.providerStatuses()) ?? []
    }

    @MainActor
    private func signIn() async {
        let server = LoopbackServer()
        self.server = server
        do {
            let port = try await server.start()
            guard let urlString = try await store.model.beginChatGptSignIn(port: Int32(port)), let url = URL(string: urlString) else {
                store.show("Sign-in is unavailable right now")
                server.stop()
                return
            }
            server.onCallback = { callback in
                Task { @MainActor in
                    signInURL = nil
                    server.stop()
                    let error = try? await store.model.completeChatGptSignIn(callbackUrl: callback)
                    store.changed(error.flatMap { $0 } ?? "Connected to ChatGPT")
                    await reloadProviders()
                }
            }
            signInURL = url
        } catch {
            server.stop()
            store.show("Couldn't start sign-in: \(error.localizedDescription)")
        }
    }
}

struct URLToken: Identifiable {
    let url: URL
    var id: String { url.absoluteString }
}

struct SafariView: UIViewControllerRepresentable {
    let url: URL
    func makeUIViewController(context: Context) -> SFSafariViewController { SFSafariViewController(url: url) }
    func updateUIViewController(_ uiViewController: SFSafariViewController, context: Context) {}
}

struct ExportFile: FileDocument {
    static var readableContentTypes: [UTType] { [.commaSeparatedText] }
    let text: String
    init(text: String) { self.text = text }
    init(configuration: ReadConfiguration) throws { text = "" }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: Data(text.utf8)) }
}

private struct ProfileForm: View {
    @Environment(AppStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    let settings: SettingsView
    @State private var sex = "UNSPECIFIED"
    @State private var birthYear = 1990
    @State private var height = 170.0
    @State private var activity = "LIGHT"
    @State private var goal = "MAINTAIN"

    var body: some View {
        Form {
            Picker("Sex (for energy estimates)", selection: $sex) { ForEach(store.model.sexes, id: \.id) { Text($0.label).tag($0.id) } }
            Stepper("Born in \(String(birthYear))", value: $birthYear, in: 1920...2015)
            Stepper("Height \(Int(height)) cm", value: $height, in: 120...220)
            Picker("Activity", selection: $activity) { ForEach(store.model.activityLevels, id: \.id) { Text($0.label).tag($0.id) } }
            Picker("Goal", selection: $goal) { ForEach(store.model.goals, id: \.id) { Text($0.label).tag($0.id) } }
            Button("Save") {
                if let error = store.model.updateProfile(sexId: sex, birthYear: Int32(birthYear), heightCm: height, activityId: activity, goalId: goal) {
                    store.show(error)
                } else {
                    store.changed("Profile saved")
                    dismiss()
                }
            }
        }
        .navigationTitle("Body and goal")
        .onAppear {
            sex = settings.sex; activity = settings.activity; goal = settings.goal
            if let y = settings.birthYear.int { birthYear = y }
            if let h = settings.heightCm.double { height = h }
        }
    }
}

private struct TargetsForm: View {
    @Environment(AppStore.self) private var store
    let settings: SettingsView
    @State private var kcal = 2000.0
    @State private var protein = 100.0
    @State private var carbs = 220.0
    @State private var fat = 70.0

    var body: some View {
        Form {
            Section {
                Button("Suggest from my profile and weight") {
                    if let reason = store.model.suggestTargets() { store.show(reason) } else { store.changed("Targets suggested"); load() }
                }
                if let basis = store.model.settings().targetsBasis { Text(basis).font(.caption).foregroundStyle(.secondary) }
            } footer: { Text("Suggestions are estimates from standard equations, not medical advice.") }
            Section("Set my own") {
                field("Calories (kcal)", $kcal)
                field("Protein (g)", $protein)
                field("Carbs (g)", $carbs)
                field("Fat (g)", $fat)
                Button("Save") {
                    let notes = store.model.setManualTargets(kcal: kcal, proteinG: protein, carbsG: carbs, fatG: fat)
                    store.changed(notes.first ?? "Targets saved")
                }
                Button("Remove targets", role: .destructive) { store.model.clearTargets(); store.changed("Targets removed") }
            }
        }
        .navigationTitle("Daily targets")
        .onAppear(perform: load)
    }

    private func load() {
        let s = store.model.settings()
        if let v = s.targetKcal.double { kcal = v }
        if let v = s.targetProteinG.double { protein = v }
        if let v = s.targetCarbsG.double { carbs = v }
        if let v = s.targetFatG.double { fat = v }
    }

    private func field(_ label: String, _ value: Binding<Double>) -> some View {
        HStack { Text(label); Spacer(); TextField(label, value: value, format: .number).keyboardType(.decimalPad).multilineTextAlignment(.trailing).frame(width: 100) }
    }
}

private struct PreferencesForm: View {
    @Environment(AppStore.self) private var store
    let settings: SettingsView

    var body: some View {
        let _ = store.revision
        let s = store.model.settings()
        Form {
            Section("Required diets") {
                ForEach(store.model.diets, id: \.id) { d in
                    Toggle(d.label, isOn: Binding(get: { s.requiredDiets.contains(d.id) }, set: { on in
                        var set = Set(s.requiredDiets)
                        if on { set.insert(d.id) } else { set.remove(d.id) }
                        store.model.setRequiredDiets(dietIds: Array(set))
                        store.changed()
                    }))
                }
            }
            Section("Allergies") {
                ForEach(store.model.allergens, id: \.id) { a in
                    Toggle(a.label, isOn: Binding(get: { s.allergies.contains(a.id) }, set: { on in
                        var set = Set(s.allergies)
                        if on { set.insert(a.id) } else { set.remove(a.id) }
                        store.model.setAllergies(allergenIds: Array(set))
                        store.changed()
                    }))
                }
            }
        }
        .navigationTitle("Diets and allergies")
    }
}
