#!/usr/bin/env python3
"""Validates the recipe library and bundles it for the app.

Input:  data/recipes/*.json  (format and rules: data/recipes/README.md)
Output: app/src/main/assets/recipes/library.json  (minified, loaded on first use)

Usage: python3 scripts/build_recipe_library.py [--check] [--table PATH [--verbose]] [--format] [FILE ...]
  --check       exit non-zero if the bundle is out of date (used in CI); writes nothing.
  --table PATH  ingredient table from IngredientTableExport (see that class) to estimate
                calories per serving and catch amounts that don't convert. Without it,
                only structure is checked; RecipeLibraryTest always runs the real engine.
  --verbose     with --table, print each recipe's estimated calories per serving.
  --format      rewrite the given FILEs in the canonical layout (one line per ingredient and step).
  FILE ...      validate only these source files (still checks ids against all files);
                implies no bundle is written. Other files that are mid-edit (invalid JSON)
                are skipped with a warning instead of failing.

The same rules are enforced by RecipeLibraryTest with the real nutrition engine.
"""
import glob
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC_DIR = os.path.join(ROOT, "data", "recipes")
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "recipes", "library.json")
CORE = os.path.join(ROOT, "shared", "src", "commonMain", "kotlin", "app", "nouri", "core")

COURSES = ["breakfast", "main", "soup", "salad", "side", "snack", "dessert", "bread", "drink", "sauce"]
DETAILED = {"main", "soup", "breakfast", "bread", "dessert"}
KCAL = {
    "main": (200, 1400), "breakfast": (150, 1000), "soup": (60, 900), "salad": (40, 900), "side": (30, 800),
    "snack": (40, 800), "dessert": (80, 900), "bread": (80, 800), "drink": (5, 600), "sauce": (5, 600),
}
COMPUTED_TAGS = {"vegetarian", "vegan", "gluten-free", "dairy-free", "nut-free", "halal", "high-protein", "lower-calorie"}
READY_TO_EAT = {"tuna_canned", "sardines", "anchovies", "smoked_salmon", "dried_shrimp", "ham"}
RAW_PROTEIN = {"POULTRY", "RED_MEAT", "PORK", "SEAFOOD"}
DIFFICULTIES = {"EASY", "MEDIUM", "INVOLVED"}
CODES = {"@poultry", "@mince", "@egg", "@rice", "@fridge3", "@reheat", "@deep_fry", "@beef", "@lamb", "@fish",
         "@shellfish", "@raw_fish", "@chilled"}

RECIPE_KEYS = {"id", "name", "cuisine", "region", "course", "servings", "prep", "cook", "difficulty", "description",
               "equipment", "tags", "ingredients", "steps", "storage", "reheating", "safety", "notes", "variantOf",
               "variantSummary", "family"}
REQUIRED = {"id", "name", "cuisine", "course", "servings", "prep", "cook", "description", "ingredients", "steps"}
LINE_KEYS = {"id", "name", "q", "u", "prep", "opt", "group", "eaten", "cooked", "taste"}
STEP_KEYS = {"t", "min", "c"}


def read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def kotlin_ids(pattern, *paths):
    found = set()
    for p in paths:
        found.update(re.findall(pattern, read(p)))
    return found


def normalized_name(name):
    name = re.sub(r"\([^)]*\)", "", name.lower())
    return re.sub(r"[^a-z0-9]", "", name)


def load_context():
    ontology = glob.glob(os.path.join(CORE, "ontology", "*.kt"))
    ingredients = kotlin_ids(r'\bing\(\s*"([a-z0-9_]+)"', *ontology)
    cuisines = kotlin_ids(r'Cuisine\(\s*"([a-z0-9_]+)"', os.path.join(CORE, "recipes", "Cuisine.kt"))
    units_src = read(os.path.join(CORE, "units", "Units.kt")) if os.path.exists(os.path.join(CORE, "units", "Units.kt")) else \
        "".join(read(p) for p in glob.glob(os.path.join(CORE, "units", "*.kt")))
    enum = units_src[units_src.index("enum class QuantityUnit"):]
    enum = enum[:enum.index(";")]
    units = set(re.findall(r"^\s*([A-Z_]+)\(", enum, re.M))
    catalogue_src = read(os.path.join(CORE, "recipes", "RecipeCatalog.kt"))
    catalogue = re.findall(r'recipe\(\s*"([a-z0-9_]+)",\s*"([^"]+)"', catalogue_src)
    return ingredients, cuisines, units, catalogue


