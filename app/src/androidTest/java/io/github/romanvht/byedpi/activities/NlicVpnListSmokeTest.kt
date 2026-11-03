package io.github.romanvht.byedpi.activities

import android.content.Context
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.textfield.MaterialAutoCompleteTextView
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
                val picker = activity.findViewById<MaterialAutoCompleteTextView>(
                    R.id.nlic_server_picker
                )
                assertNotNull(picker)
                assertTrue(picker.adapter.count >= 1)
                picker.showDropDown()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val picker = activity.findViewById<MaterialAutoCompleteTextView>(
                    R.id.nlic_server_picker
                )
                picker.dismissDropDown()
                assertTrue(activity.findViewById<TextView>(R.id.nlic_server_hint)
                    .text.isNotBlank())
            }
        }
    }
}
