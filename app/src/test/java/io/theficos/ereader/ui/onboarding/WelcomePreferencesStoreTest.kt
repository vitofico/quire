package io.theficos.ereader.ui.onboarding

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PickStartDestinationTest {

    @Test fun `no account and not skipped lands on welcome`() {
        assertThat(pickStartDestination(hasAccount = false, welcomeCompleted = false))
            .isEqualTo("welcome")
    }

    @Test fun `no account but flow completed lands on home`() {
        // User chose "Skip — offline" and we persisted the marker. Don't
        // bounce them through Welcome on every cold start.
        assertThat(pickStartDestination(hasAccount = false, welcomeCompleted = true))
            .isEqualTo("home")
    }

    @Test fun `account configured always lands on home`() {
        assertThat(pickStartDestination(hasAccount = true, welcomeCompleted = false))
            .isEqualTo("home")
        assertThat(pickStartDestination(hasAccount = true, welcomeCompleted = true))
            .isEqualTo("home")
    }
}
