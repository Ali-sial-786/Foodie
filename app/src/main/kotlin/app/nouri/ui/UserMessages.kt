package app.nouri.ui

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** A transient message shown in the app-wide snackbar, optionally with one action (usually Undo). */
data class UserMessage(val text: String, val actionLabel: String? = null, val onAction: (suspend () -> Unit)? = null)

/** App-wide snackbar queue, so messages survive navigating away from the screen that raised them. */
class UserMessages {
    private val flow = MutableSharedFlow<UserMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<UserMessage> get() = flow

    fun show(text: String, actionLabel: String? = null, onAction: (suspend () -> Unit)? = null) {
        flow.tryEmit(UserMessage(text, actionLabel, onAction))
    }
}
