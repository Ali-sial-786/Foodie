import SwiftUI
import NouriShared

@main
struct NouriApp: App {
    @State private var store = AppStore.live()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(store)
        }
    }
}

/// Tabs mirror the Android app: Today, Diary, Discover, Progress, Settings, plus Add.
struct RootView: View {
    @Environment(AppStore.self) private var store
    @State private var tab = 0
    @State private var adding = false

    var body: some View {
        let _ = store.revision
        if !store.model.settings().onboarded {
            OnboardingScreen()
        } else {
            TabView(selection: $tab) {
                TodayScreen(onAdd: { adding = true })
                    .tabItem { Label("Today", systemImage: "house") }.tag(0)
                DiaryScreen(onAdd: { adding = true })
                    .tabItem { Label("Diary", systemImage: "book") }.tag(1)
                DiscoverScreen()
                    .tabItem { Label("Discover", systemImage: "fork.knife") }.tag(2)
                ProgressScreen()
                    .tabItem { Label("Progress", systemImage: "chart.line.uptrend.xyaxis") }.tag(3)
                SettingsScreen()
                    .tabItem { Label("Settings", systemImage: "gearshape") }.tag(4)
            }
            .sheet(isPresented: $adding) { AddFoodScreen(dateIso: store.selectedDate) }
            .overlay(alignment: .bottom) { ToastView() }
        }
    }
}
