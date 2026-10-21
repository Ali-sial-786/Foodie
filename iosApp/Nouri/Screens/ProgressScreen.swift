import SwiftUI
import Charts
import NouriShared

struct ProgressScreen: View {
    @Environment(AppStore.self) private var store
    @State private var weightText = ""

    var body: some View {
        let _ = store.revision
        let weights = store.model.weights()
        let intake = store.model.intake(days: 7)
        NavigationStack {
            List {
                Section("Weight") {
                    if weights.points.count >= 2 {
                        Chart {
                            ForEach(weights.points, id: \.dateIso) { p in
                                PointMark(x: .value("Date", date(p.dateIso)), y: .value("Scale", p.scale))
                                    .foregroundStyle(.secondary).symbolSize(20)
                                LineMark(x: .value("Date", date(p.dateIso)), y: .value("Trend", p.trend))
                            }
                        }
                        .chartYScale(domain: .automatic(includesZero: false))
                        .frame(height: 180)
                        .accessibilityLabel("Weight chart: dots are weigh-ins, the line is the smoothed trend")
                    } else {
                        Text("Log your weight on two or more days to see a trend.").font(.caption).foregroundStyle(.secondary)
                    }
                    if let latest = weights.latestText { Text("Latest: \(latest)") }
                    if let rate = weights.rateText { Text(rate).font(.caption).foregroundStyle(.secondary) }
                    HStack {
                        TextField("Weight (\(weights.unit))", text: $weightText).keyboardType(.decimalPad)
                        Button("Log") {
                            guard let value = Double(weightText.replacingOccurrences(of: ",", with: ".")) else { return }
                            if let error = store.model.logWeight(value: value, dateIso: store.model.todayIso()) {
                                store.show(error)
                            } else {
                                weightText = ""
                                store.changed("Weight logged")
                            }
                        }
                        .disabled(weightText.isEmpty)
                    }
                }
                Section("Calories this week") {
                    Chart {
                        ForEach(intake, id: \.dateIso) { d in
                            BarMark(x: .value("Day", d.label), y: .value("kcal", d.kcal))
                        }
                        if let target = intake.first?.targetKcal.double {
                            RuleMark(y: .value("Target", target)).lineStyle(StrokeStyle(dash: [4]))
                                .annotation(position: .top, alignment: .leading) { Text("Target").font(.caption2) }
                        }
                    }
                    .frame(height: 160)
                    .accessibilityLabel("Calories eaten on each of the last seven days")
                }
                if !weights.entries.isEmpty {
                    Section("Weigh-ins") {
                        ForEach(weights.entries, id: \.id) { e in
                            HStack { Text(e.dateIso); Spacer(); Text(e.valueText).monospacedDigit() }
                                .swipeActions {
                                    Button(role: .destructive) {
                                        store.model.deleteWeight(id: e.id)
                                        store.changed()
                                    } label: { Label("Delete", systemImage: "trash") }
                                }
                        }
                    }
                }
            }
            .navigationTitle("Progress")
        }
    }

    private func date(_ iso: String) -> Date {
        let f = DateFormatter()
        f.calendar = Calendar(identifier: .gregorian)
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        return f.date(from: iso) ?? Date()
    }
}
