package com.walkman.tv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.SurroundSound
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.walkman.tv.ui.components.FolderBrowser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.walkman.tv.data.model.Quality
import com.walkman.tv.di.getLanIp
import com.walkman.tv.ui.appContainer
import com.walkman.tv.ui.components.QrDialog
import com.walkman.tv.ui.components.TvFocusable
import com.walkman.tv.ui.components.TvPill
import com.walkman.tv.ui.components.TvTextFieldHost
import com.walkman.tv.ui.theme.AppColors
import kotlinx.coroutines.launch

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val importFocus = remember { FocusRequester() }
    val settings by appContainer.settingsStore.settings.collectAsState()
    val scripts by appContainer.scriptStore.scripts.collectAsState()
    var url by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var showQr by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<com.walkman.tv.data.model.UserScript?>(null) }

    // System file picker (SAF) for .js scripts.
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                status = "正在讀取檔案…"
                val raw = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use {
                            it.readBytes().toString(Charsets.UTF_8)
                        }
                    }.getOrNull()
                }
                if (raw.isNullOrEmpty()) {
                    status = "讀取檔案失敗"
                } else {
                    status = "正在匯入檔案…"
                    val r = appContainer.scriptStore.import(raw)
                    status = r.fold({ it.message }, { "匯入失敗：${it.message}" })
                }
            }
        }
    }

    // In-app File browser for the download folder (TVs often lack a SAF picker). Writing needs
    // WRITE_EXTERNAL_STORAGE on API ≤ 29, or All-files access on 30+.
    var showDownloadBrowser by remember { mutableStateOf(false) }
    var dlPermHint by remember { mutableStateOf(false) }
    val dlPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) showDownloadBrowser = true else dlPermHint = true }

    fun openDownloadPicker() {
        if (hasStorageWriteAccess(context)) {
            showDownloadBrowser = true
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            dlPermHint = true
            runCatching {
                context.startActivity(
                    android.content.Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure {
                runCatching {
                    context.startActivity(
                        android.content.Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        } else {
            dlPermLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    // Receive script payloads from the phone-to-TV QR flow.
    LaunchedEffect(Unit) {
        appContainer.events.qrScriptUrl.collect { u ->
            showQr = false
            importFromUrl(scope, u) { status = it }
        }
    }
    LaunchedEffect(Unit) {
        appContainer.events.qrScriptText.collect { raw ->
            showQr = false
            status = "正在匯入上傳的指令碼…"
            val r = appContainer.scriptStore.import(raw)
            status = r.fold({ it.message }, { "匯入失敗：${it.message}" })
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(top = 8.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Section("播放音質") {
            // 8 tiers including extended (hires/atmos/master) — wrap so they fit on any TV width.
            // Each pill carries a small icon to make the tier intent scannable in one glance.
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Quality.orderedHighToLow.reversed().forEach { q ->
                    TvPill(
                        onClick = { scope.launch { appContainer.settingsStore.update { it.copy(preferredQuality = q) } } },
                        selected = settings.preferredQuality == q,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.tv.material3.Icon(
                                imageVector = qualityIcon(q),
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(q.displayName, fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        Section("探索頁音源") {
            // Toggle each source in/out. The discover (home) page subtitle and section
            // contents track this list in real time — see HomeStore.loadIfNeeded.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.walkman.tv.data.model.SourceID.homePageOrder.forEach { src ->
                    val active = src in settings.homeSources
                    TvPill(
                        onClick = {
                            scope.launch {
                                appContainer.settingsStore.update { s ->
                                    val next = if (active) s.homeSources - src else s.homeSources + src
                                    // Spec §8: never let the set collapse to empty — keep at least
                                    // one source so the discover page stays usable.
                                    s.copy(homeSources = if (next.isEmpty()) s.homeSources else next)
                                }
                            }
                        },
                        selected = active,
                    ) {
                        Text(src.displayName, fontSize = 13.sp)
                    }
                }
            }
        }

        Section("歌詞翻譯") {
            ToggleRow("顯示歌詞翻譯（外語歌曲有翻譯時，會顯示在每句歌詞下方）", settings.showLyricTranslation) {
                scope.launch { appContainer.settingsStore.update { it.copy(showLyricTranslation = !it.showLyricTranslation) } }
            }
        }

        Section("歌詞大小") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.walkman.tv.data.store.LyricSize.entries.forEach { size ->
                    TvPill(
                        onClick = { scope.launch { appContainer.settingsStore.update { it.copy(lyricSize = size) } } },
                        selected = settings.lyricSize == size,
                    ) {
                        Text(size.label, fontSize = 13.sp)
                    }
                }
            }
        }

        Section("黑膠唱片顏色") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.walkman.tv.data.store.VinylColor.entries.forEach { color ->
                    TvPill(
                        onClick = { scope.launch { appContainer.settingsStore.update { it.copy(vinylColor = color) } } },
                        selected = settings.vinylColor == color,
                    ) {
                        Text(color.label, fontSize = 13.sp)
                    }
                }
            }
        }

        Section("播放畫面") {
            ToggleRow("自動隱藏播放控制項", settings.autoHidePlayerControls) {
                scope.launch { appContainer.settingsStore.update { it.copy(autoHidePlayerControls = !it.autoHidePlayerControls) } }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "開啟後，播放畫面閒置 5 秒會自動隱藏進度列與播放按鈕；按方向鍵或確認鍵即可重新顯示。",
                color = AppColors.TextMuted,
                fontSize = 11.sp,
            )
        }

        Section("下載資料夾") {
            val roots = remember { appContainer.downloadStore.availableRoots() }
            val treeUriStr = settings.customDownloadTreeUri
            val usingTree = treeUriStr != null
            // A folder picked via the in-app browser is a customDownloadDir that isn't one of the
            // app-scoped volume roots.
            val usingCustomFolder = settings.customDownloadDir != null &&
                roots.none { it.dir.absolutePath == settings.customDownloadDir }
            // Current volume = configured path, or the first volume (default) when unset. Only
            // highlighted when a SAF folder is NOT in use.
            val currentPath = settings.customDownloadDir
                ?: roots.firstOrNull()?.dir?.absolutePath
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                roots.forEachIndexed { index, opt ->
                    val path = opt.dir.absolutePath
                    TvPill(
                        onClick = {
                            scope.launch {
                                appContainer.settingsStore.update {
                                    // Picking a volume clears any SAF folder. Store null for the
                                    // default (first) volume so it keeps following the system default.
                                    it.copy(
                                        customDownloadDir = if (index == 0) null else path,
                                        customDownloadTreeUri = null,
                                    )
                                }
                            }
                        },
                        selected = !usingTree && path == currentPath,
                    ) {
                        Text(opt.label, fontSize = 13.sp)
                    }
                }
                // Pick any folder via the in-app browser (incl. USB/SD) — no SAF needed.
                TvPill(
                    onClick = { openDownloadPicker() },
                    selected = usingTree || usingCustomFolder,
                ) {
                    Text("選擇其他資料夾…", fontSize = 13.sp)
                }
            }
            if (dlPermHint) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "需要儲存空間寫入權限：請在系統設定中允許「隨便聽」存取檔案（或授予儲存空間權限），返回後再點選擇。",
                    color = AppColors.Warning,
                    fontSize = 11.sp,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    usingTree -> "已選擇資料夾：" + (
                        treeUriStr?.let { runCatching { appContainer.downloadStore.treeDisplayName(Uri.parse(it)) }.getOrNull() }
                            ?: "自訂資料夾"
                        )
                    usingCustomFolder -> "已選擇資料夾：" + (settings.customDownloadDir ?: "")
                    else -> currentPath ?: "（應用程式預設音樂資料夾）"
                },
                color = AppColors.TextMuted,
                fontSize = 11.sp,
            )
            Text(
                if (usingTree || usingCustomFolder) {
                    "下載內容會儲存至你選擇的資料夾（可透過檔案管理員或其他應用程式瀏覽）。變更資料夾只會影響後續下載。"
                } else {
                    "儲存磁碟區目錄位於應用程式專屬空間（解除安裝時會一併清除，且不易在檔案管理員中檢視）；若要儲存至可見位置，請點選「選擇其他資料夾」。變更僅影響後續下載，已下載的歌曲仍可正常播放。"
                },
                color = AppColors.TextMuted,
                fontSize = 11.sp,
            )
        }

        Section("同時下載數量") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..6).forEach { n ->
                    TvPill(
                        onClick = { scope.launch { appContainer.settingsStore.update { it.copy(maxConcurrentDownloads = n) } } },
                        selected = settings.maxConcurrentDownloads == n,
                    ) {
                        Text("$n", fontSize = 13.sp)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "同時下載的歌曲數量上限（批次下載時會依序排隊；數量越多速度可能越快，但也會占用更多頻寬）。預設為 3。",
                color = AppColors.TextMuted,
                fontSize = 11.sp,
            )
        }

        Section("自訂音源") {
            Text(
                "貼上 URL 可自動從遠端載入；直接貼上指令碼則會以 JS 程式碼匯入；也可上傳 .js 檔案，或掃描 QR Code 透過手機／電腦操作。",
                color = AppColors.TextMuted,
                fontSize = 12.sp,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TvTextFieldHost(modifier = Modifier.weight(1f), shape = RoundedCornerShape(4.dp)) { fieldModifier ->
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(fieldModifier),
                        placeholder = { Text("URL（http://...）或直接貼上 JS 程式碼", color = AppColors.TextMuted) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { importInput(scope, url) { status = it } }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = AppColors.TextPrimary,
                            unfocusedTextColor = AppColors.TextPrimary,
                            focusedBorderColor = AppColors.AccentGreen,
                            unfocusedBorderColor = AppColors.Card,
                            cursorColor = AppColors.AccentGreen,
                        ),
                    )
                }
                Spacer(Modifier.width(8.dp))
                TvPill(
                    onClick = { importInput(scope, url) { status = it } },
                    selected = true,
                    focusRequester = importFocus,
                ) {
                    Text("匯入", fontSize = 14.sp)
                }
                Spacer(Modifier.width(8.dp))
                TvPill(
                    onClick = {
                        runCatching {
                            filePicker.launch(arrayOf("application/javascript", "text/javascript", "application/x-javascript", "text/plain", "*/*"))
                        }.onFailure { status = "找不到可用的檔案選擇器" }
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("上傳檔案", fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.width(8.dp))
                TvPill(
                    onClick = { showQr = true },
                    shape = CircleShape,
                    contentPadding = PaddingValues(10.dp),
                ) {
                    Icon(Icons.Filled.QrCode2, contentDescription = "掃碼匯入", modifier = Modifier.size(22.dp))
                }
            }
            status?.let { Text(it, color = AppColors.TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }

            Spacer(Modifier.padding(top = 6.dp))
            if (scripts.isEmpty()) {
                Text("尚未匯入任何指令碼", color = AppColors.TextMuted, fontSize = 13.sp)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    scripts.forEach { s ->
                        // Two side-by-side focusables (toggle area + delete pill). The delete
                        // path used to live on the UI's rememberCoroutineScope, which gets
                        // cancelled when the user navigates away mid-click; route through
                        // appContainer.appScope so the unload + filter + persist sequence
                        // can't be interrupted between steps.
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TvFocusable(
                                onClick = { scope.launch { appContainer.scriptStore.setEnabled(s.id, !s.enabled) } },
                                modifier = Modifier.weight(1f),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(s.name, color = AppColors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                        Text("v${s.version}  ${s.author}", color = AppColors.TextMuted, fontSize = 12.sp)
                                    }
                                    Text(
                                        if (s.enabled) "已啟用" else "已停用",
                                        color = if (s.enabled) AppColors.AccentGreen else AppColors.TextMuted,
                                        fontSize = 12.sp,
                                    )
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            TvPill(
                                onClick = { pendingDelete = s },
                                accent = AppColors.Danger,
                                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                            ) { Text("刪除", fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }
        }

    }

    pendingDelete?.let { target ->
        DeleteScriptConfirmDialog(
            scriptName = target.name,
            onCancel = { pendingDelete = null },
            onConfirm = {
                // Use the appScope (process-lived) so the unload + filter + persist sequence
                // can't be cancelled by leaving Settings mid-delete.
                pendingDelete = null
                appContainer.appScope.launch {
                    appContainer.scriptStore.remove(target.id)
                    status = "已刪除：${target.name}"
                }
            },
        )
    }

    if (showQr) {
        val ip = remember { getLanIp() }
        val port = appContainer.localServer?.boundPort
        if (ip != null && port != null) {
            QrDialog(
                url = "http://$ip:$port/script",
                title = "掃碼匯入音源",
                subtitle = "可使用手機掃碼或透過電腦開啟網址，並貼上指令碼 URL 或上傳 .js 檔案",
                onDismiss = { showQr = false },
            )
        } else {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showQr = false },
                title = { Text("無法啟動掃碼") },
                text = { Text("請確認電視已連線至 Wi-Fi，且本機服務已啟動後再試。") },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = { showQr = false }) {
                        Text("好", color = AppColors.AccentGreen)
                    }
                },
                containerColor = AppColors.BgPanel,
                titleContentColor = AppColors.TextPrimary,
                textContentColor = AppColors.TextSecondary,
            )
        }
    }

    if (showDownloadBrowser) {
        FolderBrowser(
            onPick = { dir ->
                dlPermHint = false
                showDownloadBrowser = false
                scope.launch {
                    appContainer.settingsStore.update {
                        it.copy(customDownloadDir = dir.absolutePath, customDownloadTreeUri = null)
                    }
                }
            },
            onCancel = { showDownloadBrowser = false },
        )
    }
}

/** Whether we can write to arbitrary folders with java.io.File right now. */
private fun hasStorageWriteAccess(ctx: android.content.Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            ctx,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

/**
 * Auto-detects the input: if it looks like an http(s) URL, fetches the URL and imports the
 * body; otherwise treats the input as raw JS code and imports it directly. Used by both the
 * "匯入" button and the IME "完成" action.
 */
private fun importInput(scope: kotlinx.coroutines.CoroutineScope, input: String, onStatus: (String) -> Unit) {
    if (input.isBlank()) return
    val trimmed = input.trim()
    val isUrl = trimmed.startsWith("http://", ignoreCase = true) ||
        trimmed.startsWith("https://", ignoreCase = true)
    scope.launch {
        onStatus(if (isUrl) "正在下載指令碼…" else "正在匯入指令碼…")
        val result = runCatching {
            val raw = if (isUrl) appContainer.fetchText(trimmed) else trimmed
            appContainer.scriptStore.import(raw).getOrThrow()
        }
        onStatus(result.fold({ it.message }, { "匯入失敗：${it.message}" }))
    }
}

// Kept for backwards-compat callers that already exist; just delegates.
private fun importFromUrl(scope: kotlinx.coroutines.CoroutineScope, url: String, onStatus: (String) -> Unit) {
    if (url.isBlank()) return
    scope.launch {
        onStatus("正在匯入…")
        val result = runCatching {
            val raw = appContainer.fetchText(url.trim())
            appContainer.scriptStore.import(raw).getOrThrow()
        }
        onStatus(result.fold({ it.message }, { "匯入失敗：${it.message}" }))
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = AppColors.AccentGreen, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun ToggleRow(label: String, on: Boolean, onToggle: () -> Unit) {
    TvFocusable(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = AppColors.TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(if (on) "開啟" else "關閉", color = if (on) AppColors.AccentGreen else AppColors.TextMuted, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Per-tier Material icon — small visual cue alongside the displayName in the picker.
 *  Matches iOS's per-tier symbol convention (spec §4) using Material's closest analogues. */
private fun qualityIcon(q: Quality): androidx.compose.ui.graphics.vector.ImageVector = when (q) {
    Quality.K128 -> Icons.Filled.MusicNote
    Quality.K320 -> Icons.Filled.GraphicEq
    Quality.FLAC -> Icons.Filled.HighQuality
    Quality.FLAC24, Quality.HIRES -> Icons.Filled.AutoAwesome
    Quality.ATMOS, Quality.ATMOS_PLUS -> Icons.Filled.SurroundSound
    Quality.MASTER -> Icons.Filled.Diamond
}

@Composable
private fun DeleteScriptConfirmDialog(
    scriptName: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onCancel,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .width(360.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(AppColors.BgPanel)
                .padding(horizontal = 24.dp, vertical = 22.dp),
        ) {
            Text("刪除自訂音源", color = AppColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(
                "確定要刪除「$scriptName」嗎？刪除後需要重新匯入才能使用。",
                color = AppColors.TextSecondary,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(22.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            ) {
                TvPill(
                    onClick = onCancel,
                    focusRequester = cancelFocus,
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp),
                ) {
                    Text("取消", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                TvPill(
                    onClick = onConfirm,
                    selected = true,
                    accent = AppColors.Danger,
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp),
                ) {
                    Text("確定", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
