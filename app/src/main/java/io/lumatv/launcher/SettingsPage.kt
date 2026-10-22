package io.lumatv.launcher

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*

@Composable
fun SettingsPage(config: LumaConfig, model: LauncherModel, tab: String, restoreContent: Boolean, onTab: (String) -> Unit,
    onHidden: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val tabs = listOf("外观", "应用管理", "默认桌面", "关于")
    val tabRequesters = remember { tabs.associateWith { FocusRequester() } }
    val first = remember { FocusRequester() }
    val brightnessFocus = remember { FocusRequester() }
    var appearanceDialog by remember { mutableStateOf<String?>(null) }
    var appearanceReturn by remember { mutableStateOf<String?>(null) }
    var inContent by remember { mutableStateOf(restoreContent) }
    var enterContent by remember { mutableStateOf<String?>(null) }
    val pendingContentKeys = remember { mutableListOf<android.view.KeyEvent>() }
    var status by remember { mutableStateOf(model.defaultHome()?.flattenToShortString().orEmpty()) }
    var roleHeld by remember { mutableStateOf(model.holdsHomeRole()) }
    val homeAlias = remember { ComponentName(context, "io.lumatv.launcher.HomeEntry") }
    var candidateEnabled by remember {
        mutableStateOf(context.packageManager.getComponentEnabledSetting(homeAlias) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
    }
    val homeResult = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        status = model.defaultHome()?.flattenToShortString().orEmpty()
        roleHeld = model.holdsHomeRole()
        model.notify(when {
            model.isDefaultHome() -> "系统默认项已设置，请按 Home 确认实际行为"
            roleHeld -> "系统已接受设置，但 Home 仍指向其他桌面"
            else -> "当前仍使用原桌面，可在此查看状态"
        })
    }
    val wallpaperResult = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.importWallpaper(uri)
    }
    fun openHomeSettings() {
        runCatching { homeResult.launch(Intent(Settings.ACTION_HOME_SETTINGS)) }
            .onFailure { model.notify("系统未提供桌面选择页面，请在电视系统设置中查看默认应用") }
    }
    fun selectHome() {
        val existing = model.defaultHome()
        if (existing != null && existing.packageName != context.packageName && config.originalHome.isEmpty()) {
            model.change { it.copy(originalHome = existing.flattenToString()) }
        }
        context.packageManager.setComponentEnabledSetting(ComponentName(context, "io.lumatv.launcher.HomeEntry"),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        candidateEnabled = true
        val role = if (android.os.Build.VERSION.SDK_INT >= 29) context.getSystemService(RoleManager::class.java) else null
        if (android.os.Build.VERSION.SDK_INT >= 29 && role != null && role.isRoleAvailable(RoleManager.ROLE_HOME)) {
            runCatching { homeResult.launch(role.createRequestRoleIntent(RoleManager.ROLE_HOME)) }
                .onFailure { openHomeSettings() }
        } else openHomeSettings()
    }
    fun contentKey(event: KeyEvent): Boolean {
        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft) {
            enterContent = null; inContent = false; tabRequesters.getValue(tab).requestFocus(); return true
        }
        return false
    }

    androidx.activity.compose.BackHandler {
        pendingContentKeys.clear()
        if (inContent) { enterContent = null; inContent = false; tabRequesters.getValue(tab).requestFocus() } else onBack()
    }
    Column(Modifier.fillMaxSize().onPreviewKeyEvent { event ->
        // A rapid Right, Down, Confirm can arrive before the right pane has mounted.
        // Preserve that sequence rather than letting the old category consume it.
        if (enterContent != null && event.key in listOf(Key.DirectionDown, Key.DirectionUp, Key.DirectionCenter, Key.Enter, Key.NumPadEnter)) {
            pendingContentKeys.add(android.view.KeyEvent(event.nativeKeyEvent))
            true
        } else if (enterContent != null && event.key == Key.DirectionLeft) {
            pendingContentKeys.clear(); enterContent = null; inContent = false
            tabRequesters.getValue(tab).requestFocus()
            true
        } else false
    }.padding(horizontal = (config.safeInset + 20).dp, vertical = 28.dp).testTag("page:settings")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Luma TV", color = Ink.copy(alpha = .73f), fontSize = 15.sp)
            Spacer(Modifier.weight(1f))
            LumaClock()
        }
        Spacer(Modifier.height(36.dp))
        Text("设置", color = Ink, fontSize = 32.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(25.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(55.dp)) {
            Column(Modifier.width(242.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                tabs.forEachIndexed { index, name ->
                    LumaButton(if (name == "关于") "关于 Luma TV" else name, "tab:$name", Modifier.fillMaxWidth(), tabRequesters.getValue(name),
                        active = tab == name, flat = true, onFocus = { enterContent = null; inContent = false; onTab(name) },
                        onKey = {
                            if (it.type == KeyEventType.KeyDown) when (it.key) {
                                Key.DirectionRight -> { inContent = true; onTab(name); enterContent = name; true }
                                Key.DirectionUp -> { tabRequesters.getValue(tabs[(index - 1).coerceAtLeast(0)]).requestFocus(); true }
                                Key.DirectionDown -> { tabRequesters.getValue(tabs[(index + 1).coerceAtMost(tabs.lastIndex)]).requestFocus(); true }
                                else -> false
                            } else false
                        }, onClick = { inContent = true; onTab(name); enterContent = name })
                }
            }
            key(tab) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(if (tab == "外观") 4.dp else 12.dp)) {
                    when (tab) {
                        "外观" -> {
                            PreferenceRow("背景", when (config.background) { "ocean" -> "深海"; "warm" -> "暖雾"; "custom" -> "本地图片"; else -> "默认" },
                                "appearance-background", first, onFocus = { inContent = true }, onKey = ::contentKey,
                                onClick = { appearanceDialog = "background" })
                            PreferenceRow("背景亮度", listOf("较暗", "标准", "较亮")[config.brightness], "appearance-brightness", brightnessFocus,
                                onFocus = { inContent = true }, onKey = ::contentKey, onClick = { appearanceDialog = "brightness" })
                            PreferenceRow("背景模糊", if (config.backgroundBlur) "开启" else "关闭", "background-blur",
                                onFocus = { inContent = true }, onKey = ::contentKey,
                                onClick = { model.change { it.copy(backgroundBlur = !it.backgroundBlur) } })
                            Text("低配电视可关闭背景模糊", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(start = 18.dp, bottom = 6.dp))
                            PreferenceRow("减少动态效果", if (config.reducedMotion) "开启" else "关闭", "motion",
                                onFocus = { inContent = true }, onKey = ::contentKey,
                                onClick = { model.change { it.copy(reducedMotion = !it.reducedMotion) } })
                            val edgeLabel = when (config.safeInset) { 56 -> "加宽"; 64 -> "更宽"; else -> "标准" }
                            PreferenceRow("屏幕安全边距", edgeLabel, "safe-inset",
                                onFocus = { inContent = true }, onKey = ::contentKey,
                                onClick = { model.change { it.copy(safeInset = when (it.safeInset) { 48 -> 56; 56 -> 64; else -> 48 }) } })
                            Row(Modifier.padding(top = 22.dp, start = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                listOf(Color(0xFFAEB7B4) to Color(0xFF496278), Color(0xFF7C9DA5) to Color(0xFF214555),
                                    Color(0xFFBCAF9B) to Color(0xFF685C64)).forEach { (light, dark) ->
                                    Box(Modifier.size(108.dp, 64.dp).background(Brush.linearGradient(listOf(light, dark)), RoundedCornerShape(9.dp)))
                                }
                            }
                        }
                        "应用管理" -> {
                            Text("管理应用可见性", color = Ink, fontSize = 23.sp)
                            Text("隐藏的应用不会出现在首页和全部应用中。\n恢复后保留原收藏与排序设置。", color = Muted, fontSize = 17.sp, lineHeight = 27.sp)
                            LumaButton("查看已隐藏应用（${config.hidden.size}）", "hidden-apps", requester = first,
                                onFocus = { inContent = true }, onKey = ::contentKey, onClick = onHidden)
                        }
                        "默认桌面" -> {
                            Text(when {
                                model.isDefaultHome() -> "默认桌面：Luma TV"
                                roleHeld -> "此系统尚未将 Home 交给 Luma TV"
                                else -> "当前使用其他桌面"
                            }, color = Ink, fontSize = 23.sp)
                            Text("当前桌面：$status", color = Muted, fontSize = 15.sp)
                            if (roleHeld && !model.isDefaultHome()) Text("系统已接受申请，但 Home 仍进入其他桌面。\n这台设备可能限制更换桌面，请保留原桌面。", color = Accent, fontSize = 16.sp, lineHeight = 25.sp)
                            LumaButton("设为默认桌面", "request-home", requester = first,
                                onFocus = { inContent = true }, onKey = ::contentKey, onClick = { selectHome() })
                            LumaButton("打开系统桌面选择", "home-settings", onFocus = { inContent = true }, onKey = ::contentKey,
                                onClick = { openHomeSettings() })
                            if (candidateEnabled && !model.isDefaultHome()) LumaButton("恢复普通应用模式", "ordinary-mode",
                                onFocus = { inContent = true }, onKey = ::contentKey, onClick = {
                                    if (!model.isDefaultHome()) runCatching {
                                        context.packageManager.setComponentEnabledSetting(homeAlias,
                                            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP)
                                        first.requestFocus()
                                        candidateEnabled = false
                                        model.notify("已恢复普通应用模式，系统桌面保持可用")
                                    }.onFailure { model.notify("暂时无法恢复，请先在系统设置中选择原桌面") }
                                })
                            Text("恢复原桌面", color = Ink, fontSize = 20.sp, modifier = Modifier.padding(top = 6.dp))
                            Text("在系统桌面选择中重新选择原桌面。原桌面会一直保留。\n部分电视固定了 Home 行为，系统可能不允许切换。", color = Muted, fontSize = 16.sp, lineHeight = 25.sp)
                            if (config.originalHome.isNotEmpty()) Text("切换前桌面：${config.originalHome}", color = Muted, fontSize = 14.sp)
                        }
                        else -> {
                            Text("Luma TV", color = Ink, fontSize = 28.sp)
                            Text("${BuildConfig.VERSION_NAME} · 为客厅而设计", color = Muted, fontSize = 17.sp)
                            Text("首页、收藏和设置均可离线使用。\n没有账号、广告、遥测或后台常驻服务。\n仅在本机保存应用排序、偏好和背景图片。", color = Muted, fontSize = 17.sp, lineHeight = 28.sp)
                            LumaButton("返回首页", "about-home", requester = first, onFocus = { inContent = true }, onKey = ::contentKey, onClick = onBack)
                        }
                    }
                }
                LaunchedEffect(tab, enterContent) {
                    // Fast category changes can dispose the old right-hand focus target.
                    // Wait for the requested category's content, rather than focusing the old tree.
                    if (enterContent == tab) {
                        withFrameNanos { }
                        first.requestFocus()
                        enterContent = null
                        inContent = true
                        val replay = pendingContentKeys.toList()
                        pendingContentKeys.clear()
                        val activity = context as? android.app.Activity
                        replay.forEach { activity?.window?.decorView?.dispatchKeyEvent(it) }
                    }
                }
            }
        }
    }
    appearanceDialog?.let { kind ->
        val background = kind == "background"
        val choices = if (background) listOf("dusk" to "默认", "ocean" to "深海", "warm" to "暖雾", "custom" to "本地图片")
            else listOf("0" to "较暗", "1" to "标准", "2" to "较亮")
        val selected = if (background) config.background else config.brightness.toString()
        PreferenceChoices(if (background) "背景" else "背景亮度", kind, choices, selected,
            onClose = { appearanceDialog = null; appearanceReturn = kind },
            onSelect = { id ->
                appearanceDialog = null; appearanceReturn = kind
                if (background && id == "custom") {
                    runCatching { wallpaperResult.launch(arrayOf("image/*")) }
                        .onFailure { model.notify("此设备没有文件选择器，请使用内置背景") }
                } else if (background) model.change { it.copy(background = id) }
                else model.change { it.copy(brightness = id.toInt()) }
            })
    }
    LaunchedEffect(appearanceReturn) {
        appearanceReturn?.let {
            withFrameNanos { }
            if (it == "background") first.requestFocus() else brightnessFocus.requestFocus()
            appearanceReturn = null
        }
    }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        if (restoreContent) first.requestFocus() else tabRequesters.getValue(tab).requestFocus()
    }
}

