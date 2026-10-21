import SwiftUI
import NouriShared

struct DiaryScreen: View {
    @Environment(AppStore.self) private var store
    let onAdd: () -> Void

    var body: some View {
        let _ = store.revision
        let day = store.model.day(dateIso: store.selectedDate)
        NavigationStack {
            List {
                Section {
                    HStack {
                        Button { store.selectedDate = store.model.shiftDate(dateIso: store.selectedDate, days: -1) } label: {
                            Image(systemName: "chevron.left")
                        }
                        .accessibilityLabel("Previous day")
                        Spacer()
                        VStack {
                            Text(day.title).font(.headline)
                            Text("\(day.consumed.kcalText)\(day.targetKcal.double.map { " of \(Int($0)) kcal" } ?? "")")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Button { store.selectedDate = store.model.shiftDate(dateIso: store.selectedDate, days: 1) } label: {
                            Image(systemName: "chevron.right")
                        }
                        .accessibilityLabel("Next day")
                    }
                    .buttonStyle(.borderless)
                }
                ForEach(day.meals, id: \.mealId) { meal in
                    Section {
                        if meal.entries.isEmpty {
                            Text("Nothing logged").foregroundStyle(.secondary).font(.subheadline)
                        }
                        ForEach(meal.entries, id: \.id) { entry in
                            EntryRow(entry: entry)
                                .swipeActions {
                                    Button(role: .destructive) {
                                        if store.model.deleteEntry(id: entry.id) { store.changed("Deleted \(entry.name)") }
                                    } label: { Label("Delete", systemImage: "trash") }
                                }
                        }
                    } header: {
                        HStack {
                            Text(meal.label)
                            Spacer()
                            if !meal.entries.isEmpty { Text(meal.total.kcalText).monospacedDigit() }
                        }
                    }
                }
            }
            .navigationTitle("Diary")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Undo") { if store.model.undoDelete() { store.changed("Restored") } }
                        .accessibilityHint("Restores the last deleted entry")
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button(action: onAdd) { Image(systemName: "plus") }.accessibilityLabel("Add food")
                }
            }
        }
    }
}

struct EntryRow: View {
    let entry: EntryView

    var body: some View {
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.name)
                Text(entry.detail).font(.caption).foregroundStyle(.secondary)
                if entry.isEstimate {
                    Label(entry.rangeText ?? "Estimate", systemImage: "plusminus").font(.caption2).foregroundStyle(.secondary)
                }
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 2) {
                Text(entry.nutrition.kcalText).monospacedDigit()
                Text(entry.confidenceLabel).font(.caption2).foregroundStyle(.secondary)
            }
        }
        .accessibilityElement(children: .combine)
    }
}
