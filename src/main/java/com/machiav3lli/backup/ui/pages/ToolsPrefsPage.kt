package com.machiav3lli.backup.ui.pages

import android.annotation.SuppressLint
import android.content.Context
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.machiav3lli.backup.DamagedOp
import com.machiav3lli.backup.DialogMode
import com.machiav3lli.backup.NeoApp
import com.machiav3lli.backup.PREFS_EXTENSION
import com.machiav3lli.backup.PREFS_MIME_ARRAY
import com.machiav3lli.backup.PREFS_MIME_TYPE
import com.machiav3lli.backup.R
import com.machiav3lli.backup.data.entity.LinkPref
import com.machiav3lli.backup.data.entity.Package
import com.machiav3lli.backup.data.entity.Pref
import com.machiav3lli.backup.data.entity.SortFilterModel
import com.machiav3lli.backup.data.preferences.NeoPrefs
import com.machiav3lli.backup.manager.handler.BackupRestoreHelper
import com.machiav3lli.backup.manager.handler.findBackups
import com.machiav3lli.backup.manager.handler.showNotification
import com.machiav3lli.backup.ui.activities.NeoActivity
import com.machiav3lli.backup.ui.compose.component.LaunchPreference
import com.machiav3lli.backup.ui.compose.component.PrefsGroup
import com.machiav3lli.backup.ui.compose.icons.Phosphor
import com.machiav3lli.backup.ui.compose.icons.phosphor.AndroidLogo
import com.machiav3lli.backup.ui.compose.icons.phosphor.ArrowsClockwise
import com.machiav3lli.backup.ui.compose.icons.phosphor.Broom
import com.machiav3lli.backup.ui.compose.icons.phosphor.Bug
import com.machiav3lli.backup.ui.compose.icons.phosphor.CalendarX
import com.machiav3lli.backup.ui.compose.icons.phosphor.Hash
import com.machiav3lli.backup.ui.compose.icons.phosphor.ListNumbers
import com.machiav3lli.backup.ui.compose.icons.phosphor.TrashSimple
import com.machiav3lli.backup.ui.compose.show
import com.machiav3lli.backup.ui.dialogs.ActionsDialogUI
import com.machiav3lli.backup.ui.dialogs.BaseDialog
import com.machiav3lli.backup.ui.navigation.NavRoute
import com.machiav3lli.backup.utils.BACKUP_DATE_TIME_FORMATTER
import com.machiav3lli.backup.utils.SystemUtils
import com.machiav3lli.backup.utils.currentTimestamp
import com.machiav3lli.backup.utils.applyFilter
import com.machiav3lli.backup.utils.extensions.koinNeoViewModel
import com.machiav3lli.backup.viewmodels.HomeVM
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber
import org.koin.java.KoinJavaComponent.get
import java.io.BufferedOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime


private val neoPrefs: NeoPrefs by lazy { get(NeoPrefs::class.java) }

private val unexportableLegacyPreferenceKeys = setOf(
    "mode",
    "encryption.mode",
    "password",
    "pgpKey",
    "pgpPasscode",
    "enc",
    "srv.enc",
)

private fun NeoPrefs.exportSettingsToJson(): String {
    val legacyPreferences = Pref.fromSimpleFormat(Pref.preferencesToSerialized())
        .filterKeys { it !in unexportableLegacyPreferenceKeys }
    val root = buildJsonObject {
        put("version", JsonPrimitive(1))
        put("neoPrefs", Json.decodeFromString(JsonElement.serializer(), exportToJson()))
        put("legacyPrefs", JsonPrimitive(Pref.toSimpleFormat(legacyPreferences)))
    }
    return Json.encodeToString(JsonElement.serializer(), root)
}