@Composable
private fun PreferenceRow(label: String, value: String, tag: String, requester: FocusRequester? = null,
    onFocus: () -> Unit, onKey: (KeyEvent) -> Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth()
        .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
        .onPreviewKeyEvent(onKey).onFocusChanged { focused = it.isFocused; if (focused) onFocus() }
        .shadow(if (focused) 12.dp else 0.dp, RoundedCornerShape(10.dp), clip = false).testTag(tag),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color(0x32233647), focusedContainerColor = Ink,
            contentColor = Ink, focusedContentColor = Color(0xFF26343E)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.025f),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(0.dp, Color.Transparent)))) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 18.sp)
            Spacer(Modifier.weight(1f))
            Text(value, fontSize = 16.sp, color = if (focused) Color(0xFF66747C) else Muted)
            Text("›", Modifier.padding(start = 16.dp), fontSize = 22.sp, color = if (focused) Color(0xFF66747C) else Muted)
        }
    }
}

@Composable
private fun PreferenceChoices(title: String, kind: String, choices: List<Pair<String, String>>, selected: String,
    onClose: () -> Unit, onSelect: (String) -> Unit) {
    val requesters = remember { choices.associate { it.first to FocusRequester() } }
    Dialog(onDismissRequest = onClose) {
        Column(Modifier.width(340.dp).background(Panel, RoundedCornerShape(22.dp)).padding(26.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = Ink, fontSize = 25.sp, modifier = Modifier.padding(bottom = 10.dp))
            choices.forEach { (id, label) ->
                LumaButton(label, "$kind:$id", Modifier.fillMaxWidth(), requesters.getValue(id), active = selected == id,
                    onClick = { onSelect(id) })
            }
        }
        LaunchedEffect(Unit) { withFrameNanos { }; requesters.getValue(selected.takeIf { it in requesters } ?: choices.first().first).requestFocus() }
    }
}
