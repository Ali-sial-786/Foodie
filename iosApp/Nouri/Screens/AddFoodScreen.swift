import SwiftUI
import NouriShared

/// Search, describe in words, quick add, photo scan or barcode — all logged to `dateIso`.
struct AddFoodScreen: View {
    @Environment(AppStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    let dateIso: String
    @State private var mode = 0
    @State private var mealId = ""

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Picker("How", selection: $mode) {
                    Text("Search").tag(0)
                    Text("Describe").tag(1)
                    Text("Scan").tag(2)
                    Text("Quick").tag(3)
                }
                .pickerStyle(.segmented)
                .padding(.horizontal)
                Form {
                    Section { MealPicker(mealId: $mealId) }
                    switch mode {
                    case 0: SearchFoodSection(dateIso: dateIso, mealId: mealId)
                    case 1: DescribeSection(dateIso: dateIso, mealId: mealId)
                    case 2: ScanSection(dateIso: dateIso, mealId: mealId)
                    default: QuickAddSection(dateIso: dateIso, mealId: mealId) { dismiss() }
                    }
                }
            }
            .navigationTitle("Add food")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } } }
            .onAppear { if mealId.isEmpty { mealId = store.model.suggestedMeal() } }
        }
    }
}

private struct SearchFoodSection: View {
    @Environment(AppStore.self) private var store
    let dateIso: String
    let mealId: String
    @State private var query = ""
    @State private var hits: [FoodHitView] = []
    @State private var chosen: FoodHitView?