private suspend fun NeoPrefs.importSettingsFromJson(content: String) {
    val root = Json.decodeFromString(JsonElement.serializer(), content) as? JsonObject
        ?: throw IllegalArgumentException("Invalid settings file")
    val version = root["version"]?.jsonPrimitive?.intOrNull
    if (version != 1) throw IllegalArgumentException("Unsupported settings file version")

    val neoPreferences = root["neoPrefs"]
        ?: throw IllegalArgumentException("Missing NeoPrefs section")
    importFromJson(Json.encodeToString(JsonElement.serializer(), neoPreferences))

    val legacyContent = root["legacyPrefs"]?.jsonPrimitive?.contentOrNull.orEmpty()
    if (legacyContent.isNotBlank()) {
        val allowedKeys = publicPreferences().map { it.key }
            .filterNot { it in unexportableLegacyPreferenceKeys }
            .toSet()
        val legacyPreferences = Pref.fromSimpleFormat(legacyContent)
            .filterKeys { it in allowedKeys }
        if (legacyPreferences.isNotEmpty()) {
            Pref.preferencesFromSerialized(Pref.toSimpleFormat(legacyPreferences))
        }
    }
}

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun ToolsPrefsPage(
    viewModel: HomeVM = koinNeoViewModel(),
) {
    val context = LocalContext.current
    val neoActivity = LocalActivity.current as NeoActivity
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val openDialog = remember { mutableStateOf(false) }
    val dialogProps: MutableState<Triple<DialogMode, Any?, Any?>> = remember {
        mutableStateOf(Triple(DialogMode.NONE, null, null))
    }
    val mainState by viewModel.state.collectAsState()
    val tagsMap by viewModel.tagsMap.collectAsState()


    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            snackbarHostState.show(
                coroutineScope,
                context.getString(R.string.settings_import_nofile),
            )
        } else {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val content = context.contentResolver.openInputStream(uri)
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        ?: throw IOException("Unable to read settings file")
                    neoPrefs.importSettingsFromJson(content)
                    snackbarHostState.show(
                        coroutineScope,
                        context.getString(R.string.settings_imported),
                    )
                } catch (e: Exception) {
                    Timber.e("importSettings failed: $e")
                    snackbarHostState.show(
                        coroutineScope,
                        context.getString(R.string.settings_import_failed),
                    )
                }
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(PREFS_MIME_TYPE)
    ) { uri ->
        if (uri == null) {
            snackbarHostState.show(
                coroutineScope,
                context.getString(R.string.settings_export_failed),
            )
        } else {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val content = neoPrefs.exportSettingsToJson()
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(content.toByteArray(StandardCharsets.UTF_8))
                    } ?: throw IOException("Unable to write settings file")
                    snackbarHostState.show(
                        coroutineScope,
                        context.getString(R.string.settings_exported),
                    )
                } catch (e: Exception) {
                    Timber.e("exportSettings failed: $e")
                    snackbarHostState.show(
                        coroutineScope,
                        context.getString(R.string.settings_export_failed),
                    )
                }
            }
        }
    }


    val prefs = Pref.prefGroups["tool"] ?: listOf()

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize(),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                val size = prefs.size

                PrefsGroup {
                    prefs.forEachIndexed { index, pref ->
                        LaunchPreference(
                            pref = pref,
                            index = index,
                            groupSize = size,
                        ) {
                            when (pref) {
                                pref_batchDelete           -> context.onClickUninstalledBackupsDelete(
                                    mainState.packages,
                                    snackbarHostState,
                                    coroutineScope
                                ) { message, action ->
                                    dialogProps.value =
                                        Triple(
                                            DialogMode.TOOL_DELETE_BACKUP_UNINSTALLED,
                                            message,
                                            action
                                        )
                                    openDialog.value = true
                                }

                                pref_cleanupBackupDir      -> {
                                    dialogProps.value = Triple(
                                        DialogMode.TOOL_CLEANUP_BACKUP_DIR,
                                        {
                                            coroutineScope.launch(Dispatchers.IO) {
                                                NeoApp.beginBusy("cleanupBackupDir")
                                                NeoApp.context.findBackups(damagedOp = DamagedOp.CLEANUP)
                                                NeoApp.endBusy("cleanupBackupDir")
                                            }
                                        },
                                        {
                                            coroutineScope.launch(Dispatchers.IO) {
                                                NeoApp.beginBusy("renameDamagedToERROR")
                                                NeoApp.context.findBackups(damagedOp = DamagedOp.RENAME)
                                                NeoApp.endBusy("renameDamagedToERROR")
                                            }
                                        }
                                    )
                                    openDialog.value = true
                                }

                                pref_enforceBackupsLimit   -> context.onClickEnforceBackupsLimit(
                                    mainState.packages,
                                    snackbarHostState,
                                    coroutineScope
                                ) { message, action ->
                                    dialogProps.value = Triple(
                                        DialogMode.ENFORCE_LIMIT,
                                        message,
                                        action
                                    )
                                    openDialog.value = true
                                }

                                pref_copySelfApk           -> context.onClickCopySelf(
                                    snackbarHostState,
                                    coroutineScope
                                )

                                pref_schedulesExportImport -> neoActivity.moveTo(NavRoute.Exports)

                                pref_exportSettings -> exportLauncher.launch(
                                    "NB_${currentTimestamp}.${PREFS_EXTENSION}"
                                )

                                pref_importSettings -> importLauncher.launch(PREFS_MIME_ARRAY)

                                pref_saveAppsList          -> context.onClickSaveAppsList(
                                    mainState.packages,
                                    tagsMap,
                                    mainState.sortFilter,
                                    snackbarHostState,
                                    coroutineScope
                                ) { primaryAction, secondaryAction ->
                                    dialogProps.value = Triple(
                                        DialogMode.TOOL_SAVE_APPS_LIST,
                                        primaryAction,
                                        secondaryAction
                                    )
                                    openDialog.value = true
                                }

                                pref_logViewer             -> neoActivity.moveTo(NavRoute.Logs)

                                pref_terminal              -> neoActivity.moveTo(NavRoute.Terminal)
                            }
                        }
                        if (index < size - 1) Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            }
        }
    }

    if (openDialog.value) BaseDialog(onDismiss = { openDialog.value = false }) {
        dialogProps.value.let { (dialogMode, primary, second) ->
            when (dialogMode) {
                DialogMode.TOOL_DELETE_BACKUP_UNINSTALLED
                     -> ActionsDialogUI(
                    titleText = stringResource(R.string.prefs_batchdelete),
                    messageText = primary.toString(),
                    onDismiss = { openDialog.value = false },
                    primaryText = stringResource(R.string.dialogYes),
                    primaryAction = second as () -> Unit,
                )

                DialogMode.ENFORCE_LIMIT
                     -> ActionsDialogUI(
                    titleText = stringResource(R.string.enforce_backups_limit),
                    messageText = primary.toString(),
                    onDismiss = { openDialog.value = false },
                    primaryText = stringResource(R.string.dialogYes),
                    primaryAction = second as () -> Unit,
                )

                DialogMode.TOOL_CLEANUP_BACKUP_DIR
                     -> ActionsDialogUI(
                    titleText = stringResource(R.string.prefs_cleanup_backupdir),
                    messageText = stringResource(R.string.prefs_cleanup_backupdir_summary),
                    onDismiss = { openDialog.value = false },
                    primaryText = stringResource(R.string.dialogDelete),
                    primaryAction = primary as () -> Unit,
                    secondaryText = stringResource(R.string.dialogLabel),
                    secondaryAction = second as () -> Unit,
                )

                DialogMode.TOOL_SAVE_APPS_LIST
                     -> ActionsDialogUI(
                    titleText = stringResource(R.string.prefs_saveappslist),
                    messageText = stringResource(R.string.prefs_saveappslist_summary),
                    onDismiss = { openDialog.value = false },
                    primaryText = stringResource(R.string.radio_all),
                    primaryAction = primary as () -> Unit,
                    secondaryText = stringResource(R.string.filtered_list),
                    secondaryAction = second as () -> Unit,
                )

                else -> {}
            }
        }
    }
}

