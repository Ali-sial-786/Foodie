import SwiftUI
import NouriShared

struct TodayScreen: View {
    @Environment(AppStore.self) private var store
    let onAdd: () -> Void

    var body: some View {
        let _ = store.revision
        let today = store.model.todayIso()
        let day = store.model.day(dateIso: today)
        NavigationStack {
            List {
                Section {
                    HStack(spacing: 20) {
                        CalorieRing(consumed: day.consumed.kcal, target: day.targetKcal.double)
                        VStack(alignment: .leading, spacing: 6) {
                            if let remaining = day.remainingKcal.double {
                                Text(remaining >= 0 ? "\(Int(remaining.rounded())) kcal left" : "\(Int((-remaining).rounded())) kcal over")
                                    .font(.headline)
                            } else {
                                Text("No daily target yet").font(.headline)
                                Text("Set one in Settings › Targets").font(.caption).foregroundStyle(.secondary)
                            }
                            Text(day.consumed.macrosText).font(.caption).foregroundStyle(.secondary)
                            if day.containsEstimates {
                                Label("Includes estimates", systemImage: "plusminus").font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                    .padding(.vertical, 6)
                    Button(action: {
                        store.selectedDate = today
                        onAdd()
                    }) {
                        Label("Add food", systemImage: "plus.circle.fill").font(.headline)
                    }
                }
                Section("Meals") {
                    ForEach(day.meals, id: \.mealId) { meal in
                        HStack {
                            Text(meal.label)
                            Spacer()
                            Text(meal.entries.isEmpty ? "—" : meal.total.kcalText).foregroundStyle(.secondary).monospacedDigit()
                        }
                    }
                }
                let recent = store.model.recentFoods(limit: 6)
                if !recent.isEmpty {
                    Section("Log again") {
                        ForEach(recent, id: \.id) { entry in
                            Button {
                                let outcome = store.model.relog(id: entry.id, mealId: store.model.suggestedMeal(), dateIso: today)
                                store.changed(outcome.logged ? "Logged \(entry.name)" : outcome.error ?? "Not logged")
                            } label: {
                                HStack {
                                    VStack(alignment: .leading) {
                                        Text(entry.name).foregroundStyle(.primary)
                                        Text(entry.detail).font(.caption).foregroundStyle(.secondary)
                                    }
                                    Spacer()
                                    Text(entry.nutrition.kcalText).font(.caption).foregroundStyle(.secondary)
                                }
                            }
                            .accessibilityHint("Logs this again now")
                        }
                    }
                }
                let ideas = store.model.recipeIdeas(limit: 4)
                if !ideas.isEmpty {
                    Section("Recipe ideas") {
                        ForEach(ideas, id: \.id) { card in
                            NavigationLink(value: card.id) { RecipeRow(card: card) }
                        }
                    }
                }
            }
            .navigationTitle("Today")
            .navigationDestination(for: String.self) { id in RecipeDetailScreen(recipeId: id) }
        }
    }
}
