import SwiftUI
import NouriShared

struct OnboardingScreen: View {
    @Environment(AppStore.self) private var store

    var body: some View {
        VStack(spacing: 24) {
            Spacer()
            Image(systemName: "leaf.circle.fill").font(.system(size: 72)).foregroundStyle(Color.accentColor)
            Text("Welcome to Nouri").font(.largeTitle.bold())
            VStack(alignment: .leading, spacing: 12) {
                Label("Log meals by photo, barcode, search or by describing them", systemImage: "camera")
                Label("\(store.model.recipeCount) recipes from cuisines worldwide", systemImage: "fork.knife")
                Label("Your data stays on this phone", systemImage: "lock")
                Label("Starts in demo mode: AI results are simulated until you choose a provider", systemImage: "theatermasks")
            }
            .font(.subheadline)
            Spacer()
            Button {
                store.model.completeOnboarding()
                store.changed()
            } label: {
                Text("Get started").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent).controlSize(.large)
            Text("You can set your profile, targets and AI provider in Settings.").font(.caption).foregroundStyle(.secondary)
        }
        .padding(24)
    }
}