    var body: some View {
        Section {
            TextField("Search foods, dishes, ingredients", text: $query)
                .textInputAutocapitalization(.never)
                .onChange(of: query) { _, q in hits = q.count >= 2 ? store.model.search(query: q) : [] }
        }
        Section {
            ForEach(hits, id: \.token) { hit in
                Button { chosen = hit } label: {
                    VStack(alignment: .leading) {
                        Text(hit.title).foregroundStyle(.primary)
                        Text("\(hit.subtitle) · \(hit.kcalPer100gText)").font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
        }
        .sheet(item: Binding(get: { chosen.map(Token.init) }, set: { chosen = $0?.hit })) { token in
            FoodLogSheet(hit: token.hit, dateIso: dateIso, mealId: mealId)
        }
    }
}

struct Token: Identifiable {
    let hit: FoodHitView
    var id: String { hit.token }
}

/// Amount and unit for one food, with live nutrition, then log.
struct FoodLogSheet: View {
    @Environment(AppStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    let hit: FoodHitView
    let dateIso: String
    let mealId: String
    @State private var amount = 1.0
    @State private var option: Int32 = 0
    @State private var duplicate: String?

    var body: some View {
        let options = store.model.portionOptions(token: hit.token)
        let preview = store.model.previewFood(token: hit.token, amount: amount, optionIndex: option)
        NavigationStack {
            Form {
                Section(hit.subtitle) {
                    Picker("Unit", selection: $option) {
                        ForEach(options, id: \.index) { o in Text(o.label).tag(o.index) }
                    }
                    .onChange(of: option) { _, new in
                        if let o = options.first(where: { $0.index == new }) { amount = o.defaultAmount }
                    }
                    HStack {
                        Text("Amount")
                        Spacer()
                        TextField("Amount", value: $amount, format: .number)
                            .keyboardType(.decimalPad).multilineTextAlignment(.trailing).frame(width: 100)
                    }
                }
                Section("Nutrition") {
                    MacroLine(nutrition: preview.nutrition)
                    if let grams = preview.gramsText { Text(grams).font(.caption).foregroundStyle(.secondary) }
                    if preview.isEstimate, let range = preview.rangeText {
                        Label("Estimate: \(range)", systemImage: "plusminus").font(.caption)
                    }
                    ForEach(preview.notes, id: \.self) { Text($0).font(.caption2).foregroundStyle(.secondary) }
                }
            }
            .navigationTitle(hit.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Log") { log(force: false) }.disabled(!preview.canLog)
                }
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            }
            .onAppear { if let first = options.first { option = first.index; amount = first.defaultAmount } }
            .alert("Log again?", isPresented: Binding(get: { duplicate != nil }, set: { if !$0 { duplicate = nil } })) {
                Button("Log again") { log(force: true) }
                Button("Cancel", role: .cancel) {}
            } message: { Text(duplicate ?? "") }
        }
    }

    private func log(force: Bool) {
        let outcome = store.model.logFood(token: hit.token, amount: amount, optionIndex: option, mealId: mealId, dateIso: dateIso, force: force)
        if outcome.logged {
            store.changed("Logged \(hit.title)")
            dismiss()
        } else if let message = outcome.duplicateMessage {
            duplicate = message
        } else {
            store.show(outcome.error ?? "Not logged")
        }
    }
}

private struct DescribeSection: View {
    @Environment(AppStore.self) private var store
    let dateIso: String
    let mealId: String
    @State private var text = ""
    @State private var result: DescribeResultView?
    @State private var working = false

    var body: some View {
        Section {
            TextField("e.g. 2 rotis, a bowl of dal and a mango lassi", text: $text, axis: .vertical).lineLimit(2...5)
            Button("Read on my phone") { result = store.model.describe(text: text) }.disabled(text.isEmpty)
            Button(working ? "Interpreting…" : "Interpret with AI") {
                working = true
                Task { @MainActor in
                    result = try? await store.model.describeWithAi(text: text)
                    working = false
                }
            }
            .disabled(text.isEmpty || working)
        } footer: {
            Text("“Read on my phone” works offline. “Interpret with AI” sends only this text to the provider you chose in Settings; Nouri still calculates the nutrition.")
        }
        if let result {
            Section("What Nouri understood") {
                if result.simulated { SimulatedBanner() }
                if let error = result.error { Label(error, systemImage: "exclamationmark.triangle").foregroundStyle(.red) }
                ForEach(Array(result.items.enumerated()), id: \.offset) { _, item in
                    VStack(alignment: .leading, spacing: 2) {
                        HStack {
                            Image(systemName: item.resolved ? "checkmark.circle" : "questionmark.circle")
                                .accessibilityLabel(item.resolved ? "Recognised" : "Not recognised")
                            Text(item.name)
                            Spacer()
                            Text(item.nutrition.kcalText).monospacedDigit()
                        }
                        if !item.amountText.isEmpty { Text(item.amountText).font(.caption).foregroundStyle(.secondary) }
                        ForEach(item.notes, id: \.self) { Text($0).font(.caption2).foregroundStyle(.secondary) }
                    }
                }
                MacroLine(nutrition: result.total)
                Button("Log these") {
                    let outcome = store.model.logDescribed(mealId: mealId, dateIso: dateIso, force: true)
                    store.changed(outcome.logged ? "Logged \(outcome.count) item(s)" : outcome.error ?? "Not logged")
                    if outcome.logged { self.result = nil; text = "" }
                }
                .disabled(!result.items.contains(where: { $0.resolved }))
            }
        }
    }
}

private struct QuickAddSection: View {
    @Environment(AppStore.self) private var store
    let dateIso: String
    let mealId: String
    let onDone: () -> Void
    @State private var name = ""
    @State private var kcal: Double?
    @State private var protein: Double?
    @State private var carbs: Double?
    @State private var fat: Double?

    var body: some View {
        Section {
            TextField("Name (optional)", text: $name)
            number("Calories (kcal)", $kcal)
            number("Protein (g)", $protein)
            number("Carbs (g)", $carbs)
            number("Fat (g)", $fat)
            Button("Log") {
                let outcome = store.model.quickAdd(name: name, kcal: kcal ?? 0, proteinG: protein ?? 0, carbsG: carbs ?? 0, fatG: fat ?? 0, mealId: mealId, dateIso: dateIso)
                store.changed(outcome.logged ? "Logged" : outcome.error ?? "Not logged")
                if outcome.logged { onDone() }
            }
            .disabled(kcal == nil)
        } footer: { Text("For when you already know the numbers, e.g. from a menu.") }
    }

    private func number(_ label: String, _ value: Binding<Double?>) -> some View {
        HStack {
            Text(label)
            Spacer()
            TextField("0", value: value, format: .number).keyboardType(.decimalPad).multilineTextAlignment(.trailing).frame(width: 100)
        }
    }
}
