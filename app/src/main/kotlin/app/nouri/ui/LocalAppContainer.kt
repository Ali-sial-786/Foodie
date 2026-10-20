package app.nouri.ui

import androidx.compose.runtime.staticCompositionLocalOf
import app.nouri.AppContainer

/** The app's dependency container, provided once at the root of the composition. */
val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }
