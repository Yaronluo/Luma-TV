package io.lumatv.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.os.Bundle
import android.os.UserHandle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : ComponentActivity() {
    val model: LauncherModel by viewModels()
    private val packages = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { model.refresh(true) }
    }
    private val launcherCallback = object : LauncherApps.Callback() {
        override fun onPackageAdded(name: String, user: UserHandle) { model.refresh(true) }
        override fun onPackageRemoved(name: String, user: UserHandle) { model.refresh(true) }
        override fun onPackageChanged(name: String, user: UserHandle) { model.refresh(true) }
        override fun onPackagesAvailable(names: Array<out String>, user: UserHandle, replacing: Boolean) { model.refresh(true) }
        override fun onPackagesUnavailable(names: Array<out String>, user: UserHandle, replacing: Boolean) { model.refresh(true) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED); addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        androidx.core.content.ContextCompat.registerReceiver(this, packages, filter, androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
        getSystemService(LauncherApps::class.java).registerCallback(launcherCallback)
        setContent { LumaTheme { LumaApp(model, onExit = { finish() }) } }
    }

    override fun onResume() { super.onResume(); model.refresh() }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) model.homeRequests.value++
    }

    override fun onDestroy() {
        unregisterReceiver(packages)
        getSystemService(LauncherApps::class.java).unregisterCallback(launcherCallback)
        super.onDestroy()
    }
}
