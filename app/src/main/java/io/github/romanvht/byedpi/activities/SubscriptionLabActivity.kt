package io.github.romanvht.byedpi.activities

import android.os.Bundle
import io.github.romanvht.byedpi.R
import io.github.romanvht.byedpi.subscription.SubscriptionLabFragment

/** This is a subscription *preview*, not a second Android VPN service. */
class SubscriptionLabActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        setupToolbar()
        supportActionBar?.setTitle(R.string.nlic_subscriptions_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings, SubscriptionLabFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
