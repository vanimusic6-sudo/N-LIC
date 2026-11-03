package io.github.romanvht.byedpi.activities

import android.content.Context
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.button.MaterialButton
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import io.github.romanvht.byedpi.R
import io.github.romanvht.byedpi.subscription.SubscriptionImport
import io.github.romanvht.byedpi.subscription.SubscriptionVault
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Launches the ACTUAL production VPN server screen on an emulator, then
 * opens its dropdown and selects a node. Compiler/unit tests alone would
 * miss theme, resource and activity lifecycle crashes.
 */
@RunWith(AndroidJUnit4::class)
class NlicVpnListSmokeTest {
    @Test fun openVpnPageAndServerDropdown() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Test-only fake configuration; never use real user credentials.
        val fixture = SubscriptionImport.parseNode(
            "vless://b63169b1-8e95-48f2-8c8b-431ceef44999@demo.invalid:443?security=tls#CI-SMOKE"
        )!!
        SubscriptionVault.addManual(context, listOf(fixture))
        SubscriptionVault.select(context, fixture.id)

        ActivityScenario.launch(SubscriptionLabActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull(activity.findViewById<TextView>(R.id.nlic_status_label))
                val button = activity.findViewById<MaterialButton>(
                    R.id.nlic_server_picker_button
                )
                assertNotNull(button)
                assertTrue(button.isEnabled)
                button.performClick()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            onView(withText("CI-SMOKE  ·  VLESS"))
                .inRoot(isDialog())
                .check(matches(isDisplayed()))
                .perform(click())
            scenario.onActivity { activity ->
                val button = activity.findViewById<MaterialButton>(
                    R.id.nlic_server_picker_button
                )
                assertTrue(button.text.contains("CI-SMOKE"))
                assertTrue(activity.findViewById<TextView>(R.id.nlic_server_hint)
                    .text.isNotBlank())
            }
        }
    }
}
