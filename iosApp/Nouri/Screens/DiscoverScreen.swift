import SwiftUI
import NouriShared

/// Search and filter every recipe; versions of a dish are one card with a picker.
struct DiscoverScreen: View {
    @Environment(AppStore.self) private var store
    @State private var query = ""
    @State private var sortId = "BEST_MATCH"
    @State private var regionId: String?
    @State private var cuisineId: String?
    @State private var courseId: String?
    @State private var dietIds: Set<String> = []
    @State private var maxMinutes = 0
    @State private var maxKcal = 0
    @State private var easyOnly = false
    @State private var highProtein = false
    @State private var favouritesOnly = false
    @State private var showCuisines = false
    @State private var versionsOf: RecipeCardView?
    @State private var path: [String] = []

    private var activeFilters: Int {
        [regionId, cuisineId, courseId].compactMap { $0 }.count + dietIds.count + (maxMinutes > 0 ? 1 : 0) + (maxKcal > 0 ? 1 : 0) +
            [easyOnly, highProtein, favouritesOnly].filter { $0 }.count
    }

    var body: some View {
        let _ = store.revision
        let result = store.model.searchRecipes(
            query: query, sortId: sortId, regionId: regionId, cuisineId: cuisineId, courseId: courseId, dietIds: Array(dietIds),
            maxMinutes: Int32(maxMinutes), maxKcal: Int32(maxKcal), easyOnly: easyOnly, highProtein: highProtein, favouritesOnly: favouritesOnly
        )
        NavigationStack(path: $path) {
            List {
                Section {
                    ScrollView(.horizontal, showsIndicators: false) { filterChips(result) }
                    HStack {
                        Text("\(result.dishes.count) dishes · \(result.matchingRecipes) of \(result.total) recipes")
                            .font(.caption).foregroundStyle(.secondary)
                        Spacer()
                        if activeFilters > 0 { Button("Clear filters (\(activeFilters))") { clear() }.font(.caption) }
                    }
                    if result.hiddenByPreferences > 0 {
                        Text("\(result.hiddenByPreferences) hidden by your food preferences (Settings › Food preferences).")
                            .font(.caption2).foregroundStyle(.secondary)
                    }
                }
                if result.dishes.isEmpty {
                    ContentUnavailableView("No recipes match", systemImage: "fork.knife", description: Text("Try removing a filter."))
                }
                ForEach(result.dishes, id: \.id) { card in
                    NavigationLink(value: card.id) {
                        RecipeRow(card: card) { versionsOf = card }
                    }
                }
            }
            .listStyle(.insetGrouped)
            .searchable(text: $query, prompt: "Recipes, cuisines, ingredients")
            .navigationTitle("Discover")
            .navigationDestination(for: String.self) { id in RecipeDetailScreen(recipeId: id) }
            .sheet(isPresented: $showCuisines) {
                CuisinePicker(regions: result.regions, regionId: $regionId, cuisineId: $cuisineId)
            }
            .sheet(item: Binding(get: { versionsOf.map(CardToken.init) }, set: { versionsOf = $0?.card })) { token in
                VersionsSheet(card: token.card) { id in
                    versionsOf = nil
                    path.append(id)
                }
            }
        }
    }

    @ViewBuilder
    private func filterChips(_ result: RecipeSearchView) -> some View {
        HStack(spacing: 8) {
            Menu {
                ForEach(store.model.sorts, id: \.id) { o in Button(o.label) { sortId = o.id } }
            } label: { chip("Sort: \(store.model.sorts.first { $0.id == sortId }?.label ?? "")", on: sortId != "BEST_MATCH", icon: "arrow.up.arrow.down") }
            Button { showCuisines = true } label: {
                chip(cuisineName(result) ?? regionName(result) ?? "Any cuisine", on: cuisineId != nil || regionId != nil, icon: "globe")
            }
            Menu {
                Button("Any course") { courseId = nil }
                ForEach(store.model.courses, id: \.id) { o in Button(o.label) { courseId = o.id } }
            } label: { chip(store.model.courses.first { $0.id == courseId }?.label ?? "Any course", on: courseId != nil, icon: "fork.knife") }
            Menu {
                ForEach(store.model.diets, id: \.id) { o in
                    Button { if dietIds.contains(o.id) { dietIds.remove(o.id) } else { dietIds.insert(o.id) } } label: {
                        if dietIds.contains(o.id) { Label(o.label, systemImage: "checkmark") } else { Text(o.label) }
                    }
                }
            } label: {
                chip(dietIds.isEmpty ? "Any diet" : store.model.diets.filter { dietIds.contains($0.id) }.map(\.label).joined(separator: ", "), on: !dietIds.isEmpty, icon: "leaf")
            }
            Menu {
                Button("Any time") { maxMinutes = 0 }
                ForEach([20, 30, 45, 60, 90], id: \.self) { m in Button("\(m) minutes or less") { maxMinutes = m } }
            } label: { chip(maxMinutes > 0 ? "≤ \(maxMinutes) min" : "Any time", on: maxMinutes > 0, icon: "timer") }
            Menu {
                Button("Any calories") { maxKcal = 0 }
                ForEach([300, 450, 600, 800], id: \.self) { k in Button("\(k) kcal or less per serving") { maxKcal = k } }
            } label: { chip(maxKcal > 0 ? "≤ \(maxKcal) kcal" : "Any calories", on: maxKcal > 0, icon: "flame") }
            Button { easyOnly.toggle() } label: { chip("Easy", on: easyOnly, icon: nil) }
            Button { highProtein.toggle() } label: { chip("High protein", on: highProtein, icon: nil) }
            Button { favouritesOnly.toggle() } label: { chip("Favourites", on: favouritesOnly, icon: "heart") }
        }
        .buttonStyle(.plain)
        .padding(.vertical, 2)
    }

