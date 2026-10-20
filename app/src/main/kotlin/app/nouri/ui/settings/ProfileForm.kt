package app.nouri.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.InputChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import app.nouri.core.model.FoodPreferences
import app.nouri.core.ontology.Allergen
import app.nouri.core.ontology.CanonicalIngredient
import app.nouri.core.ontology.DietGoal
import app.nouri.core.ontology.IngredientCatalog
import app.nouri.core.progress.ActivityLevel
import app.nouri.core.progress.BodyProfile
import app.nouri.core.progress.NumberText
import app.nouri.core.progress.Sex
import app.nouri.core.progress.WeightGoal
import app.nouri.core.progress.WeightUnit
import app.nouri.core.recipes.CuisineCatalog
import app.nouri.core.recipes.Difficulty
import app.nouri.core.util.SystemTime
import app.nouri.ui.components.NumberField
import app.nouri.ui.components.toUserDouble

/** Editable text form for the profile and food preferences; every field is optional. */
data class ProfileForm(
    val sex: Sex = Sex.UNSPECIFIED,
    val birthYear: String = "",
    val heightCm: String = "",
    val activity: ActivityLevel = ActivityLevel.LIGHT,
    val goal: WeightGoal = WeightGoal.MAINTAIN,
    val weeklyRate: String = "",
    val targetWeight: String = "",
    val bodyFat: String = "",
    val diets: Set<DietGoal> = emptySet(),
    val allergies: Set<Allergen> = emptySet(),
    val cuisines: Set<String> = emptySet(),
    val dislikes: Set<String> = emptySet(),
    val skill: Difficulty = Difficulty.MEDIUM,
    val maxMinutes: String = "",
    val household: String = "",
) {
    /** The profile, or a plain-language problem with the first invalid field. */
    fun toProfile(unit: WeightUnit): Result<BodyProfile> {
        val thisYear = SystemTime.today().year
        val year = birthYear.trim().takeIf { it.isNotEmpty() }?.let { it.toIntOrNull()?.takeIf { y -> y in 1900..thisYear } ?: return fail("Enter a birth year like 1990") }
        val height = heightCm.optional { it in 50.0..260.0 } ?: return fail("Enter a height between 50 and 260 cm")
        val rateDisplay = weeklyRate.optional { it in 0.0..unit.fromKg(1.5) } ?: return fail("Choose a weekly rate up to ${unit.format(1.5)}")
        val target = targetWeight.optional { unit.toKg(it) in 25.0..400.0 } ?: return fail("Enter a target weight in ${unit.symbol}")
        val fat = bodyFat.optional { it > 2.0 && it < 75.0 } ?: return fail("Body fat should be between 3 and 75%")
        return runCatching {
            BodyProfile(
                sex = sex,
                birthYear = year,
                heightCm = height.value,
                activityLevel = activity,
                goal = goal,
                targetWeightKg = target.value?.let(unit::toKg),
                weeklyRateKg = rateDisplay.value?.let(unit::toKg) ?: defaultRate(goal),
                bodyFatPercent = fat.value,
            )
        }
    }

    fun applyTo(prefs: FoodPreferences): FoodPreferences = prefs.copy(
        requiredDiets = diets,
        allergies = allergies,
        favouriteCuisines = CuisineCatalog.all.map { it.id }.filter { it in cuisines },
        dislikedIngredients = dislikes,
        cookingSkill = skill,
        usualMaxMinutes = maxMinutes.trim().toIntOrNull()?.takeIf { it in 5..600 },
        householdSize = household.trim().toIntOrNull()?.takeIf { it in 1..20 } ?: prefs.householdSize,
    )

    companion object {
        fun from(profile: BodyProfile?, prefs: FoodPreferences, unit: WeightUnit): ProfileForm = ProfileForm(
            sex = profile?.sex ?: Sex.UNSPECIFIED,
            birthYear = profile?.birthYear?.toString().orEmpty(),
            heightCm = profile?.heightCm?.let { NumberText.format(it, 0) }.orEmpty(),
            activity = profile?.activityLevel ?: ActivityLevel.LIGHT,
            goal = profile?.goal ?: WeightGoal.MAINTAIN,
            weeklyRate = profile?.takeIf { it.goal != WeightGoal.MAINTAIN }?.weeklyRateKg?.let { NumberText.format(unit.fromKg(it), 2, trimZeros = true) }.orEmpty(),
            targetWeight = profile?.targetWeightKg?.let { NumberText.format(unit.fromKg(it), 1) }.orEmpty(),
            bodyFat = profile?.bodyFatPercent?.let { NumberText.format(it, 0) }.orEmpty(),
            diets = prefs.requiredDiets,
            allergies = prefs.allergies,
            cuisines = prefs.favouriteCuisines.toSet(),
            dislikes = prefs.dislikedIngredients,
            skill = prefs.cookingSkill,
            maxMinutes = prefs.usualMaxMinutes?.toString().orEmpty(),
            household = prefs.householdSize.takeIf { it > 1 }?.toString().orEmpty(),
        )

        fun defaultRate(goal: WeightGoal): Double = when (goal) {
            WeightGoal.LOSE -> 0.5
            WeightGoal.GAIN -> 0.25
            else -> 0.5
        }
    }
}

