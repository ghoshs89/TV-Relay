package dev.relay.tv.data

import android.app.Activity
import android.app.Application
import android.os.Bundle

/** Saves what the store still holds in memory whenever a screen of the app pauses or stops. */
class FlushOnStop(private val store: Store) : Application.ActivityLifecycleCallbacks {
    override fun onActivityPaused(activity: Activity) = store.flush()
    override fun onActivityStopped(activity: Activity) = store.flush()
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
