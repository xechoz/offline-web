package io.github.xechoz.offlineweb.demo

import android.app.Application
import android.util.Log
import io.github.xechoz.offlineweb.ExpiryPolicy
import io.github.xechoz.offlineweb.OfflineConfig
import io.github.xechoz.offlineweb.OfflineWeb
import io.github.xechoz.offlineweb.StartupVerification

class DemoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        OfflineWeb.init(
            context = this,
            config = OfflineConfig(
                appKey = "demo",
                endpoint = BuildConfig.OFFLINE_ENDPOINT,
                trustedKeys = mapOf("demo-2026-01" to BuildConfig.OFFLINE_PUBLIC_KEY),
                clientVersion = 1,
                allowCleartext = true,
                expiryPolicy = ExpiryPolicy.STRICT,
                verifyOnStartup = StartupVerification.SIZE_ONLY,
                logger = { message -> Log.d("OfflineWeb", message) },
            ),
        )
    }
}