def check_recipe(r, ctx, table):
    ingredients, cuisines, units, _ = ctx
    problems = []
    keys = set(r)
    if keys - RECIPE_KEYS:
        problems.append(f"unknown keys {sorted(keys - RECIPE_KEYS)}")
    if REQUIRED - keys:
        problems.append(f"missing keys {sorted(REQUIRED - keys)}")
        return problems
    if not re.fullmatch(r"[a-z0-9]+(_[a-z0-9]+)*", r["id"]):
        problems.append("id must be snake_case")
    if r["cuisine"] not in cuisines:
        problems.append(f"unknown cuisine {r['cuisine']}")
    course = r["course"]
    if course not in COURSES:
        problems.append(f"unknown course {course}")
        course = "main"
    if r.get("difficulty", "MEDIUM") not in DIFFICULTIES:
        problems.append(f"unknown difficulty {r.get('difficulty')}")
    tags = r.get("tags", [])
    if set(tags) & COMPUTED_TAGS:
        problems.append(f"tags {sorted(set(tags) & COMPUTED_TAGS)} are calculated, not declared")
    if set(tags) & set(COURSES):
        problems.append("put the course in \"course\", not in tags")
    bad_tags = [t for t in tags if not re.fullmatch(r"[a-z0-9]+(-[a-z0-9]+)*", t)]
    if bad_tags:
        problems.append(f"tags must be lowercase-kebab: {bad_tags}")
    if not (isinstance(r["servings"], int) and 1 <= r["servings"] <= 24):
        problems.append(f"servings {r['servings']}")
    if not (isinstance(r["prep"], int) and isinstance(r["cook"], int) and 0 <= r["prep"] <= 240 and 0 <= r["cook"] <= 720
            and r["prep"] + r["cook"] >= 5):
        problems.append(f"times {r['prep']}+{r['cook']} (whole minutes; prep ≤ 240, cook ≤ 720)")
    if not 40 <= len(r["description"]) <= 400:
        problems.append("description should be 40–400 characters")
    if not r.get("equipment"):
        problems.append("no equipment")
    if not str(r.get("storage") or "").strip():
        problems.append("no storage advice")
    for field in ("storage", "reheating"):
        v = r.get(field)
        if isinstance(v, str) and v.startswith("@") and v not in CODES:
            problems.append(f"unknown code {v}")
    for s in r.get("safety", []):
        if s.startswith("@") and s not in CODES:
            problems.append(f"unknown code {s}")

    steps = r["steps"]
    min_steps, min_chars = (6, 700) if course in DETAILED else (4, 350)
    if len(steps) < min_steps:
        problems.append(f"{len(steps)} steps; at least {min_steps} for {course}")
    total = 0
    for i, s in enumerate(steps, 1):
        if set(s) - STEP_KEYS or "t" not in s:
            problems.append(f"step {i}: keys must be t, min, c")
            continue
        total += len(s["t"])
        if not 40 <= len(s["t"]) <= 600:
            problems.append(f"step {i} should be 40–600 characters ({len(s['t'])})")
        if "min" in s and not (isinstance(s["min"], (int, float)) and 0 < s["min"] <= 720):
            problems.append(f"step {i}: min must be minutes > 0")
        if "c" in s and not (isinstance(s["c"], int) and 30 <= s["c"] <= 300):
            problems.append(f"step {i}: c must be °C between 30 and 300")
    if total < min_chars:
        problems.append(f"method is {total} characters; at least {min_chars} for {course}")

    lines = r["ingredients"]
    if not lines:
        problems.append("no ingredients")
    kcal = 0.0
    needs_safety = False
    for line in lines:
        label = line.get("name", "?")
        if set(line) - LINE_KEYS:
            problems.append(f"{label}: unknown keys {sorted(set(line) - LINE_KEYS)}")
        if "id" not in line or "name" not in line:
            problems.append(f"{label}: id and name are required")
            continue
        if line["id"] not in ingredients:
            problems.append(f"{label}: unknown ingredient {line['id']}")
            continue
        taste = line.get("taste", False)
        if not taste:
            if "q" not in line or "u" not in line:
                problems.append(f"{label}: needs q and u (or taste: true)")
                continue
            if line["u"] not in units:
                problems.append(f"{label}: unknown unit {line['u']}")
                continue
            if not (isinstance(line["q"], (int, float)) and line["q"] > 0):
                problems.append(f"{label}: q must be > 0")
        eaten = line.get("eaten", 1.0)
        if not (isinstance(eaten, (int, float)) and 0 <= eaten <= 1):
            problems.append(f"{label}: eaten must be 0–1")
        if table is None:
            continue
        info = table.get(line["id"])
        if info is None:
            problems.append(f"{label}: {line['id']} missing from the ingredient table (re-export it)")
            continue
        optional = line.get("opt", False)
        if info["halal"] == "NOT_PERMISSIBLE" and not optional:
            problems.append(f"{label}: must be optional (halal / alcohol-free cooks)")
        if (not optional and not line.get("cooked", False) and line["id"] not in READY_TO_EAT
                and info["category"] in RAW_PROTEIN):
            needs_safety = True
        if taste or optional:
            continue
        status, _grams, kcal_per_unit, *_ = info["cooked" if line.get("cooked") else "raw"][line["u"]]
        if status == "UNRESOLVED":
            problems.append(f"{label}: {line['u']} doesn't convert for {line['id']} — use grams or a unit it defines")
            continue
        kcal += kcal_per_unit * line["q"] * eaten
    if table is not None:
        if needs_safety and not r.get("safety"):
            problems.append("raw meat, poultry or seafood needs a safety note")
        per = kcal / max(1, r["servings"])
        if VERBOSE:
            print(f"  {r['id']}: about {per:.0f} kcal per serving ({course}, {len(steps)} steps, {total} characters of method)")
        lo, hi = KCAL[course]
        if not lo <= per <= hi:
            problems.append(f"about {per:.0f} kcal per serving; expected {lo}–{hi} for {course}")
    return problems


