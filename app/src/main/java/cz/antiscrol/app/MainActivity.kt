package cz.antiscrol.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.provider.MediaStore
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import cz.antiscrol.app.data.LoadedRules
import cz.antiscrol.app.data.RulesRepository
import cz.antiscrol.app.data.RulesSource
import cz.antiscrol.app.data.SettingsStore
import cz.antiscrol.app.ui.AntiScrolTheme
import cz.antiscrol.app.ui.MainScreen
import cz.antiscrol.app.ui.SettingsScreen
import cz.antiscrol.app.web.WebController
import java.io.File
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity(), WebController.Host {

    private lateinit var settingsStore: SettingsStore
    private lateinit var rulesRepository: RulesRepository
    private lateinit var controller: WebController

    private var loadedRules by mutableStateOf<LoadedRules?>(null)
    private var profilesEnabled by mutableStateOf(false)
    private var settingsVisible by mutableStateOf(false)
    private val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    // --- file chooser state ---
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var fileParams: WebChromeClient.FileChooserParams? = null
    private var capturedImage: Pair<File, Uri>? = null
    private var capturedVideo: Pair<File, Uri>? = null
    private var cameraPermissionAsked = false

    // --- web permission (camera / microphone asked by the page) state ---
    private var pendingWebPermission: PermissionRequest? = null

    private val chooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { onChooserResult(it) }

    private val cameraForChooserLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { launchChooser() }

    private val webPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            pendingWebPermission?.let { answerWebPermission(it) }
            pendingWebPermission = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        settingsStore = SettingsStore(this)
        rulesRepository = RulesRepository(this)

        val rules = rulesRepository.loadLocal()
        loadedRules = rules
        // One tiny read; the policy must know this before the first page loads.
        profilesEnabled = runBlocking { settingsStore.profilesEnabled.first() }

        controller = WebController(this, this, rules.rules, profilesEnabled)
        cleanOldCaptures()

        setContent {
            AntiScrolTheme {
                val snackbarHostState = remember { SnackbarHostState() }
                LaunchedEffect(Unit) {
                    messages.collect { snackbarHostState.showSnackbar(it) }
                }
                BackHandler(enabled = true) {
                    when {
                        settingsVisible -> {
                            settingsVisible = false
                        }
                        controller.handleBack() -> {
                            // Handled inside the WebView.
                        }
                        else -> {
                            moveTaskToBack(true)
                        }
                    }
                }
                MainScreen(
                    controller = controller,
                    snackbarHostState = snackbarHostState,
                    settingsVisible = settingsVisible,
                    onOpenSettings = { settingsVisible = true },
                    onLeaveSettings = { settingsVisible = false },
                    settingsContent = {
                        SettingsScreen(
                            profilesEnabled = profilesEnabled,
                            onProfilesChanged = { changeProfilesEnabled(it) },
                            rulesInfo = rulesInfo(loadedRules),
                            appVersion = BuildConfig.VERSION_NAME,
                            onRefreshRules = { refreshRules(announce = true) },
                            onSignOut = {
                                controller.clearAllData { showMessage("Odhlášeno, data smazána.") }
                                settingsVisible = false
                            },
                            onClose = { settingsVisible = false },
                        )
                    },
                )
            }
        }

        controller.start()
        refreshRules(announce = false)
        handleTestIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleTestIntent(intent)
    }

    /**
     * Debug builds only: lets the emulator smoke test (tools/smoke-test.sh) drive the page.
     * The release APK ignores these extras.
     */
    private fun handleTestIntent(intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        val command = intent?.getStringExtra("antiscrol.test") ?: return
        controller.runTestCommand(command)
    }

    override fun onResume() {
        super.onResume()
        controller.onResume()
    }

    override fun onPause() {
        controller.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        pendingWebPermission?.deny()
        pendingWebPermission = null
        controller.destroy()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ settings and rules

    private fun changeProfilesEnabled(enabled: Boolean) {
        profilesEnabled = enabled
        controller.setProfilesEnabled(enabled)
        lifecycleScope.launch { settingsStore.setProfilesEnabled(enabled) }
    }

    private fun refreshRules(announce: Boolean) {
        val current = loadedRules ?: return
        lifecycleScope.launch {
            val newer = rulesRepository.fetchNewer(current.rules)
            if (newer != null) {
                loadedRules = newer
                controller.updateRules(newer.rules)
                if (announce) showMessage("Staženy novější pravidla (verze ${newer.rules.version}).")
            } else if (announce) {
                showMessage("Novější pravidla nejsou k dispozici.")
            }
        }
    }

    private fun rulesInfo(loaded: LoadedRules?): String {
        if (loaded == null) return ""
        val source = when (loaded.source) {
            RulesSource.BUNDLED -> "přibalená v aplikaci"
            RulesSource.DOWNLOADED -> "stažená z internetu"
        }
        return "Verze ${loaded.rules.version} ($source). Pravidla říkají, co se má na stránce skrýt; když se web změní, " +
            "dají se opravit bez nové verze aplikace."
    }

    // ------------------------------------------------------------------ WebController.Host

    override fun showMessage(text: String) {
        messages.tryEmit(text)
    }

    override fun openExternal(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            showMessage("Odkaz se nepodařilo otevřít.")
        }
    }

    /** The page asks for the camera or microphone (voice message, camera in the page). */
    override fun onWebPermissionRequest(request: PermissionRequest) {
        if (!controller.isAppHost(request.origin?.host)) {
            request.deny()
            return
        }
        val needed = androidPermissionsFor(request)
        if (needed.isEmpty()) {
            request.deny()
            return
        }
        val missing = needed.filterNot { hasPermission(it) }
        if (missing.isEmpty()) {
            answerWebPermission(request)
        } else {
            pendingWebPermission?.deny()
            pendingWebPermission = request
            webPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun androidPermissionsFor(request: PermissionRequest): List<String> = buildList {
        for (resource in request.resources) {
            when (resource) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> add(Manifest.permission.CAMERA)
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> add(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun answerWebPermission(request: PermissionRequest) {
        val granted = request.resources.filter { resource ->
            when (resource) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> hasPermission(Manifest.permission.CAMERA)
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> hasPermission(Manifest.permission.RECORD_AUDIO)
                else -> false
            }
        }
        try {
            if (granted.isEmpty()) request.deny() else request.grant(granted.toTypedArray())
        } catch (e: Exception) {
            // The request may have been cancelled by the page in the meantime.
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------------ file chooser (add story, send photo)

    override fun onShowFileChooser(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams?) {
        // Only one chooser at a time; an unanswered callback would block the page's file input.
        fileCallback?.onReceiveValue(null)
        fileCallback = callback
        fileParams = params
        if (!hasPermission(Manifest.permission.CAMERA) && !cameraPermissionAsked) {
            // Asked once. If it is denied, the gallery still works, just without "take a photo".
            cameraPermissionAsked = true
            cameraForChooserLauncher.launch(Manifest.permission.CAMERA)
        } else {
            launchChooser()
        }
    }

    private fun launchChooser() {
        if (fileCallback == null) return
        val params = fileParams
        val accept = params?.acceptTypes.orEmpty()
            .flatMap { it.split(',') }
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
        val mimeTypes = accept.filter { it.contains('/') }
        val wantsImage = mimeTypes.isEmpty() || mimeTypes.any { it.startsWith("image/") || it == "*/*" }
        val wantsVideo = mimeTypes.isEmpty() || mimeTypes.any { it.startsWith("video/") || it == "*/*" }

        // The system picker needs no storage permission: it hands over only the chosen files.
        val pick = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            when {
                mimeTypes.size == 1 -> {
                    type = mimeTypes[0]
                }
                mimeTypes.isEmpty() -> {
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
                }
                else -> {
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())
                }
            }
            if (params?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
        }

        capturedImage = null
        capturedVideo = null
        val cameraIntents = mutableListOf<Parcelable>()
        if (hasPermission(Manifest.permission.CAMERA)) {
            if (wantsImage) {
                newCapture("jpg")?.let { capture ->
                    capturedImage = capture
                    cameraIntents += captureIntent(MediaStore.ACTION_IMAGE_CAPTURE, capture.second)
                }
            }
            if (wantsVideo) {
                newCapture("mp4")?.let { capture ->
                    capturedVideo = capture
                    cameraIntents += captureIntent(MediaStore.ACTION_VIDEO_CAPTURE, capture.second)
                }
            }
        }

        val chooser = Intent.createChooser(pick, "Vyber fotku nebo video")
        if (cameraIntents.isNotEmpty()) {
            chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, cameraIntents.toTypedArray())
        }
        try {
            chooserLauncher.launch(chooser)
        } catch (e: ActivityNotFoundException) {
            finishChooser(null)
            showMessage("V telefonu není aplikace pro výběr souboru.")
        }
    }

    private fun captureIntent(action: String, output: Uri): Intent = Intent(action).apply {
        putExtra(MediaStore.EXTRA_OUTPUT, output)
        clipData = ClipData.newRawUri("", output)
        addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun newCapture(extension: String): Pair<File, Uri>? = try {
        val dir = File(cacheDir, "captures").apply { mkdirs() }
        val file = File(dir, "capture_${System.currentTimeMillis()}.$extension")
        file to FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    } catch (e: Exception) {
        null
    }

    private fun onChooserResult(result: ActivityResult) {
        val picked = mutableListOf<Uri>()
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            data?.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) clip.getItemAt(i)?.uri?.let { picked += it }
            }
            if (picked.isEmpty()) data?.data?.let { picked += it }
            // The camera returns no data; the photo or video is in the file we handed to it.
            if (picked.isEmpty()) {
                listOfNotNull(capturedImage, capturedVideo)
                    .firstOrNull { (file, _) -> file.exists() && file.length() > 0 }
                    ?.let { (_, uri) -> picked += uri }
            }
        }
        finishChooser(if (picked.isEmpty()) null else picked.toTypedArray())
    }

    private fun finishChooser(uris: Array<Uri>?) {
        fileCallback?.onReceiveValue(uris)
        fileCallback = null
        fileParams = null
        // Empty placeholders of captures that were not taken.
        listOfNotNull(capturedImage, capturedVideo).forEach { (file, _) ->
            if (file.exists() && file.length() == 0L) file.delete()
        }
        capturedImage = null
        capturedVideo = null
    }

    private fun cleanOldCaptures() {
        val limit = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        File(cacheDir, "captures").listFiles()?.forEach { if (it.lastModified() < limit) it.delete() }
    }
}
