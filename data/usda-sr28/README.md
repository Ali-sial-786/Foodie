# USDA SR28 source data

Files in this directory are the unmodified ASCII files `ABBREV.txt` and `FOOD_DES.txt` from the
**USDA National Nutrient Database for Standard Reference, Release 28** (2015, slightly revised 2016),
U.S. Department of Agriculture, Agricultural Research Service, Nutrient Data Laboratory.

* Licence: U.S. Government work — public domain (CC0 1.0 as published on FoodData Central / data.gov).
* Values are per 100 g of edible portion.
* SHA-256 (as committed):
  * `ABBREV.txt`   `4c42235a001efd5e94ce38001682138056aa1b755427b8a8d315c2b02cd2b85b`
  * `FOOD_DES.txt` `bb2047218cdad1d830da69e760386409fde3b7afabceae604c5479f280d4fa2e`

## How these copies were obtained

The development sandbox could not reach usda.gov, so the files were taken from the npm package
`fda-nutrient-database@1.0.2` (registry integrity `sha512-pUebhBfCDukm…ZYZl5A==`, verified), which
redistributes the SR28 ASCII files unchanged. Values were spot-checked against well-known SR28
records (e.g. #20045 cooked white rice 130 kcal, #01123 raw egg 143 kcal, #04053 olive oil 884 kcal).
Anyone with internet access can re-verify against the official download at
https://fdc.nal.usda.gov/download-datasets (SR Legacy / SR28 ASCII).

SR28 has been superseded by "SR Legacy" (April 2018), which contains minor corrections; migrating to
SR Legacy or FoodData Central Foundation Foods is tracked in DATA_SOURCES.md.

Regenerate the derived files with `python3 scripts/generate_food_data.py`.
