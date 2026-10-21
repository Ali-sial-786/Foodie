import SwiftUI
import NouriShared

/// Brief confirmation messages ("Logged 2 items").
struct ToastView: View {
    @Environment(AppStore.self) private var store

    var body: some View {
        if let message = store.toast {
            Text(message)
                .font(.subheadline)
                .padding(.horizontal, 16).padding(.vertical, 10)
                .background(.thinMaterial, in: Capsule())
                .padding(.bottom, 64)
                .transition(.move(edge: .bottom).combined(with: .opacity))
                .accessibilityAddTraits(.isStaticText)
        }
    }
}

/// Calories eaten against the target, with the number in the middle (never colour alone).
struct CalorieRing: View {
    let consumed: Double
    let target: Double?

    var body: some View {
        let progress = target.map { $0 > 0 ? min(consumed / $0, 1.5) : 0 } ?? 0
        ZStack {
            Circle().stroke(Color.secondary.opacity(0.2), lineWidth: 14)
            Circle()
                .trim(from: 0, to: min(progress, 1))
                .stroke(progress > 1 ? Color.orange : Color.accentColor, style: StrokeStyle(lineWidth: 14, lineCap: .round))
                .rotationEffect(.degrees(-90))
            VStack(spacing: 2) {
                Text("\(Int(consumed.rounded()))").font(.title.bold()).monospacedDigit()
                Text(target.map { "of \(Int($0.rounded())) kcal" } ?? "kcal eaten").font(.caption).foregroundStyle(.secondary)
                if let target, consumed > target {
                    Label("Over target", systemImage: "exclamationmark.triangle").font(.caption2).foregroundStyle(.orange)
                }
            }
        }
        .frame(width: 150, height: 150)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(target.map { "\(Int(consumed)) of \(Int($0)) kilocalories eaten" } ?? "\(Int(consumed)) kilocalories eaten")
    }
}

/// Difficulty as one to three bars, always with its name.
struct DifficultyBars: View {
    let label: String
    let level: Int32

    var body: some View {
        HStack(spacing: 4) {
            HStack(alignment: .bottom, spacing: 2) {
                ForEach(0..<3, id: \.self) { i in
                    RoundedRectangle(cornerRadius: 1)
                        .fill(i < Int(level) ? Color.primary : Color.secondary.opacity(0.3))
                        .frame(width: 4, height: CGFloat(6 + i * 3))
                }
            }
            Text(label).font(.caption)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Difficulty: \(label)")
    }
}

/// The user's own rating, e.g. "★ 4/5".
struct RatingLabel: View {
    let rating: Int

    var body: some View {
        Label("\(rating)/5", systemImage: "star.fill")
            .font(.caption)
            .labelStyle(.titleAndIcon)
            .accessibilityLabel("Your rating: \(rating) out of 5")
    }
}

/// Five stars; tapping the current rating again clears it.
struct RatingStars: View {
    let rating: Int?
    let onRate: (Int?) -> Void

    var body: some View {
        HStack(spacing: 4) {
            ForEach(1...5, id: \.self) { n in
                let on = (rating ?? 0) >= n
                Button {
                    onRate(rating == n ? nil : n)
                } label: {
                    Image(systemName: on ? "star.fill" : "star").font(.title3)
                }
                .buttonStyle(.plain)
                .foregroundStyle(on ? Color.orange : Color.secondary)
                .accessibilityLabel(rating == n ? "Rated \(n) of 5. Double-tap to clear" : "Rate \(n) of 5")
            }
        }
    }
}

struct MacroLine: View {
    let nutrition: NutritionView

    var body: some View {
        HStack {
            Text(nutrition.kcalText).font(.subheadline.bold())
            Spacer()
            Text(nutrition.macrosText).font(.caption).foregroundStyle(.secondary)
        }
    }
}

/// Shown on every demo result so simulated numbers are never mistaken for real ones.
struct SimulatedBanner: View {
    var body: some View {
        Label("Demo result — simulated, not a real analysis", systemImage: "theatermasks")
            .font(.footnote.bold())
            .padding(10)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.purple.opacity(0.15), in: RoundedRectangle(cornerRadius: 10))
    }
}

struct MealPicker: View {
    @Environment(AppStore.self) private var store
    @Binding var mealId: String

    var body: some View {
        Picker("Meal", selection: $mealId) {
            ForEach(store.model.meals, id: \.id) { meal in Text(meal.label).tag(meal.id) }
        }
    }
}

extension Optional where Wrapped == KotlinDouble {
    var double: Double? { self?.doubleValue }
}

extension Optional where Wrapped == KotlinInt {
    var int: Int? { self.map { Int($0.int32Value) } }
}
