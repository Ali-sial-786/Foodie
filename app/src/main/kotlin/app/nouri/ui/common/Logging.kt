package app.nouri.ui.common

import app.nouri.AppContainer
import app.nouri.core.diary.DiaryEntry
import app.nouri.data.repo.LogResult

/**
 * Logs entries and reports the outcome in the app snackbar with an Undo action.
 * Returns the duplicate warning instead of logging when one is detected, so the
 * caller can ask the user.
 */
suspend fun AppContainer.logEntries(entries: List<DiaryEntry>, force: Boolean = false): LogResult {
    val result = diary.log(entries, force)
    when (result) {
        is LogResult.Logged -> if (result.entries.isNotEmpty()) {
            val ids = result.entries.map { it.id }
            val meal = result.entries.first().mealType.label
            val text = if (result.entries.size == 1) "Logged ${result.entries.first().name} to $meal" else "Logged ${result.entries.size} items to $meal"
            messages.show(text, "Undo") { diary.delete(ids) }
        }
        LogResult.AlreadyLogged -> messages.show("Already in your diary")
        is LogResult.PossibleDuplicate -> Unit
    }
    return result
}

/** Soft-deletes entries with an Undo snackbar. */
suspend fun AppContainer.deleteEntries(ids: List<String>, label: String) {
    diary.delete(ids)
    messages.show("Deleted $label", "Undo") { diary.restore(ids) }
}