private fun fail(message: String): Result<BodyProfile> = Result.failure(IllegalArgumentException(message))

/** A parsed optional number, so "blank" (value null) and "invalid" (no Parsed at all) are distinguishable. */
private class Parsed(val value: Double?)

private fun String.optional(valid: (Double) -> Boolean): Parsed? {
    if (isBlank()) return Parsed(null)
    val v = toUserDouble() ?: return null
    return if (valid(v)) Parsed(v) else null
}

internal fun Sex.label(): String = when (this) {
    Sex.FEMALE -> "Female"
    Sex.MALE -> "Male"
    Sex.UNSPECIFIED -> "Prefer not to say"
}

internal fun WeightGoal.label(): String = when (this) {
    WeightGoal.MAINTAIN -> "Maintain my weight"
    WeightGoal.LOSE -> "Lose weight"
    WeightGoal.GAIN -> "Gain weight"
    WeightGoal.MUSCLE_GAIN -> "Build muscle"
}

@Composable
internal fun GoalFields(form: ProfileForm, unit: WeightUnit, onChange: (ProfileForm) -> Unit) {
    Column {
        WeightGoal.entries.forEach { g -> RadioRow(g.label(), selected = form.goal == g) { onChange(form.copy(goal = g)) } }
        if (form.goal == WeightGoal.LOSE || form.goal == WeightGoal.GAIN) {
            NumberField(
                form.weeklyRate, { onChange(form.copy(weeklyRate = it)) }, "Weekly rate (optional)", Modifier.fillMaxWidth(),
                suffix = "${unit.symbol}/week",
                supportingText = "Default ${unit.format(ProfileForm.defaultRate(form.goal), 2)} per week. Slower changes are easier to sustain.",
            )
            NumberField(form.targetWeight, { onChange(form.copy(targetWeight = it)) }, "Target weight (optional)", Modifier.fillMaxWidth(), suffix = unit.symbol)
        }
    }
}

@Composable
internal fun AboutYouFields(form: ProfileForm, unit: WeightUnit, onChange: (ProfileForm) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Sex (for the energy equation)", style = MaterialTheme.typography.labelLarge)
        SingleChoiceChips(Sex.entries, form.sex, { it.label() }, { onChange(form.copy(sex = it)) })
        if (form.sex == Sex.UNSPECIFIED) BodyText("Estimates use the average of the male and female equations.")
        NumberField(form.birthYear, { onChange(form.copy(birthYear = it)) }, "Birth year", Modifier.fillMaxWidth(), decimal = false, supportingText = "Only the year is stored")
        NumberField(form.heightCm, { onChange(form.copy(heightCm = it)) }, "Height", Modifier.fillMaxWidth(), suffix = "cm")
        if (unit == WeightUnit.LB) FeetInchesHelper { cm -> onChange(form.copy(heightCm = NumberText.format(cm, 0))) }
        Text("Activity", style = MaterialTheme.typography.labelLarge)
        ActivityLevel.entries.forEach { a -> RadioRow(a.label, selected = form.activity == a) { onChange(form.copy(activity = a)) } }
        NumberField(form.bodyFat, { onChange(form.copy(bodyFat = it)) }, "Body fat (optional)", Modifier.fillMaxWidth(), suffix = "%")
    }
}