val pref_batchDelete = LinkPref(
    key = "tool.batchDelete",
    titleId = R.string.prefs_batchdelete,
    summaryId = R.string.prefs_batchdelete_summary,
    icon = Phosphor.TrashSimple,
    //iconTint = { MaterialTheme.colorScheme.secondary },
)

private fun Context.onClickUninstalledBackupsDelete(
    packageList: Collection<Package>,
    snackbarHostState: SnackbarHostState,
    coroutineScope: CoroutineScope,
    showDialog: (String, () -> Unit) -> Unit,
): Boolean {
    val deleteList = ArrayList<Package>()
    val message = StringBuilder()
    if (packageList.isNotEmpty()) {
        packageList.forEach { appInfo ->
            if (!appInfo.isInstalled) {
                deleteList.add(appInfo)
                message.append(appInfo.packageLabel).append("\n")
            }
        }
    }
    if (packageList.isNotEmpty()) {
        if (deleteList.isNotEmpty()) {
            showDialog(message.toString().trim { it <= ' ' }) {
                coroutineScope.launch(Dispatchers.IO) {
                    deleteBackups(deleteList)
                }
            }
        } else {
            snackbarHostState.show(
                coroutineScope,
                getString(R.string.batchDeleteNothingToDelete)
            )
        }
    } else {
        snackbarHostState.show(
            coroutineScope,
            getString(R.string.wait_noappslist),
        )
    }
    return true
}

