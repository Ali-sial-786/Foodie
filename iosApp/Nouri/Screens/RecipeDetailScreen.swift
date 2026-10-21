import SwiftUI
import NouriShared

struct RecipeDetailScreen: View {
    @Environment(AppStore.self) private var store
    let recipeId: String
    @State private var servings: Int?
    @State private var logging = false
    @State private var currentId: String?

    var body: some View {
        let _ = store.revision
        let id = currentId ?? recipeId
        if let r = store.model.recipe(id: id, servings: Int32(servings ?? 0)) {
            content(r)
                .onAppear { if servings == nil { servings = Int(r.baseServings) } }
        } else {
            ContentUnavailableView("Recipe not found", systemImage: "questionmark")
        }
    }

    private func content(_ r: RecipeDetailView) -> some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: 6) {
                    Text(r.subtitle).font(.subheadline).foregroundStyle(.secondary)
                    HStack(spacing: 12) {
                        Label(r.minutesText, systemImage: "timer").font(.caption)
                        DifficultyBars(label: r.difficulty, level: r.difficultyLevel)
                        if let rating = r.rating.int { RatingLabel(rating: rating) }
                        if r.cookedCount > 0 { Label("Cooked \(r.cookedCount)×", systemImage: "frying.pan").font(.caption) }
                    }
                    Text(r.originLabel).font(.caption).foregroundStyle(.secondary)
                    if let summary = r.variantSummary { Text("Changes: \(summary)").font(.caption) }
                    if let attribution = r.attribution { Text("Source: \(attribution)").font(.caption).foregroundStyle(.secondary) }
                    if !r.about.isEmpty { Text(r.about).font(.body) }
                    HStack {
                        Text("Your rating").font(.subheadline)
                        RatingStars(rating: r.rating.int) { stars in
                            store.model.rateRecipe(id: r.id, stars: Int32(stars ?? 0))
                            store.changed()
                        }
                    }
                    Text("Ratings stay on this phone and help Nouri suggest recipes you like.").font(.caption2).foregroundStyle(.secondary)
                }
            }
            if !r.versions.isEmpty {
                Section("\(r.versions.count) versions of this dish") {
                    ForEach(r.versions, id: \.id) { v in
                        Button { currentId = v.id; servings = nil } label: { VersionRow(version: v, current: v.id == r.id) }
                    }
                }
            }
            Section {
                Button { logging = true } label: { Label("Log a portion", systemImage: "plus.circle") }
                Button {
                    store.model.markCooked(id: r.id)
                    store.changed("Marked as cooked")
                } label: { Label("Mark as cooked", systemImage: "frying.pan") }
            }
            Section("Nutrition per serving") {
                MacroLine(nutrition: r.perServing)
                Text("\(r.completenessText) · \(r.confidenceLabel)").font(.caption).foregroundStyle(.secondary)
                if !r.diets.isEmpty { Label(r.diets.joined(separator: " · "), systemImage: "leaf").font(.caption) }
                if !r.allergens.isEmpty { Label("Contains: \(r.allergens.joined(separator: ", "))", systemImage: "exclamationmark.triangle").font(.caption) }
                ForEach(r.halalNotes, id: \.self) { Text($0).font(.caption2).foregroundStyle(.secondary) }
            }
            Section("Ingredients") {
                Stepper("Servings: \(servings ?? Int(r.servings))", value: Binding(get: { servings ?? Int(r.servings) }, set: { servings = $0 }), in: 1...50)
                ForEach(r.scaleNotes, id: \.self) { Text($0).font(.caption2).foregroundStyle(.secondary) }
                ForEach(Array(r.ingredients.enumerated()), id: \.offset) { _, line in
                    VStack(alignment: .leading, spacing: 0) {
                        if let group = line.group { Text(group.capitalized).font(.caption.bold()).foregroundStyle(.secondary) }
                        Text("• \(line.text)").foregroundStyle(line.optional ? .secondary : .primary)
                    }
                }
            }
            if !r.equipment.isEmpty {
                Section("Equipment") { Text(r.equipment.joined(separator: " · ")).font(.subheadline) }
            }
            Section("Method") {
                ForEach(r.steps, id: \.number) { step in
                    HStack(alignment: .top, spacing: 10) {
                        Text("\(step.number).").font(.headline).frame(width: 26, alignment: .leading)
                        VStack(alignment: .leading, spacing: 4) {
                            Text(step.text)
                            if let timer = step.timerText { Label(timer, systemImage: "timer").font(.caption).foregroundStyle(.secondary) }
                        }
                    }
                    .accessibilityElement(children: .combine)
                }
            }
            if !r.safety.isEmpty {
                Section("Food safety & storage") { ForEach(r.safety, id: \.self) { Text($0).font(.caption) } }
            }
            if !r.notes.isEmpty {
                Section("Notes") { ForEach(r.notes, id: \.self) { Text($0).font(.caption) } }
            }
        }
        .navigationTitle(r.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    store.model.toggleFavourite(id: r.id)
                    store.changed()
                } label: { Image(systemName: r.favourite ? "heart.fill" : "heart") }
                .accessibilityLabel(r.favourite ? "Remove from favourites" : "Add to favourites")
            }
        }
        .sheet(isPresented: $logging) { LogRecipeSheet(recipe: r, servings: Int32(servings ?? Int(r.servings))) }
    }
}

private struct LogRecipeSheet: View {
    @Environment(AppStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    let recipe: RecipeDetailView
    let servings: Int32
    @State private var portions = 1.0
    @State private var mealId = ""
    @State private var duplicate: String?

    var body: some View {
        NavigationStack {
            Form {
                MealPicker(mealId: $mealId)
                Stepper("Servings eaten: \(portions.formatted())", value: $portions, in: 0.5...10, step: 0.5)
                Text("About \(Int((recipe.perServing.kcal * portions).rounded())) kcal").font(.headline)
            }
            .navigationTitle("Log \(recipe.name)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Log") { log(force: false) } }
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            }
            .onAppear { if mealId.isEmpty { mealId = store.model.suggestedMeal() } }
            .alert("Log again?", isPresented: Binding(get: { duplicate != nil }, set: { if !$0 { duplicate = nil } })) {
                Button("Log again") { log(force: true) }
                Button("Cancel", role: .cancel) {}
            } message: { Text(duplicate ?? "") }
        }
        .presentationDetents([.medium])
    }

    private func log(force: Bool) {
        let outcome = store.model.logRecipe(id: recipe.id, servings: servings, portionServings: portions, mealId: mealId, dateIso: store.selectedDate, force: force)
        if outcome.logged {
            store.changed("Logged \(recipe.name)")
            dismiss()
        } else if let message = outcome.duplicateMessage {
            duplicate = message
        } else {
            store.show(outcome.error ?? "Not logged")
        }
    }
}