@Composable
private fun FeetInchesHelper(onCm: (Double) -> Unit) {
    var feet by rememberSaveable { mutableStateOf("") }
    var inches by rememberSaveable { mutableStateOf("") }
    fun push(f: String, i: String) {
        val ft = f.toUserDouble() ?: return
        val inch = i.toUserDouble() ?: 0.0
        onCm((ft * 12 + inch) * 2.54)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField(feet, { feet = it; push(it, inches) }, "Feet", Modifier.weight(1f), decimal = false)
        NumberField(inches, { inches = it; push(feet, it) }, "Inches", Modifier.weight(1f))
    }
}

@Composable
internal fun DietAndAllergyFields(form: ProfileForm, onChange: (ProfileForm) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Dietary requirements", style = MaterialTheme.typography.labelLarge)
        ChipGroup(DietGoal.entries.filter { it != DietGoal.LOWER_CALORIE && it != DietGoal.HIGHER_PROTEIN }, form.diets, { it.label }, { onChange(form.copy(diets = form.diets.toggle(it))) })
        BodyText("Recipes that don't meet these are never suggested. Halal is judged per ingredient; check meat and gelatin sources yourself.")
        Text("Allergies", style = MaterialTheme.typography.labelLarge)
        ChipGroup(Allergen.entries, form.allergies, { it.label }, { onChange(form.copy(allergies = form.allergies.toggle(it))) })
        BodyText("Allergy filtering uses ingredient data and can't account for cross-contamination. Always check labels.")
    }
}

@Composable
internal fun CuisineFields(form: ProfileForm, onChange: (ProfileForm) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Cuisines you enjoy", style = MaterialTheme.typography.labelLarge)
        ChipGroup(CuisineCatalog.all.filter { it.id != "international" }.map { it.id }, form.cuisines, { id -> CuisineCatalog[id]?.name ?: id }, { onChange(form.copy(cuisines = form.cuisines.toggle(it))) })
        BodyText("Used to tell similar-looking dishes apart and to rank recipe ideas.")
    }
}

@Composable
internal fun CookingFields(form: ProfileForm, onChange: (ProfileForm) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Cooking skill", style = MaterialTheme.typography.labelLarge)
        SingleChoiceChips(Difficulty.entries, form.skill, { it.label }, { onChange(form.copy(skill = it)) })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(form.maxMinutes, { onChange(form.copy(maxMinutes = it)) }, "Usual max time", Modifier.weight(1f), suffix = "min", decimal = false)
            NumberField(form.household, { onChange(form.copy(household = it)) }, "Household size", Modifier.weight(1f), decimal = false)
        }
    }
}

/** Disliked ingredients, picked from the ingredient catalogue so filtering is exact. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DislikeFields(form: ProfileForm, suggest: (String) -> List<CanonicalIngredient>, onChange: (ProfileForm) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val suggestions = remember(query) { if (query.trim().length >= 2) suggest(query.trim()).filter { it.id !in form.dislikes } else emptyList() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Ingredients you'd rather avoid", style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            label = { Text("Search ingredients") },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            suggestions.forEach { ing -> SuggestionChip(onClick = { onChange(form.copy(dislikes = form.dislikes + ing.id)); query = "" }, label = { Text("+ ${ing.name}") }) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            form.dislikes.forEach { id ->
                InputChip(
                    selected = true,
                    onClick = { onChange(form.copy(dislikes = form.dislikes - id)) },
                    label = { Text(IngredientCatalog[id]?.name ?: id) },
                    trailingIcon = { Text("×", modifier = Modifier.padding(start = 2.dp)) },
                )
            }
        }
        if (form.dislikes.isNotEmpty()) BodyText("Tap an ingredient to remove it.")
    }
}