private suspend fun Context.deleteBackups(deleteList: List<Package>) {
    val notificationId = SystemUtils.now.toInt()
    deleteList.forEachIndexed { i, ai ->
        showNotification(
            this,
            NeoActivity::class.java,
            notificationId,
            "${getString(R.string.batchDeleteMessage)} ($i/${deleteList.size})",
            ai.packageLabel,
            false
        )
        Timber.i("deleting backups of ${ai.packageLabel}")
        ai.deleteAllBackups()
    }
    showNotification(
        this,
        NeoActivity::class.java,
        notificationId,
        getString(R.string.batchDeleteNotificationTitle),
        "${getString(R.string.batchDeleteBackupsDeleted)} ${deleteList.size}",
        false
    )
}

val pref_cleanupBackupDir = LinkPref(
    key = "tool.cleanupBackupDir",
    titleId = R.string.prefs_cleanup_backupdir,
    summaryId = R.string.prefs_cleanup_backupdir_summary,
    icon = Phosphor.Broom,
)

val pref_enforceBackupsLimit = LinkPref(
    key = "tool.enforceBackupsLimit",
    titleId = R.string.enforce_backups_limit,
    summaryId = R.string.enforce_backups_limit_summary,
    icon = Phosphor.Hash,
)

private fun Context.onClickEnforceBackupsLimit(
    packageList: Collection<Package>,
    snackbarHostState: SnackbarHostState,
    coroutineScope: CoroutineScope,
    showDialog: (String, () -> Unit) -> Unit,
): Boolean {
    // TODO consider locked backups for the list
    val packagesForHousekeeping = packageList.filter {
        pref_numBackupRevisions.value > 0 && it.numberOfBackups > pref_numBackupRevisions.value
    }
    if (packagesForHousekeeping.isNotEmpty()) {
        showDialog(
            getString(
                R.string.enforce_backups_limit_FORMAT,
                pref_numBackupRevisions.value,
                packagesForHousekeeping.joinToString { it.packageLabel }
            ),
        ) {
            coroutineScope.launch(Dispatchers.IO) {
                packagesForHousekeeping.forEach {
                    BackupRestoreHelper.housekeepingPackageBackups(it)
                    Package.invalidateCacheForPackage(it.packageName)
                }
                snackbarHostState.show(
                    coroutineScope,
                    getString(
                        R.string.enforced_backups_limit_FORMAT,
                        pref_numBackupRevisions.value,
                        packagesForHousekeeping.joinToString { it.packageLabel }
                    ),
                )
            }
        }
    } else {
        snackbarHostState.show(
            coroutineScope,
            getString(R.string.no_apps_to_enforce_backups_limit),
        )
    }
    return true
}

val pref_copySelfApk = LinkPref(
    key = "tool.copySelfApk",
    titleId = R.string.prefs_copyselfapk,
    summaryId = R.string.prefs_copyselfapk_summary,
    icon = Phosphor.AndroidLogo,
    //iconTint = { MaterialTheme.colorScheme.primary },
)

