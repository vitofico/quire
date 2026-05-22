package io.theficos.ereader.ui.onboarding

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks whether the user has finished — or deliberately skipped — the
 * first-launch flow.
 *
 * The credential store alone is not enough to gate Welcome. Users who tap
 * "Skip — use offline only" expect to land on Library without re-prompting
 * on every cold start, even though `CalibreCredentialStore.getAccount()`
 * remains null forever. Conversely, users who finished the flow with valid
 * credentials should also bypass Welcome (we set `completed = true` after
 * `saveBasicAccount` / `saveBearerAccount`).
 *
 * Backing storage is a plain `SharedPreferences` (not Encrypted) — no
 * secret material lands here, just a boolean. Keeping it cheap avoids an
 * EncryptedSharedPreferences init on the cold-start critical path.
 */
class WelcomePreferencesStore(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _completed = MutableStateFlow(prefs.getBoolean(KEY_COMPLETED, false))

    /** Observable view of [completed]. */
    val completed: StateFlow<Boolean> = _completed.asStateFlow()

    /** Synchronous read for use at composition time. */
    fun isCompleted(): Boolean = _completed.value

    /** Mark the flow as completed. Called from each terminal path. */
    fun markCompleted() {
        prefs.edit().putBoolean(KEY_COMPLETED, true).apply()
        _completed.value = true
    }

    /** Test/reset hook. Not invoked from production code. */
    fun reset() {
        prefs.edit().remove(KEY_COMPLETED).apply()
        _completed.value = false
    }

    private companion object {
        const val PREFS_NAME = "welcome_prefs"
        const val KEY_COMPLETED = "completed"
    }
}

/**
 * Pure helper that decides whether to land on `welcome` or `home` at the
 * NavHost's `startDestination`. Pulled out for unit-testability — the
 * full composition is hard to exercise from a JVM test, but this boolean
 * combiner is the load-bearing piece.
 *
 * @param hasAccount true iff `CalibreCredentialStore.getAccount() != null`.
 * @param welcomeCompleted true iff [WelcomePreferencesStore.isCompleted].
 */
fun pickStartDestination(hasAccount: Boolean, welcomeCompleted: Boolean): String =
    if (hasAccount || welcomeCompleted) "home" else "welcome"
