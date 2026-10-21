import XCTest
import NouriShared
@testable import Nouri

/// Runs the shared app model inside the iOS app on the simulator, with in-memory storage.
@MainActor
final class NouriTests: XCTestCase {
    private func makeModel() -> NouriAppModel {
        let library = Bundle.main.url(forResource: "library", withExtension: "json").flatMap { try? String(contentsOf: $0, encoding: .utf8) }
        let tsv = Bundle.main.url(forResource: "usda_sr28", withExtension: "tsv").flatMap { try? String(contentsOf: $0, encoding: .utf8) }
        return IosPlatform.shared.makeOfflineModel(storage: MemoryTextStorage(), secrets: MemorySecretStore(), usdaTsv: tsv, libraryJson: library)
    }

    func testBundledRecipesLoad() {
        let model = makeModel()
        XCTAssertGreaterThan(model.recipeCount, 80, "the recipe library should be bundled with the app")
    }

    func testQuickAddAppearsInToday() {
        let model = makeModel()
        let today = model.todayIso()
        XCTAssertTrue(model.quickAdd(name: "Chai", kcal: 120, proteinG: 4, carbsG: 15, fatG: 5, mealId: "BREAKFAST", dateIso: today).logged)
        XCTAssertEqual(model.day(dateIso: today).consumed.kcal, 120, accuracy: 0.001)
    }

    func testSearchFindsUsdaFoods() {
        let model = makeModel()
        XCTAssertFalse(model.search(query: "lentils").isEmpty)
    }

    func testDiscoverFiltersAndVersions() {
        let model = makeModel()
        let vegan = model.searchRecipes(query: "", sortId: nil, regionId: nil, cuisineId: nil, courseId: nil, dietIds: ["VEGAN"],
                                        maxMinutes: 0, maxKcal: 0, easyOnly: false, highProtein: false, favouritesOnly: false)
        XCTAssertFalse(vegan.dishes.isEmpty)
        XCTAssertTrue(vegan.dishes.allSatisfy { $0.dietLabel == "Vegan" })
        let butter = model.searchRecipes(query: "butter chicken", sortId: nil, regionId: nil, cuisineId: nil, courseId: nil, dietIds: [],
                                         maxMinutes: 0, maxKcal: 0, easyOnly: false, highProtein: false, favouritesOnly: false)
        XCTAssertGreaterThanOrEqual(butter.dishes.first?.versionCount ?? 0, 2)
    }

    func testDemoScanIsSimulated() async throws {
        let model = makeModel()
        let image = UIGraphicsImageRenderer(size: CGSize(width: 40, height: 40)).image { ctx in
            UIColor.orange.setFill(); ctx.fill(CGRect(x: 0, y: 0, width: 40, height: 40))
        }
        let processed = try XCTUnwrap(ImageProcessing.process(image))
        let result = try await model.analyzePhoto(jpeg: processed.bytes, widthPx: processed.width, heightPx: processed.height, demoScenario: nil)
        XCTAssertNil(result.error)
        XCTAssertTrue(result.simulated)
    }

    func testFileStorageRoundTrip() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let storage = FileTextStorage(directory: dir)
        storage.write(name: "a.json", text: "{}")
        XCTAssertEqual(storage.read(name: "a.json"), "{}")
        XCTAssertEqual(storage.names(), ["a.json"])
        storage.delete(name: "a.json")
        XCTAssertNil(storage.read(name: "a.json"))
    }
}
