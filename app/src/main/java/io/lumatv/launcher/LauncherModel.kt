package io.lumatv.launcher

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class LumaApplication : Application()

class LauncherModel(app: Application) : AndroidViewModel(app) {
    val repository = AppRepository(app)
    private val store = ConfigStore(app)
    private val _config = MutableStateFlow<LumaConfig?>(null)
    val config = _config.asStateFlow()
    private val _apps = MutableStateFlow<List<AppEntry>?>(null)
    val apps = _apps.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    val homeRequests = MutableStateFlow(0)
    private var bookmarkJob: Job? = null
    private val refreshLock = Mutex()
    var iconRevision = MutableStateFlow(0)
        private set

    init {
        viewModelScope.launch { store.config.collect { _config.value = it } }
        refresh()
    }

    fun refresh(invalidate: Boolean = false) {
        viewModelScope.launch {
            refreshLock.withLock {
                runCatching { repository.discover() }.onSuccess { apps ->
                    if (invalidate) { repository.invalidate(); iconRevision.value++ }
                    _apps.value = apps
                    warm(apps)
                }.onFailure { notify("读取应用失败，请返回后重试") }
            }
        }
    }

    /** Loads every tile's artwork once, so the first expanded frame is not a wall of placeholders. */
    private var warmJob: Job? = null
    private fun warm(apps: List<AppEntry>) {
        warmJob?.cancel()
        warmJob = viewModelScope.launch(Dispatchers.IO) {
            apps.forEach { app ->
                repository.artwork(app, preferBanner = true)
                repository.artwork(app, preferBanner = false)
            }
        }
    }

    fun change(block: (LumaConfig) -> LumaConfig) {
        viewModelScope.launch {
            runCatching { store.update(block) }.onFailure { notify("配置保存失败，请重试") }
        }
    }

    fun bookmark(route: String, mark: Bookmark) {
        bookmarkJob?.cancel()
        bookmarkJob = viewModelScope.launch {
            delay(150)
            saveBookmark(route, mark)
        }
    }

    private suspend fun saveBookmark(route: String, mark: Bookmark) = store.update {
        if (route == "home") it.copy(route = route, home = mark) else it.copy(route = route, all = mark)
    }

    fun launch(app: AppEntry, route: String, mark: Bookmark) {
        bookmarkJob?.cancel()
        viewModelScope.launch {
            // Persist before leaving: the OS may reclaim the Launcher while another app runs.
            runCatching { saveBookmark(route, mark) }.onFailure { notify("位置保存失败") }
            runCatching { repository.launch(app) }.onFailure {
                notify("无法打开 ${app.displayName}，应用可能已被移除或受到系统限制")
                refresh(true)
            }
        }
    }

    fun notify(text: String) { _message.value = text }
    fun clearMessage() { _message.value = null }

    fun defaultHome(): ComponentName? = getApplication<Application>().packageManager.resolveActivity(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addCategory(Intent.CATEGORY_DEFAULT), 0
    )?.activityInfo?.let { ComponentName(it.packageName, it.name) }

    fun isDefaultHome() = defaultHome()?.packageName == getApplication<Application>().packageName

    fun holdsHomeRole(): Boolean = android.os.Build.VERSION.SDK_INT >= 29 &&
        getApplication<Application>().getSystemService(android.app.role.RoleManager::class.java)
            .isRoleHeld(android.app.role.RoleManager.ROLE_HOME)

    fun importWallpaper(uri: Uri) {
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val context = getApplication<Application>()
                    val resolver = context.contentResolver
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                    require(options.outWidth > 0 && options.outHeight > 0) { "invalid image" }
                    var sample = 1
                    while (options.outWidth / sample > 2560 || options.outHeight / sample > 1440) sample *= 2
                    options.inJustDecodeBounds = false; options.inSampleSize = sample
                    val image = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                        ?: error("unable to decode")
                    val target = File(context.filesDir, "wallpaper.jpg")
                    val temporary = File(context.filesDir, "wallpaper.tmp")
                    try {
                        temporary.outputStream().use { require(image.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
                        require(temporary.renameTo(target)) { "unable to save" }
                    } finally { image.recycle(); temporary.delete() }
                }
                store.update { it.copy(background = "custom", wallpaperRevision = System.currentTimeMillis()) }
            }
            result.onFailure { notify("无法读取这张图片，原背景已保留") }
        }
    }
}
