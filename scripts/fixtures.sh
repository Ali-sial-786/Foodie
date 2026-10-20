#!/usr/bin/env bash
# Regenerates the food data derived from USDA SR28 and verifies it is up to date.
set -euo pipefail
cd "$(dirname "$0")/.."

python3 scripts/generate_food_data.py
python3 scripts/generate_food_data.py --check
git --no-pager diff --stat -- shared/src/commonMain/kotlin/app/nouri/core/fooddb/generated app/src/main/assets/fooddb || true