VERBOSE = False


KEY_ORDER = ["id", "name", "cuisine", "region", "course", "servings", "prep", "cook", "difficulty", "description",
             "equipment", "tags", "ingredients", "steps", "storage", "reheating", "safety", "notes", "variantOf",
             "variantSummary", "family"]


def dump(data):
    """Canonical layout: readable diffs, one line per ingredient line and per step."""
    def one(v):
        return json.dumps(v, ensure_ascii=False)
    out = ['{\n  "recipes": [']
    for i, r in enumerate(data["recipes"]):
        keys = [k for k in KEY_ORDER if k in r] + sorted(k for k in r if k not in KEY_ORDER)
        body = []
        for k in keys:
            v = r[k]
            if k in ("ingredients", "steps") and v:
                rows = ",\n".join(f"        {one(x)}" for x in v)
                body.append(f'      "{k}": [\n{rows}\n      ]')
            else:
                body.append(f'      "{k}": {one(v)}')
        out.append("    {\n" + ",\n".join(body) + "\n    }" + ("," if i < len(data["recipes"]) - 1 else ""))
    out.append("  ]\n}\n")
    return "\n".join(out)


def main(argv):
    global VERBOSE
    VERBOSE = "--verbose" in argv
    check = "--check" in argv
    table = None
    if "--table" in argv:
        table = json.loads(read(argv[argv.index("--table") + 1]))
    explicit = [a for i, a in enumerate(argv) if a.endswith(".json") and not (i > 0 and argv[i - 1] == "--table")]
    if "--format" in argv:
        for path in explicit:
            formatted = dump(json.loads(read(path)))
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(formatted)

    ctx = load_context()
    sources = sorted(glob.glob(os.path.join(SRC_DIR, "*.json")))
    seen_ids = {rid: "RecipeCatalog.kt" for rid, _ in ctx[3]}
    seen_names = {normalized_name(name): f"RecipeCatalog.kt:{rid}" for rid, name in ctx[3]}
    recipes, errors, counts = [], [], {}
    validate = {os.path.abspath(p) for p in explicit} if explicit else None
    for path in sources:
        name = os.path.basename(path)
        try:
            data = json.loads(read(path))
        except json.JSONDecodeError as e:
            if validate is not None and os.path.abspath(path) not in validate:
                print(f"warning: skipped {name} (invalid JSON, probably mid-edit)", file=sys.stderr)
            else:
                errors.append(f"{name}: invalid JSON: {e}")
            continue
        if set(data) != {"recipes"}:
            errors.append(f"{name}: top level must be {{\"recipes\": [...]}}")
            continue
        counts[name] = len(data["recipes"])
        for r in data["recipes"]:
            rid = r.get("id", "?")
            if rid in seen_ids:
                errors.append(f"{name} {rid}: id already used in {seen_ids[rid]}")
            seen_ids[rid] = name
            key = normalized_name(r.get("name", ""))
            if key in seen_names:
                errors.append(f"{name} {rid}: same dish as {seen_names[key]}")
            seen_names[key] = f"{name}:{rid}"
            if validate is None or os.path.abspath(path) in validate:
                errors.extend(f"{name} {rid}: {p}" for p in check_recipe(r, ctx, table))
            recipes.append(r)
    families = {r.get("id"): r.get("family") or r.get("variantOf") for r in recipes}
    for r in recipes:
        fam = r.get("family")
        where = f"{seen_ids.get(r.get('id'), '?')} {r.get('id')}"
        if fam is not None:
            if fam == r.get("id"):
                errors.append(f"{where}: family must name another recipe (the dish's main version)")
            elif fam not in seen_ids:
                errors.append(f"{where}: family {fam} is not a recipe id")
            elif families.get(fam):
                errors.append(f"{where}: family {fam} is itself a version of {families[fam]}; point at the main recipe instead")
            if r.get("variantOf"):
                errors.append(f"{where}: a variation joins its base's family automatically; drop family")
        v = r.get("variantOf")
        if v and v not in seen_ids:
            errors.append(f"{seen_ids.get(r.get('id'), '?')} {r.get('id')}: variation of unknown recipe {v}")
        if v and not r.get("variantSummary"):
            errors.append(f"{seen_ids.get(r.get('id'), '?')} {r.get('id')}: a variation needs variantSummary")

    if errors:
        print("\n".join(errors), file=sys.stderr)
        print(f"\n{len(errors)} problem(s)", file=sys.stderr)
        return 1
    summary = ", ".join(f"{k}: {v}" for k, v in counts.items())
    print(f"{len(recipes)} recipes OK ({summary or 'no files'})" + ("" if table else " — structure only; pass --table for calories"))
    if explicit:
        return 0
    bundle = json.dumps({"recipes": recipes}, ensure_ascii=False, separators=(",", ":")) + "\n"
    current = read(OUT) if os.path.exists(OUT) else None
    if check:
        if current != bundle:
            print(f"{os.path.relpath(OUT, ROOT)} is out of date; run python3 scripts/build_recipe_library.py", file=sys.stderr)
            return 1
        return 0
    if current != bundle:
        os.makedirs(os.path.dirname(OUT), exist_ok=True)
        with open(OUT, "w", encoding="utf-8") as fh:
            fh.write(bundle)
        print(f"wrote {os.path.relpath(OUT, ROOT)} ({len(bundle) // 1024} KB)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
