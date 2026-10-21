# Recipe library

Nouri's bundled recipe library: reference recipes kept as data, one JSON file per group of cuisines. They ship inside the app, load the first time recipes are needed, and appear in Discover search and filters like the built-in recipes in `RecipeCatalog.kt`.

- **Source:** `data/recipes/*.json` (edit these).
- **Bundle:** `python3 scripts/build_recipe_library.py` validates every file and writes `app/src/main/assets/recipes/library.json`. Never edit the bundle by hand; CI runs `--check`.
- **Tests:** `RecipeLibraryTest` (shared `jvmTest`) decodes every file and checks the same rules with the real nutrition engine.
- **While writing:** `python3 scripts/build_recipe_library.py --format data/recipes/<file>.json` tidies the layout, and `python3 scripts/build_recipe_library.py --table <ingredient-table.json> --verbose data/recipes/<file>.json` checks one file, including estimated calories per serving. Export the table with `NOURI_EXPORT_INGREDIENTS=/tmp/ingredient-table.json ./gradlew :shared:jvmTest --tests "*IngredientTableExport*" --rerun` (re-export after changing the ingredient catalogue).

Recipes are Nouri's own write-ups of widely known home-style dishes (origin "Traditional (Nouri reference recipe)"). Do not copy text from cookbooks or websites, and never claim a published source. Nutrition is not stored: the engine calculates it from the ingredient lines, so amounts must be realistic.

## Format

```json
{
  "recipes": [
    {
      "id": "jollof_rice",
      "name": "Jollof rice",
      "cuisine": "nigerian",
      "region": "Yoruba",
      "course": "main",
      "servings": 6,
      "prep": 20,
      "cook": 60,
      "difficulty": "MEDIUM",
      "description": "Long-grain rice cooked in a smoky, peppery tomato base until every grain is coated and tender.",
      "equipment": ["Blender", "Heavy pot with a tight lid"],
      "tags": ["one-pot", "party"],
      "ingredients": [
        {"id": "bell_pepper", "q": 2, "u": "PIECE", "name": "Red bell peppers", "prep": "deseeded", "group": "Pepper base"},
        {"id": "rice_white", "q": 500, "u": "GRAM", "name": "Long-grain parboiled rice", "prep": "rinsed until the water runs clear", "group": "Rice"},
        {"id": "vegetable_oil", "q": 120, "u": "MILLILITRE", "name": "Vegetable oil", "eaten": 0.9},
        {"id": "salt", "name": "Salt", "taste": true},
        {"id": "white_wine", "q": 60, "u": "MILLILITRE", "name": "Dry white wine", "opt": true}
      ],
      "steps": [
        {"t": "Blend the peppers, tomatoes, onion and scotch bonnet with 120 ml water until completely smooth.", "min": 2},
        {"t": "Bake in the middle of the oven until golden and the base sounds hollow when tapped.", "min": 35, "c": 200}
      ],
      "storage": "@fridge3",
      "reheating": "@reheat",
      "safety": ["@rice"],
      "notes": ["Parboiled rice holds its shape best; basmati works but needs 20% less liquid."]
    }
  ]
}
```

| Key | Meaning |
|---|---|
| `id` | Unique, `snake_case`; must not clash with `RecipeCatalog.kt` or another file. |
| `cuisine` | An id from `CuisineCatalog` (`shared/.../recipes/Cuisine.kt`). `region` is free text (city, community or regional style). |
| `course` | One of `breakfast`, `main`, `soup`, `salad`, `side`, `snack`, `dessert`, `bread`, `drink`, `sauce`. |
| `prep`, `cook` | Minutes. `prep` is hands-on preparation (mention marinating or soaking in the steps and notes rather than adding hours here). |
| `difficulty` | `EASY`, `MEDIUM` or `INVOLVED`. |
| `tags` | lowercase-kebab extras such as `quick`, `one-pot`, `street-food`, `festive`, `ramadan`, `make-ahead`, `grill`, `baked`, `no-cook`, `spicy`, `budget`, `meal-prep`. Diet labels (`vegetarian`, `vegan`, `gluten-free`, `dairy-free`, `nut-free`, `halal`, `high-protein`, `lower-calorie`) are **calculated** from the ingredients — don't add them. |
| `ingredients[].id` | An ingredient id from `IngredientCatalog.kt`. Every line must calculate. |
| `q`, `u` | Amount and unit: a `QuantityUnit` name — `GRAM`, `KILOGRAM`, `MILLILITRE`, `LITRE`, `TEASPOON`, `TABLESPOON`, `CUP`, `PINCH`, `PIECE`, `SLICE`, `CLOVE`, `HANDFUL`, `BUNCH`, `CAN`, `STICK`, … Prefer grams for meat, fish, grains, flour, pasta, pulses, vegetables and fruit; spoons for spices and pastes; `PIECE`/`CLOVE` only when the ingredient defines a piece size. |
| `name`, `prep` | What the cook reads: "Chicken thighs, boneless" / "cut into 4 cm pieces". |
| `opt` | Optional garnish or extra; not counted in nutrition. Pork and alcohol (`halal: NOT_PERMISSIBLE`) may **only** appear as optional lines, so halal and alcohol-free cooks can make every recipe as written. |
| `group` | Section heading such as "Marinade", "Sauce", "To serve". |
| `eaten` | Fraction eaten (0–1): deep-frying oil ≈ 0.15–0.3, discarded marinade, whole spices removed. |
| `cooked` | `true` if the amount is already-cooked weight ("300 g cooked rice"). |
| `taste` | "To taste", with no amount (salt, pepper). |
| `steps[]` | `t` text, optional `min` (timer minutes) and `c` (temperature °C). |
| `storage`, `reheating`, `safety` | Free text or a code: `@fridge3`, `@reheat`, `@chilled`, `@poultry`, `@mince`, `@beef`, `@lamb`, `@fish`, `@shellfish`, `@raw_fish`, `@egg`, `@rice`, `@deep_fry` (see `StandardRecipeNotes.kt`). |
| `variantOf`, `variantSummary` | Only for a deliberate variation of another recipe (lighter, vegetarian…). Never present a variation as the traditional dish. |
| `family` | Groups versions of the same dish under one item in Discover ("Biryani · 4 versions"): the id of the dish's main recipe, in `RecipeCatalog.kt` or any library file. Use it for genuinely different traditional versions — regional styles (Sindhi vs Hyderabadi biryani), a fish vs chicken version. The main recipe has no `family`; a family can't point at a recipe that is itself in a family; variations join their base's family automatically, so they don't set it. |

## Quality rules (enforced)

- Mains, soups, breakfasts, breads and desserts: at least **6 steps** and **700 characters** of method. Salads, sides, snacks, drinks and sauces: at least 4 steps and 350 characters. Each step is 40–600 characters.
- Steps should be detailed enough to cook from without prior knowledge: heat level, pan size, times, visual and sensory cues ("until the onions are deep golden and jammy, 12–15 minutes"), doneness checks, resting, how to serve. Put timers and oven temperatures in `min` / `c`.
- Description 40–400 characters; at least one piece of equipment; storage advice.
- Raw meat, poultry or seafood needs a `safety` note.
- Calories per serving must be plausible for the course (main 200–1400, breakfast 150–1000, soup 60–900, salad 40–900, side 30–800, snack 40–800, dessert 80–900, bread 80–800, drink and sauce 5–600).
- No duplicate dishes: names are compared case- and punctuation-insensitively (text in brackets is ignored) against the catalogue and every file.
