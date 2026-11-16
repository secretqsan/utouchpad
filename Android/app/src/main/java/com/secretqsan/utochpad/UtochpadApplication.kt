package com.secretqsan.utochpad

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * Owns the single controller for the whole process.
 *
 * The controller must outlive individual activities: opening the settings
 * screen must not tear down the WebSocket or the virtual devices. Its
 * start/stop lifecycle is therefore tied to the process (all activities), not
 * to MainActivity.
 */
class UtochpadApplication : Application() {
    lateinit var controller: UtochpadController
        private set

    override fun onCreate() {
        super.onCreate()

        controller = UtochpadController(this)

        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    controller.onStart()
                }

                override fun onStop(owner: LifecycleOwner) {
                    controller.onStop()
                }
            },
        )

        // Biometric prompts need a FragmentActivity. Track whichever one is
        // currently resumed; the unlock request can arrive while either the
        // touchpad or the settings activity is showing.
        registerActivityLifecycleCallbacks(
            object : ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    if (activity is FragmentActivity) controller.attachActivity(activity)
                }

                override fun onActivityPaused(activity: Activity) {
                    if (activity is FragmentActivity) controller.detachActivity(activity)
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
    }
}