private fun Context.onClickCopySelf(
    snackbarHostState: SnackbarHostState,
    coroutineScope: CoroutineScope,
): Boolean {
    try {
        CoroutineScope(Dispatchers.Default).launch {
            if (BackupRestoreHelper.copySelfApk(
                    this@onClickCopySelf,
                    NeoApp.shellHandler!!
                )
            ) {
                showNotification(
                    this@onClickCopySelf,
                    NeoActivity::class.java,
                    SystemUtils.now.toInt(),
                    getString(R.string.copyOwnApkSuccess),
                    "",
                    false
                )
                snackbarHostState.show(
                    coroutineScope,
                    getString(R.string.copyOwnApkSuccess)
                )
            } else {
                showNotification(
                    this@onClickCopySelf,
                    NeoActivity::class.java,
                    SystemUtils.now.toInt(),
                    getString(R.string.copyOwnApkFailed),
                    "",
                    false
                )
                snackbarHostState.show(
                    coroutineScope,
                    getString(R.string.copyOwnApkFailed)
                )
            }
        }
    } catch (e: IOException) {
        Timber.e("${getString(R.string.copyOwnApkFailed)}: $e")
    } finally {
        return true
    }
}

val pref_exportSettings = LinkPref(
    key = "tool.exportSettings",
    titleId = R.string.prefs_exportsettings,
    summaryId = R.string.prefs_exportsettings_summary,
    icon = Phosphor.ListNumbers,
)

val pref_importSettings = LinkPref(
    key = "tool.importSettings",
    titleId = R.string.prefs_importsettings,
    summaryId = R.string.prefs_importsettings_summary,
    icon = Phosphor.ArrowsClockwise,
)

val pref_schedulesExportImport = LinkPref(
    key = "tool.schedulesExportImport",
    titleId = R.string.prefs_schedulesexportimport,
    summaryId = R.string.prefs_schedulesexportimport_summary,
    icon = Phosphor.CalendarX,
)

val pref_saveAppsList = LinkPref(
    key = "tool.saveAppsList",
    titleId = R.string.prefs_saveappslist,
    summaryId = R.string.prefs_saveappslist_summary,
    icon = Phosphor.ListNumbers,
)


private fun Context.onClickSaveAppsList(
    packageList: Collection<Package>,
    tagsMap: Map<String, Set<String>>,
    sortFilterModel: SortFilterModel,
    snackbarHostState: SnackbarHostState,
    coroutineScope: CoroutineScope,
    showDialog: (() -> Unit, () -> Unit) -> Unit
): Boolean {
    if (packageList.isNotEmpty()) {
        showDialog(
            {
                writeAppsListFile(
                    packageList
                        .filter { it.isSystem }
                        .map { "${it.packageLabel}: ${it.packageName} @ ${it.versionName}" },
                    false  //TODO hg42 name first because of ":", better for scripts
                )
            },
            {
                writeAppsListFile( // TODO communicate that the filter from home page is used
                    packageList.applyFilter(sortFilterModel, tagsMap)
                        .map { "${it.packageLabel}: ${it.packageName} @ ${it.versionName}" },
                    true
                )
            }
        )
    } else {
        snackbarHostState.show(
            coroutineScope,
            getString(R.string.wait_noappslist),
        )
    }
    return true
}

@Throws(IOException::class)
fun Context.writeAppsListFile(appsList: List<String>, filteredBoolean: Boolean) {
    val date = LocalDateTime.now()
    val filesText = appsList.joinToString("\n")
    val fileName = "${BACKUP_DATE_TIME_FORMATTER.format(date)}.appslist"
    NeoApp.backupRoot?.createFile(fileName)?.let { listFile ->
        BufferedOutputStream(listFile.outputStream())
            .use { it.write(filesText.toByteArray(StandardCharsets.UTF_8)) }
        showNotification(
            this, NeoActivity::class.java, SystemUtils.now.toInt(),
            getString(
                if (filteredBoolean) R.string.write_apps_list_filtered
                else R.string.write_apps_list_all
            ), null, false
        )
        Timber.i("Wrote apps\' list file at $date")
    }
}


val pref_logViewer = LinkPref(
    key = "tool.logViewer",
    titleId = R.string.prefs_logviewer,
    icon = Phosphor.Bug,
)

val pref_terminal = LinkPref(
    key = "tool.terminal",
    titleId = R.string.prefs_tools_terminal,
    icon = Phosphor.Bug,
)
