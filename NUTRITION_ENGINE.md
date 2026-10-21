# Nutrition engine

All nutrition numbers in Nouri are calculated by deterministic, unit-tested code in `:shared`. AI providers only identify foods and estimate portion weights. **They never supply calories or nutrients.**

## Building blocks

| Concept | Where | Notes |
|---|---|---|
| `Nutrients` | `model/Nutrients.kt` | Energy, protein, total carbohydrate (includes fibre, matching USDA/US/Canada) and fat are always present. Fibre, sugar, sodium and saturated fat are `null` when unknown, which is not the same as zero. |
| `FoodReference` | `fooddb/FoodReference.kt` | Nutrients per 100 g of edible portion, plus density, household portions, refuse fraction, serving size, source and data quality. |
| `CanonicalIngredient` | `ontology/` | ~385 ingredients with raw and cooked USDA references, regional aliases, edible fraction, cooking yield, allergens, halal status, substitutions. |
| `DishDefinition` | `dishes/` | ~85 typical dish compositions with a batch cooked weight. Per-100 g values are calculated from the composition. |

## Units and conversions (`units/Units.kt`)

- **Mass** converts directly.
- **Volume** uses a food-specific density when known (from a USDA cup portion, for example). Otherwise it assumes water density, and the conversion is marked with that assumption.
- **Cups and spoons depend on region.** Metric cups are 250 mL; US customary cups are 236.6 mL. The user's `MeasurementSystem` decides which applies.
- **Counts** ("2 rotis", "1 medium onion") use food-specific portions. Generic fallbacks (handful 30 g, bowl 300 g, plate 400 g, scoop 30 g) are labelled assumptions.
- Every non-exact `GramConversion` carries a plain-language `assumption` that the UI shows. The converter never guesses silently.

## Recipe calculation (`nutrition/NutritionEngine.kt`)

For each ingredient line:

1. Resolve the ingredient: explicit id → normaliser (exact, then core, contained and fuzzy matches) → food reference. Raw versus cooked follows the line (`measuredCooked`) and the reference state.
2. Convert the quantity to grams as written.
3. Apply the **edible fraction** (bone, peel, shell) unless the line was already weighed as eaten.
4. Apply the **consumed fraction**, for example only part of deep-frying oil being absorbed, or a marinade that is discarded.
5. Calculate nutrients from the per-100 g reference.

Each line ends `RESOLVED`, `ESTIMATED` (it involved an assumption), `UNQUANTIFIED` (for example "salt to taste"; contributes nothing and is not an error) or `UNRESOLVED` (left out of totals with a warning).

Recipe totals:

- **Cooked weight** is the measured yield if the user weighed the pot. Otherwise it is estimated, with a warning suggesting the pot be weighed: most ingredients keep **0.85** of their mass (default retention), meat uses its cooked yield (0.75), and dry rice, pasta and pulses count their dry mass plus the water they absorb (`cookedYield`). That absorbed water is often not listed (pasta boiled and drained, soaked chickpeas), so the pot gets whichever is larger: the absorbed water, or the listed water and stock × 0.85. Per-serving values never depend on this estimate; only per-gram portions do.
- Per serving = total ÷ servings. Per 100 g cooked = total × 100 ÷ cooked weight. Portions by weight use the cooked weight; portions by fraction use "half the pot".
- **Recipe confidence:**
  - LOW if an energy-dense line is unresolved or completeness is below 75%;
  - MODERATE if any line is estimated or unresolved;
  - HIGHER otherwise.
- **Scaling** (`RecipeScaler`) is not naive multiplication:
  - spices and salt scale sub-linearly (factor^0.85);
  - countable items round to sensible amounts;
  - times, temperatures and pans are kept, with notes explaining what to change (batch browning, check doneness).

## Meal photo estimates (`scan/MealEstimator.kt`)

1. **Identification** comes from the provider: dish candidates with likelihoods, plus components.
2. **Portion** priority: weighed > package serving > adjusted by the user > multi-angle > reference object > learned usual portion > photo estimate > typical serving.
3. **Matching** priority: past corrections > saved foods > dish reference > ingredient reference > database search.
4. **Deterministic nutrition:** grams × reference per 100 g. Restaurant preparation can add an estimated amount of cooking fat, and the note says how much.
5. **Uncertainty:**
   - Each item has a range built from portion spread and preparation variability.
   - Independent item ranges combine by **root-sum-square**, not a straight sum, so a meal's range is narrower than the sum of its items' ranges.
   - The confidence label is the weakest of identification, portion and data confidence.
6. **Corrections** (`ScanEdit`) recalculate everything deterministically. When a meal is logged, corrections are remembered in `CorrectionMemory`: renames, and usual portions after at least two logs. This is on-device context, not model training.

Confidence labels are deliberately coarse: **Higher**, **Moderate** and **Low** confidence. Model probabilities are not calibrated, so they are never shown as percentages.

## Rounding (`nutrition/Rounding.kt`)

Calculations always use unrounded values. Rounding happens only for display, so a sum of rounded items can differ slightly from the rounded total.

| `Precision` | Used for | Energy | Macros |
|---|---|---|---|
| `MEASURED` | Weighed food, label values, user-entered | 1 kcal | 0.1 g below 10 g, else 1 g |
| `CALCULATED` | Recipes, reference foods | 5 kcal | 1 g |
| `ESTIMATED` | Photo and generic-portion estimates | 10 kcal, shown with a range | 1 g |

Sodium rounds to 5 mg below 140 mg and to 10 mg above. `NutritionRounding.UsLabel` implements US FDA label rounding (21 CFR 101.9), used to compare against printed labels.

## Snapshots and provenance

When an entry is logged, its nutrients, grams, provenance (source, reference id, detail, dataset version), confidence, precision, energy range and notes are **frozen** in a `NutritionSnapshot`. Later changes to reference data never rewrite history. Editing an entry rescales the frozen per-gram values and records a new capture time.

Source authority, from highest to lowest:

1. manufacturer label
2. user-entered
3. saved recipe
4. verified database (USDA)
5. branded database (Open Food Facts)
6. reference recipe
7. AI-assisted estimate
8. approximate fallback

## Known limitations

- SR28 is superseded by SR Legacy and FoodData Central. Migration is planned; see [DATA_SOURCES.md](DATA_SOURCES.md).
- Dish compositions are typical recipes. Real dishes vary a lot, which is why dishes always carry a variability note and an estimate range.
- Fibre, sugar, sodium and saturated fat are only as complete as their sources.
