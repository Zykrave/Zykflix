package com.zykrave.zykflix

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import java.security.Security
import org.conscrypt.Conscrypt
import com.zykrave.zykflix.database.AppDatabase
import com.zykrave.zykflix.models.TvShow
import com.zykrave.zykflix.models.Video
import com.zykrave.zykflix.providers.AniWorldProvider
import com.zykrave.zykflix.providers.MiruroProvider
import com.zykrave.zykflix.sync.CloudSyncManager
import com.zykrave.zykflix.sync.SupabaseProvider
import com.zykrave.zykflix.utils.AppLanguageManager
import com.zykrave.zykflix.utils.ArtworkRepairScheduler
import com.zykrave.zykflix.utils.CacheUtils
import com.zykrave.zykflix.utils.DnsResolver
import com.zykrave.zykflix.utils.IsrgRootTrustProvider
import com.zykrave.zykflix.utils.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ZykflixApp : Application() {
    companion object {
        lateinit var instance: ZykflixApp
            private set

        @Volatile
        var currentActivity: Activity? = null
            private set
    }

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguageManager.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

            override fun onActivityStarted(activity: Activity) = Unit

            override fun onActivityResumed(activity: Activity) {
                currentActivity = activity
            }

            override fun onActivityPaused(activity: Activity) {
                if (currentActivity === activity) {
                    currentActivity = null
                }
            }

            override fun onActivityStopped(activity: Activity) = Unit

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

            override fun onActivityDestroyed(activity: Activity) {
                if (currentActivity === activity) {
                    currentActivity = null
                }
            }
        })

        // 0. Initialize Conscrypt for modern SSL on old Android
        Security.insertProviderAt(Conscrypt.newProvider(), 1)

        // 1. Install ISRG Root X1 globally for Let's Encrypt. On Android < 7 (API 24)
        // network_security_config.xml is not supported so the certificate must be injected manually.
        IsrgRootTrustProvider.install()

        // 2. Inizializzazione preferenze (con applicationContext)
        UserPreferences.setup(this)
        DnsResolver.setDnsUrl(UserPreferences.dohProviderUrl)

        val appContext = applicationContext
        val isTv = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        val threshold = if (isTv) 10L else 50L

        applicationScope.launch(Dispatchers.IO) {
            AppDatabase.setup(appContext)
            SupabaseProvider.initialize(appContext)
            runCatching { CloudSyncManager.initialize(appContext) }
            AniWorldProvider.initialize(appContext)
            ArtworkRepairScheduler.schedule(appContext, UserPreferences.currentProvider)
            CacheUtils.autoClearIfNeeded(appContext, thresholdMb = threshold)
        }

    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            CacheUtils.clearAppCache(this)
        }
    }
}
