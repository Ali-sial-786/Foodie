package app.nouri.core.dishes

import kotlin.test.Test

/** Prints the computed dish table for review (`./gradlew :shared:jvmTest --tests "*DishTablePrinter*" -i`). */
class DishTablePrinter {
    @Test
    fun printTable() {
        if (System.getProperty("nouri.printDishes") == null && System.getenv("NOURI_PRINT_DISHES") == null) return
        val n = DishNutrition.shared
        for (d in DishCatalog.all) {
            val p = n.reference(d.id)!!.per100g
            val s = p * (d.servingGrams / 100.0)
            println("%-22s %5.0f kcal/100g  P%5.1f C%5.1f F%5.1f | serving %4.0f g = %4.0f kcal".format(d.id, p.energyKcal, p.proteinG, p.carbsG, p.fatG, d.servingGrams, s.energyKcal))
        }
    }
}
