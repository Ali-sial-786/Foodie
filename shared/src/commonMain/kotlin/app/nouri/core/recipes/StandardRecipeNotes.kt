package app.nouri.core.recipes

/**
 * Food-safety, storage and reheating wording shared by the built-in catalogue and the
 * recipe library, so the same advice reads the same everywhere. Library files refer to
 * these with codes such as `"@poultry"` (see [codes]).
 */
object StandardRecipeNotes {
    const val POULTRY = "Cook chicken until the thickest piece reaches 74 °C (165 °F) and the juices run clear."
    const val MINCE = "Cook minced meat to 71–74 °C (160–165 °F); it should not be pink in the centre."
    const val EGG = "Cook eggs until whites are set; serve runny yolks only to people not at higher risk (pregnancy, young children, older adults)."
    const val RICE = "Cool leftover rice quickly (within 1 hour), refrigerate, and reheat only once until steaming hot."
    const val FRIDGE_3_DAYS = "Refrigerate within 2 hours in a covered container; eat within 3 days."
    const val REHEAT = "Reheat until steaming hot throughout (74 °C / 165 °F)."
    const val DEEP_FRY = "Fill the pan no more than one-third with oil, keep it at 170–180 °C and never leave hot oil unattended."
    const val BEEF = "Cook beef until browned on the outside; slow-cooked cuts are safe once tender and steaming hot throughout."
    const val LAMB = "Cook lamb until browned on the outside; slow-cooked cuts are safe once tender and steaming hot throughout."
    const val FISH = "Cook fish until it is opaque and flakes easily, or reaches 63 °C (145 °F) in the thickest part."
    const val SHELLFISH = "Cook prawns until pink and opaque; discard mussels or clams that are open before cooking or stay closed after."
    const val RAW_FISH = "Use only fish sold for raw eating (previously frozen to kill parasites) and keep it cold; not advised in pregnancy."
    const val DAIRY_DESSERT = "Keep chilled and eat within 3 days."

    /** Shorthand used in the JSON files in `data/recipes/`. */
    val codes: Map<String, String> = mapOf(
        "@poultry" to POULTRY, "@mince" to MINCE, "@egg" to EGG, "@rice" to RICE, "@fridge3" to FRIDGE_3_DAYS,
        "@reheat" to REHEAT, "@deep_fry" to DEEP_FRY, "@beef" to BEEF, "@lamb" to LAMB, "@fish" to FISH,
        "@shellfish" to SHELLFISH, "@raw_fish" to RAW_FISH, "@chilled" to DAIRY_DESSERT,
    )

    fun expand(text: String): String = codes[text.trim()] ?: text
}
