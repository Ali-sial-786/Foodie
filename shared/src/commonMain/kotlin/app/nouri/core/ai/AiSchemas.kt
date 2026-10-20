package app.nouri.core.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * JSON Schemas for structured outputs (strict mode: every property required,
 * optional values expressed as nullable types, no additional properties).
 */
object AiSchemas {
    private fun type(vararg t: String): Pair<String, JsonElement> =
        "type" to if (t.size == 1) JsonPrimitive(t[0]) else JsonArray(t.map { JsonPrimitive(it) })

    private fun str(nullable: Boolean = false) = JsonObject(mapOf(if (nullable) type("string", "null") else type("string")))
    private fun num(nullable: Boolean = false) = JsonObject(mapOf(if (nullable) type("number", "null") else type("number")))
    private fun int() = JsonObject(mapOf(type("integer")))
    private fun intN() = JsonObject(mapOf(type("integer", "null")))
    private fun bool() = JsonObject(mapOf(type("boolean")))
    private fun enumOf(vararg values: String) = JsonObject(mapOf(type("string"), "enum" to JsonArray(values.map { JsonPrimitive(it) })))
    private fun arr(items: JsonObject) = JsonObject(mapOf(type("array"), "items" to items))

    private fun obj(vararg props: Pair<String, JsonObject>, nullable: Boolean = false) = JsonObject(
        mapOf(
            if (nullable) type("object", "null") else type("object"),
            "properties" to JsonObject(props.toMap()),
            "required" to JsonArray(props.map { JsonPrimitive(it.first) }),
            "additionalProperties" to JsonPrimitive(false),
        ),
    )

    private val gramRange = obj("low" to num(), "typical" to num(), "high" to num(), nullable = true)

    val meal: JsonObject = obj(
        "items" to arr(
            obj(
                "label" to str(),
                "candidates" to arr(obj("name" to str(), "likelihood" to enumOf("high", "medium", "low"), "reason" to str(true))),
                "cuisine" to str(true),
                "components" to arr(str()),
                "cookingMethod" to str(true),
                "visibleFat" to str(true),
                "estimatedGrams" to gramRange,
                "householdEstimate" to str(true),
                "portionBasis" to str(true),
                "isSauceOrCondiment" to bool(),
                "partiallyHidden" to bool(),
            ),
        ),
        "referenceObjects" to arr(obj("name" to str(), "note" to str(true))),
        "unidentified" to arr(str()),
        "suggestedQuestion" to obj("text" to str(), "options" to arr(str()), "itemIndex" to intN(), nullable = true),
        "imageQuality" to enumOf("good", "blurry", "dark", "partial", "not_food"),
        "notes" to str(true),
    )

    val ingredients: JsonObject = obj(
        "items" to arr(
            obj(
                "name" to str(), "quantity" to num(true), "unit" to str(true), "packaged" to bool(),
                "certainty" to enumOf("clear", "likely", "possible"), "notes" to str(true),
            ),
        ),
        "scene" to str(true),
        "notes" to str(true),
    )

    val recipes: JsonObject = obj(
        "recipes" to arr(
            obj(
                "name" to str(), "cuisine" to str(), "region" to str(true), "description" to str(),
                "isTraditional" to bool(), "variantOf" to str(true), "servings" to int(),
                "prepMinutes" to int(), "cookMinutes" to int(), "difficulty" to enumOf("easy", "medium", "involved"),
                "equipment" to arr(str()),
                "ingredients" to arr(
                    obj(
                        "name" to str(), "amount" to num(true), "unit" to str(true), "grams" to num(true),
                        "preparation" to str(true), "optional" to bool(), "group" to str(true),
                    ),
                ),
                "steps" to arr(obj("text" to str(), "timerMinutes" to num(true), "temperatureC" to intN())),
                "substitutions" to arr(obj("ingredient" to str(), "substitute" to str(), "note" to str(true))),
                "foodSafety" to arr(str()),
                "storage" to str(true),
                "reheating" to str(true),
                "tags" to arr(str()),
            ),
        ),
        "notes" to str(true),
    )

    val mealText: JsonObject = obj(
        "items" to arr(
            obj(
                "food" to str(), "amount" to num(true), "unit" to str(true), "grams" to num(true),
                "preparation" to str(true), "recipeFraction" to num(true), "recipeReference" to str(true),
            ),
        ),
        "mealHint" to str(true),
        "notes" to str(true),
    )

    val label: JsonObject = obj("productName" to str(true), "panelText" to str(), "language" to str(true))

    /** Wraps a schema in the Responses API `text.format` object. */
    fun responseFormat(name: String, schema: JsonObject): JsonObject = buildJsonObject {
        put("type", "json_schema")
        put("name", name)
        put("schema", schema)
        put("strict", true)
    }
}