    private func chip(_ text: String, on: Bool, icon: String?) -> some View {
        HStack(spacing: 4) {
            if on { Image(systemName: "checkmark").font(.caption2) }
            if let icon { Image(systemName: icon).font(.caption) }
            Text(text).font(.caption).lineLimit(1)
        }
        .padding(.horizontal, 10).padding(.vertical, 6)
        .background(on ? Color.accentColor.opacity(0.2) : Color.secondary.opacity(0.12), in: Capsule())
        .accessibilityAddTraits(on ? .isSelected : [])
    }

    private func cuisineName(_ r: RecipeSearchView) -> String? {
        guard let cuisineId else { return nil }
        return r.regions.flatMap(\.cuisines).first { $0.id == cuisineId }?.name
    }

    private func regionName(_ r: RecipeSearchView) -> String? {
        guard let regionId else { return nil }
        return r.regions.first { $0.regionId == regionId }?.label
    }

    private func clear() {
        regionId = nil; cuisineId = nil; courseId = nil; dietIds = []; maxMinutes = 0; maxKcal = 0
        easyOnly = false; highProtein = false; favouritesOnly = false
    }
}

struct CardToken: Identifiable {
    let card: RecipeCardView
    var id: String { card.id }
}

/// One recipe in a list: name, course and cuisine, time, calories, difficulty, rating and versions.
struct RecipeRow: View {
    let card: RecipeCardView
    var onVersions: (() -> Void)? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .top) {
                Text(card.name).font(.headline).lineLimit(2)
                Spacer()
                if card.favourite { Image(systemName: "heart.fill").foregroundStyle(.pink).accessibilityLabel("Favourite") }
            }
            Text(card.subtitle).font(.caption).foregroundStyle(.secondary)
            Text(([minutes(Int(card.minutes)), card.nutrition.kcalText, "\(Int(card.nutrition.proteinG.rounded())) g protein"] + (card.dietLabel.map { [$0] } ?? [])).joined(separator: " · "))
                .font(.caption)
            HStack(spacing: 12) {
                DifficultyBars(label: card.difficulty, level: card.difficultyLevel)
                if let rating = card.rating.int { RatingLabel(rating: rating) }
                if card.cookedCount > 0 { Label("Cooked \(card.cookedCount)×", systemImage: "frying.pan").font(.caption) }
                Spacer()
                if card.versionCount > 1, let onVersions {
                    Button(action: onVersions) {
                        Label("\(card.versionCount) versions", systemImage: "square.stack").font(.caption)
                    }
                    .buttonStyle(.bordered).controlSize(.small)
                    .accessibilityLabel("\(card.versionCount) versions of this dish. Choose one")
                }
            }
            if !card.isTraditional { Text(card.originLabel).font(.caption2).foregroundStyle(.secondary) }
            if !card.reasons.isEmpty { Text("Why: " + card.reasons.joined(separator: "; ")).font(.caption2).foregroundStyle(.secondary) }
        }
        .padding(.vertical, 2)
    }
}

func minutes(_ m: Int) -> String { m < 60 ? "\(m) min" : "\(m / 60) h\(m % 60 > 0 ? " \(m % 60) min" : "")" }

struct VersionsSheet: View {
    let card: RecipeCardView
    let onPick: (String) -> Void

    var body: some View {
        NavigationStack {
            List(card.versions, id: \.id) { v in
                Button { onPick(v.id) } label: { VersionRow(version: v, current: false) }
            }
            .navigationTitle("\(card.versions.first?.name ?? card.name) · \(card.versionCount) versions")
            .navigationBarTitleDisplayMode(.inline)
        }
        .presentationDetents([.medium, .large])
    }
}

struct VersionRow: View {
    let version: RecipeVersionView
    let current: Bool

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(version.name).foregroundStyle(.primary)
                Text("\(version.label) · \(minutes(Int(version.minutes))) · \(version.kcalText)").font(.caption).foregroundStyle(.secondary)
                HStack {
                    DifficultyBars(label: version.difficulty, level: version.difficultyLevel)
                    if let r = version.rating.int { RatingLabel(rating: r) }
                }
            }
            Spacer()
            if current { Image(systemName: "checkmark.circle.fill").accessibilityLabel("Showing this version") }
        }
    }
}

struct CuisinePicker: View {
    let regions: [RegionGroupView]
    @Binding var regionId: String?
    @Binding var cuisineId: String?
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Button("Any cuisine (\(regions.reduce(0) { $0 + Int($1.count) }))") { regionId = nil; cuisineId = nil; dismiss() }
                ForEach(regions, id: \.regionId) { region in
                    Section {
                        Button("All \(region.label) (\(region.count))") { regionId = region.regionId; cuisineId = nil; dismiss() }
                        ForEach(region.cuisines, id: \.id) { c in
                            Button { cuisineId = c.id; regionId = nil; dismiss() } label: {
                                HStack {
                                    Text(c.name)
                                    Spacer()
                                    Text("\(c.count)").foregroundStyle(.secondary)
                                    if cuisineId == c.id { Image(systemName: "checkmark") }
                                }
                            }
                        }
                    } header: { Text(region.label) }
                }
            }
            .navigationTitle("Cuisines")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}
