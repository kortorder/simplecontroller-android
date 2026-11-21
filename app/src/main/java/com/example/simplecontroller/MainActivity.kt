package com.example.simplecontroller

import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.children
import androidx.core.view.doOnNextLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.simplecontroller.io.GameStarter
import com.example.simplecontroller.io.GameStarters
import com.example.simplecontroller.ui.showGameStarterDialog
import com.example.simplecontroller.io.LayoutManager
import com.example.simplecontroller.io.ControllerProfileLoadResult
import com.example.simplecontroller.io.ImportedControllerProfile
import com.example.simplecontroller.io.ControllerProfilesBackupLoadResult
import com.example.simplecontroller.io.ProfileTransferDocument
import com.example.simplecontroller.io.StoredControllerProfileResult
import com.example.simplecontroller.io.clearStagedControllerProfileTransfer
import com.example.simplecontroller.io.controllerProfilesBackupFileName
import com.example.simplecontroller.io.controllerProfileTransferFileName
import com.example.simplecontroller.io.createControllerProfilesBackup
import com.example.simplecontroller.io.decodeProfileTransferDocument
import com.example.simplecontroller.io.deleteControllerProfile
import com.example.simplecontroller.io.deepCopyControls
import com.example.simplecontroller.io.encodeControllerProfilesBackup
import com.example.simplecontroller.io.importControllerProfilesBackup
import com.example.simplecontroller.io.listLayouts
import com.example.simplecontroller.io.readControllerProfileTransferText
import com.example.simplecontroller.io.readControllerProfile
import com.example.simplecontroller.io.readStagedControllerProfileTransfer
import com.example.simplecontroller.io.saveControllerProfile
import com.example.simplecontroller.io.sanitizeSuggestedProfileName
import com.example.simplecontroller.io.stageControllerProfileTransfer
import com.example.simplecontroller.io.stageProfileTransferDocument
import com.example.simplecontroller.io.stableLegacyBasePageId
import com.example.simplecontroller.io.uniqueImportedProfileName
import com.example.simplecontroller.io.validateImportedProfileName
import com.example.simplecontroller.model.Control
import com.example.simplecontroller.model.ControllerPage
import com.example.simplecontroller.model.ControllerPageOption
import com.example.simplecontroller.model.ControllerPageSession
import com.example.simplecontroller.model.ControllerProfile
import com.example.simplecontroller.model.ControlType
import com.example.simplecontroller.model.LayoutOrientation
import com.example.simplecontroller.model.OrientationPreferenceBackend
import com.example.simplecontroller.model.OrientationPreferenceStore
import com.example.simplecontroller.model.PageAction
import com.example.simplecontroller.model.TouchAimCalibrationProfile
import com.example.simplecontroller.model.hasPageName
import com.example.simplecontroller.model.ProfileSwipeMode
import com.example.simplecontroller.model.capturePageGeometry
import com.example.simplecontroller.model.controlsForOrientation
import com.example.simplecontroller.model.geometryFor
import com.example.simplecontroller.model.isLocalPageAction
import com.example.simplecontroller.model.newPageId
import com.example.simplecontroller.model.page
import com.example.simplecontroller.model.pageName
import com.example.simplecontroller.model.referenceCount
import com.example.simplecontroller.model.remapControlIds
import com.example.simplecontroller.model.withGeometry
import com.example.simplecontroller.model.withOnlyControlIds
import com.example.simplecontroller.net.NetworkClient
import com.example.simplecontroller.ui.ControlView
import com.example.simplecontroller.ui.GlobalSettings
import com.example.simplecontroller.ui.SwipeManager
import com.example.simplecontroller.ui.ThemeManager
import com.example.simplecontroller.ui.CommunityHelp
import com.example.simplecontroller.ui.HelpOnboardingPolicy
import com.example.simplecontroller.ui.ControllerToolsDialog
import com.example.simplecontroller.ui.UIComponentBuilder
import com.example.simplecontroller.ui.PropertySheetStyle
import com.example.simplecontroller.ui.ReleaseAllCoordinator
import com.example.simplecontroller.ui.TouchAimCalibrationWizard
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.simplecontroller.net.UdpClient
import android.widget.CheckBox
import android.view.ViewGroup
import java.nio.charset.StandardCharsets
import com.example.simplecontroller.ui.FloatingControlsController
import com.example.simplecontroller.ui.FloatingControlsEditor
import com.example.simplecontroller.ui.FloatingRingView
import com.example.simplecontroller.ui.AndroidFloatingRingBackend
import com.example.simplecontroller.model.FloatingZone
import com.example.simplecontroller.model.FloatingLocalAction
import com.example.simplecontroller.io.detachedControllerPageCopy
import com.example.simplecontroller.model.controlsForFloatingOrientation
import com.example.simplecontroller.model.FloatingZoneKind
import com.example.simplecontroller.model.ControlGeometry
import com.example.simplecontroller.model.FloatingZoneGeometry

class MainActivity : AppCompatActivity(), LayoutManager.LayoutCallback {

    private companion object {
        const val USB_TETHER_RETURN_PENDING = "usbTetherReturnPending"
        const val TETHER_SETTINGS_ACTION = "android.settings.TETHER_SETTINGS"
    }

    private val createProfileDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) {
            clearStagedControllerProfileTransfer(this)
        } else {
            writeAndVerifyProfileExport(uri)
        }
    }

    private val openProfileDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) readAndPromptForProfileImport(uri)
    }

    /* ---------- persisted "current layout" name ---------- */
    private val prefs by lazy { getSharedPreferences("layout", MODE_PRIVATE) }
    private var layoutName: String
        get() = prefs.getString("current", "default") ?: "default"
        set(v) = prefs.edit().putString("current", v).apply()

    /* ---------- network settings prefs ---------- */
    private val networkPrefs by lazy { getSharedPreferences("network", MODE_PRIVATE) }
    private val orientationPrefs by lazy { getSharedPreferences("orientation", MODE_PRIVATE) }
    private val orientationStore by lazy {
        OrientationPreferenceStore(object : OrientationPreferenceBackend {
            override fun read(key: String): String? = orientationPrefs.getString(key, null)

            override fun write(key: String, value: String) {
                orientationPrefs.edit().putString(key, value).apply()
            }
        })
    }

    /* ---------- helper classes ---------- */
    private lateinit var uiBuilder: UIComponentBuilder
    private lateinit var layoutManager: LayoutManager

    /* ---------- underlying data model ---------- */
    // Changed from lazy delegate to lateinit to avoid initialization order issues
    private lateinit var controls: MutableList<Control>
    private lateinit var controllerProfile: ControllerProfile
    private lateinit var pageSession: ControllerPageSession
    private var displayedPageId: String = ""
    private var editingPageId: String = ""
    private var profileAutosaveBlocked = false
    private var hasEditsToKeepBeforeStarter = false
    private var transitionGestureConsumed = false
    private val bridgeInputResetListener: () -> Unit = { clearInputViewsForBridgeBoundary() }
    private val floatingControls: FloatingControlsController by lazy {
        FloatingControlsController(
            this, canvas, { controls }, { controllerProfile.page(displayedPageId)!! },
            { activeLayoutOrientation }, { layoutManager.createControlView(it) },
            { zone ->
                val backend = AndroidFloatingRingBackend(this, zone.ring!!, zone.id)
                FloatingRingView(this, zone.ring!!, backend,
                    { floatingControls.finish(zone.id) }, finishIgnoresSwipes = zone.finishIgnoresSwipes).also {
                    backend.onOutputReplaced = it.state::outputReplaced
                    backend.onMovementReplaced = it.state::movementReplaced
                    backend.onFeedbackChanged = it.state::refreshFeedback
                    backend.pendingPayloads = it.state::payloadsForSlot
                }
            },
            { zone -> floatingEditor.edit(zone) },
            ::moveFloatingZone
        )
    }
    private val floatingEditorDelegate = lazy {
        FloatingControlsEditor(
            this, canvas, { controls }, { controllerProfile.page(displayedPageId)!! },
            { activeLayoutOrientation }, ::replaceFloatingZones,
            ::syncDisplayedPageIntoProfile,
            { syncDisplayedPageIntoProfile(); layoutManager.showSaveDialog() }
        )
    }
    private val floatingEditor: FloatingControlsEditor by floatingEditorDelegate
    private fun cancelFloatingSelection() {
        if(floatingEditorDelegate.isInitialized()) floatingEditor.cancelSelection()
    }

    private fun replaceFloatingZones(zones: List<FloatingZone>) {
        syncDisplayedPageIntoProfile()
        controllerProfile = controllerProfile.copy(pages = controllerProfile.pages.map {
            if (it.id == displayedPageId) it.copy(floatingZones = zones) else it
        })
        renderPage(displayedPageId, safeToDetach = true)
    }

    /** Commit a completed Edit drag without removing the view that owns the gesture. */
    private fun moveFloatingZone(zoneId: String, rectangle: ControlGeometry): Boolean {
        if (!GlobalSettings.editMode || orientationRenderPending) return false
        if (controllerProfile.page(displayedPageId)?.floatingZones?.none { it.id == zoneId } != false) return false
        syncDisplayedPageIntoProfile()
        controllerProfile = controllerProfile.copy(pages = controllerProfile.pages.map { page ->
            if (page.id != displayedPageId) page else page.copy(floatingZones = page.floatingZones.map { zone ->
                if (zone.id != zoneId) zone else zone.withGeometry(activeLayoutOrientation,
                    FloatingZoneGeometry(canvas.width.toFloat(),canvas.height.toFloat(),rectangle))
            })
        })
        return true
    }

    fun floatingAction(action: FloatingLocalAction, targetId: String) = floatingControls.action(action, targetId)
    fun floatingActionTargets(): List<Pair<String, String>> =
        controllerProfile.page(displayedPageId)!!.floatingZones.map { it.id to it.name.ifBlank { "Floating control" } } +
            controls.map { it.id to it.name.ifBlank { it.id } }
    fun editFloatingControlOptions(control: Control) = floatingEditor.editControlOptions(control)
    fun preserveSavedProfileDuringFloatingConversion() { profileAutosaveBlocked = true }
    fun restoreFloatingGeometry(zone: FloatingZone) {
        syncDisplayedPageIntoProfile()
        controllerProfile = controllerProfile.copy(pages = controllerProfile.pages.map { page ->
            if (page.id != displayedPageId) page else page.copy(
                portraitGeometry = page.portraitGeometry?.copy(controls =
                    page.portraitGeometry.controls + zone.portraitRestorationGeometry),
                landscapeGeometry = page.landscapeGeometry?.copy(controls =
                    page.landscapeGeometry.controls + zone.landscapeRestorationGeometry)
            )
        })
    }

    /* ---------- edit-only widgets we show/hide ---------- */
    private lateinit var btnSave: View
    private lateinit var btnLoad: View
    private lateinit var fabAdd: View
    private lateinit var btnEdit: Button
    private lateinit var btnPageManager: Button
    private lateinit var btnEditOrientation: Button
    private lateinit var btnPlayOrientation: Button
    private lateinit var orientationControlsRow: LinearLayout

    /* ---------- global switches ---------- */
    private lateinit var switchSnap: Switch
    private lateinit var switchHold: Switch
    private lateinit var switchTurbo: Switch
    private lateinit var switchSwipe: Switch

    /* ---------- connection UI ---------- */
    private lateinit var connectionStatusText: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnCamera: Button
    private lateinit var btnAllControls: Button
    private var toolsDialog: ControllerToolsDialog? = null
    private var toolbarGesture = false

    /* ---------- main canvas ---------- */
    private lateinit var canvas: FrameLayout
    private lateinit var controlAreaRoot: com.example.simplecontroller.ui.SafeControlAreaLayout
    private var renderedCanvasWidth = 0f
    private var renderedCanvasHeight = 0f
    private var renderedControlGeometry = emptyMap<String, ControlGeometry>()
    private var activeTouchAimCalibration: TouchAimCalibrationWizard? = null
    private var touchAimCalibrationLaunchGeneration = 0
    private var activeLayoutOrientation = LayoutOrientation.PORTRAIT
    private var pendingOrientationPageId: String? = null
    private var orientationRenderPending = false
    
    /* ---------- layout monitoring ---------- */
    private var lastCanvasHeight = 0
    private var isLayoutInitialized = false
    private var originalCanvasHeight = 0f
    private var originalCanvasWidth = 0f
    private var originalControlDimensions = mutableMapOf<String, OriginalDimensions>()
    
    data class OriginalDimensions(
        val x: Float,
        val y: Float, 
        val w: Float,
        val h: Float
    )

    // =============== life-cycle ======================================
    override fun onCreate(savedInstanceState: Bundle?) {
        // 0. Theme first
        ThemeManager.init(this)

        super.onCreate(savedInstanceState)
        val startupPlayOrientation = orientationStore.readPlayOrientation()
        setContentView(R.layout.activity_main)

        // 1. Grab views
        canvas = findViewById(R.id.canvas)
        controlAreaRoot = findViewById(R.id.controlAreaRoot)
        connectionStatusText = findViewById(R.id.connectionStatus)
        activeLayoutOrientation = layoutOrientationFrom(resources.configuration)

        // 2. Apply theme colours
        canvas.setBackgroundColor(ContextCompat.getColor(this, R.color.ui_canvas))
        connectionStatusText.setTextColor(ContextCompat.getColor(this, R.color.dark_text_primary))

        // 3. Network / player-role state
        UdpClient.initializeCameraFollowPreferences(networkPrefs)
        val savedPlayerRole = networkPrefs.getString(
            "playerRole",
            NetworkClient.PlayerRole.PLAYER1.name
        )
        val playerRole = runCatching {
            NetworkClient.PlayerRole.valueOf(savedPlayerRole ?: "PLAYER1")
        }.getOrDefault(NetworkClient.PlayerRole.PLAYER1)
        NetworkClient.setPlayerRole(playerRole)

        // 4. Load the complete profile. Old top-level control arrays are wrapped in memory as Base.
        val storedProfile = readControllerProfile(this, layoutName)
        val offerGameStarters = savedInstanceState == null && storedProfile == StoredControllerProfileResult.NotFound &&
            prefs.all.isEmpty() && listLayouts(this).isEmpty()
        val loadedProfile = (storedProfile as? StoredControllerProfileResult.Loaded)?.result
        profileAutosaveBlocked = storedProfile is StoredControllerProfileResult.Error
        controllerProfile = loadedProfile?.profile ?: run {
            val baseId = stableLegacyBasePageId(layoutName)
            ControllerProfile(
                homePageId = baseId,
                pages = listOf(ControllerPage(baseId, "Base", emptyList()))
            )
        }
        pageSession = ControllerPageSession(
            controllerProfile.homePageId,
            controllerProfile.pages.map { it.id }
        )
        displayedPageId = controllerProfile.homePageId
        editingPageId = controllerProfile.homePageId
        controls = controllerProfile.page(controllerProfile.homePageId)
            ?.controlsForOrientation(activeLayoutOrientation, 0f, 0f)
            ?.let(::deepCopyControls)
            .orEmpty()
            .toMutableList()

        // 5. Helpers that all point at *that* list
        uiBuilder = UIComponentBuilder(this, canvas)
        layoutManager = LayoutManager(this, canvas, controls) { ctrl ->
            ControlView(
                this,
                ctrl,
                onPageAction = ::activateControllerPageAction,
                pageActionLabel = ::pageActionDisplayLabel
            )
        }.apply { setCallback(this@MainActivity) }

        // If this is a first run (or the file was missing) seed it with the default template
        if (loadedProfile == null ||
            (loadedProfile.migratedFromSinglePage && controls.isEmpty())
        ) {
            controls += layoutManager.defaultLayout()
            syncDisplayedPageIntoProfile()
        }

        // 6. Build the UI & observers
        setupUI()
        configureControlArea()
        observeConnectionStatus()

        if (loadedProfile?.warnings?.isNotEmpty() == true) {
            Toast.makeText(
                this,
                loadedProfile.warnings.take(3).joinToString("\n"),
                Toast.LENGTH_LONG
            ).show()
        } else if (storedProfile is StoredControllerProfileResult.Error) {
            Toast.makeText(
                this,
                "This profile could not be read. Its original file was left unchanged; use Save as to recover safely.",
                Toast.LENGTH_LONG
            ).show()
        }

        // 7. Apply insets before the first measured profile render.
        setupWindowInsetsHandling()

        // 8. Render only after the saved Play orientation and canvas size have settled. Rendering
        // earlier can leave the startup controls detached until Edit Mode forces another render.
        switchToOrientation(startupPlayOrientation, controllerProfile.homePageId)
        
        // 9. Misc startup
        loadNetworkSettings()
        NetworkClient.setLocalInputResetListener(bridgeInputResetListener)
        NetworkClient.initializeBridgeIntent(getSharedPreferences("bridge_connection_intent", MODE_PRIVATE))
        updatePlayerRoleIndicator()
        val helpPrefs = getSharedPreferences(CommunityHelp.PREFERENCES, MODE_PRIVATE)
        if (HelpOnboardingPolicy.shouldOffer(offerGameStarters,
                helpPrefs.getBoolean(CommunityHelp.STARTED, false),
                helpPrefs.getBoolean(CommunityHelp.COMPLETED, false))) {
            if (offerGameStarters) {
                prefs.edit().putBoolean("offeredGameStarters", true).apply()
                helpPrefs.edit().putBoolean(CommunityHelp.STARTER_PENDING, true).apply()
            }
            CommunityHelp.showTour(this, firstRun = true) {
                if (helpPrefs.getBoolean(CommunityHelp.STARTER_PENDING, false) && !isFinishing) {
                    helpPrefs.edit().putBoolean(CommunityHelp.STARTER_PENDING, false).apply()
                    showGameStarterDialog(this, firstLaunch = true, ::openGameStarter)
                }
            }
        } else if (offerGameStarters) {
            prefs.edit().putBoolean("offeredGameStarters", true).apply()
            showGameStarterDialog(this, firstLaunch = true, ::openGameStarter)
        }
    }


    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        menu.add(0, 7101, 0, "Floating controls")
        menu.add(0, 7102, 1, "Finish floating control")
        menu.add(0, 7103, 2, getString(R.string.help_title))
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            7101 -> { if (GlobalSettings.editMode) floatingEditor.showManager() else floatingControls.showFinishList(); true }
            7102 -> { floatingControls.showFinishList(); true }
            7103 -> { showSupport(); true }
            R.id.menu_toggle_theme -> {
                ThemeManager.toggleDarkMode(this)
                recreate() // Recreate activity to apply theme changes
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    fun createControlFrom(src: Control) {
        layoutManager.createControlFrom(src)
    }


    override fun onStart() {
        super.onStart()

        // Returning from tether settings is handled by USB discovery in onResume().
        // Do not briefly start the saved manual connection first.
        val usbTetherReturnPending =
            networkPrefs.getBoolean(USB_TETHER_RETURN_PENDING, false)
        if ((NetworkClient.outputMode == NetworkClient.OutputMode.PRACTICE ||
            (networkPrefs.getBoolean("autoReconnect", false) && NetworkClient.outputMode != NetworkClient.OutputMode.SCX2)) && !usbTetherReturnPending &&
            NetworkClient.connectionStatus.value != NetworkClient.ConnectionStatus.CONNECTED) {
            NetworkClient.start()
        }
        if (!usbTetherReturnPending) prepareFloatingControlsForPlay()
    }

    private fun prepareFloatingControlsForPlay() {
        if (!GlobalSettings.editMode && ::controllerProfile.isInitialized &&
            controllerProfile.pages.any { page->page.floatingZones.any { it.enabled } }) {
            UdpClient.prepareFloatingControls()
        }
    }

    override fun onPause() {
        super.onPause()
        NetworkClient.appDidEnterBackground()
        cancelTouchAimCalibration("Calibration stopped because the app left the foreground.")
        syncDisplayedPageIntoProfile()
        releaseOutputs(showFeedback = false)
        if (displayedPageId != controllerProfile.homePageId) {
            pageSession.resetToHome()
            editingPageId = controllerProfile.homePageId
            renderPage(controllerProfile.homePageId, safeToDetach = true)
        } else pageSession.resetToHome()
        syncDisplayedPageIntoProfile()
        if (!profileAutosaveBlocked) {
            saveControllerProfile(this, layoutName, controllerProfile)
        }
    }

    override fun onResume() {
        super.onResume()
        NetworkClient.appDidEnterForeground()
        if (networkPrefs.getBoolean(USB_TETHER_RETURN_PENDING, false)) {
            networkPrefs.edit().remove(USB_TETHER_RETURN_PENDING).apply()
            connectViaUsbTether()
        }
    }

    override fun onStop() {
        super.onStop()

        // Only disconnect if auto-reconnect is disabled
        if (NetworkClient.outputMode != NetworkClient.OutputMode.SCX2 &&
            !networkPrefs.getBoolean("autoReconnect", false)) {
            NetworkClient.close()
        }
    }

    override fun onDestroy() {
        NetworkClient.clearLocalInputResetListener(bridgeInputResetListener)
        super.onDestroy()
    }

    /** Wire/session boundaries never navigate pages or change saved controls. */
    private fun clearInputViewsForBridgeBoundary() {
        transitionGestureConsumed = true
        cancelFloatingSelection()
        floatingControls.clear(removeEditorViews = false)
        SwipeManager.releaseEverythingLocally()
        cancelTouchAimCalibration("Calibration stopped because bridge input was reset.")
    }

    // Override dispatchTouchEvent to handle swipe mode
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (orientationRenderPending) return true
        if (activeTouchAimCalibration != null) return super.dispatchTouchEvent(ev)
        // A fresh toolbar touch must never become a floating/swipe/game gesture or
        // be swallowed by the disconnected receiver's input guard.
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            transitionGestureConsumed = false // A new contact may enter only after the neutral barrier below.
            toolbarGesture = controlAreaRoot.isToolbarTouch(ev.rawX, ev.rawY)
        }
        if (toolbarGesture) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) toolbarGesture = false
            return super.dispatchTouchEvent(ev)
        }
        if (transitionGestureConsumed) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL)
                transitionGestureConsumed = false
            return true
        }
        // Floating zones can retain the live contact while preparing; ordinary controls still
        // wait for the activation barrier below, so legacy holds are never switched mid-gesture.
        if (!GlobalSettings.editMode && (UdpClient.activationPending ||
                (NetworkClient.localOutputSelected && NetworkClient.connectionStatus.value != NetworkClient.ConnectionStatus.CONNECTED)) && ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val location = IntArray(2)
            canvas.getLocationOnScreen(location)
            val x = ev.rawX - location[0]; val y = ev.rawY - location[1]
            val hit = canvas.children.toList().asReversed().firstOrNull {
                it.visibility == View.VISIBLE && it.isClickable &&
                    x >= it.x && x <= it.x + it.width && y >= it.y && y <= it.y + it.height
            }
            val safetyAction = (hit as? ControlView)?.model?.let {
                it.payload.trim().equals("RELEASE_ALL", true) || it.floatingLocalAction != FloatingLocalAction.NONE
            } == true
            if (!safetyAction && (hit == null || hit is ControlView)) {
                transitionGestureConsumed = true
                Toast.makeText(this, "Preparing scoped controls; touch again after receiver confirmation.", Toast.LENGTH_SHORT).show()
                return true
            }
        }
        if (::controllerProfile.isInitialized && floatingControls.dispatch(ev)) return true
        // When swipe mode is active and we're not in edit mode, handle with SwipeManager
        if (GlobalSettings.globalSwipe && !GlobalSettings.editMode) {
            // If the manager processes it, we're done
            if (SwipeManager.processTouchEvent(ev)) {
                return true
            }
        }
        // Otherwise, use normal dispatch
        return super.dispatchTouchEvent(ev)
    }

    /**
     * Set up all UI components
     */

    /* ---------- member variables for turbo speed control ---------- */
    private lateinit var turboSpeedControl: Pair<EditText, ImageButton>
    private lateinit var turboSpeedContainer: View
    private var turboSpeed = 16L // Default speed in milliseconds (≈60 Hz)

    private fun setupUI() {
        /* --- Edit toggle ------------------------------------------------ */
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        btnEdit = uiBuilder.addCornerButton(
            "Edit", Gravity.TOP or Gravity.END, dp(16), dp(16)
        ) {
            requestEditModeChange(!GlobalSettings.editMode)
        }
        uiBuilder.styleAppButton(btnEdit, primary = true)
        btnEdit.contentDescription = "Edit. Hold to manage or finish floating controls."
        btnEdit.setOnLongClickListener {
            if (GlobalSettings.editMode) floatingEditor.showManager() else floatingControls.showFinishList()
            true
        }

        // Keep the status badge between the two tool columns on narrow windows.
        canvas.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (controllerProfile.safeControlArea) {
                if (!orientationRenderPending && renderedCanvasWidth > 0 && renderedCanvasHeight > 0 &&
                    canvas.width > 0 && canvas.height > 0 &&
                    (canvas.width.toFloat() != renderedCanvasWidth || canvas.height.toFloat() != renderedCanvasHeight)) {
                    // Capture using the dimensions that produced these controls, before refitting.
                    // IME/multi-window/font changes cannot re-label old pixels as a new canvas.
                    syncDisplayedPageIntoProfile()
                    releaseOutputs(showFeedback = false)
                    pendingOrientationPageId = displayedPageId
                    orientationRenderPending = true
                    val page = displayedPageId
                    canvas.post { completeOrientationRender(activeLayoutOrientation, page, waitForNextLayout = false) }
                }
                return@addOnLayoutChangeListener
            }
            val available = canvas.width - canvas.paddingLeft - canvas.paddingRight
            val leftTools = maxOf(btnConnect.right, btnCamera.right, btnAllControls.right)
            val sideSpace = maxOf(leftTools - canvas.paddingLeft + dp(8), btnEdit.width + dp(24))
            val statusWidth = minOf(dp(160), (available - 2 * sideSpace).coerceAtLeast(dp(56)))
            // TextView.setMaxWidth always requests layout, even for the same value. Writing it
            // from every layout callback would prevent the window from ever becoming idle.
            if (connectionStatusText.maxWidth != statusWidth) connectionStatusText.maxWidth = statusWidth
        }

        /* --- Connection button ------------------------------------------ */
        fun toolbarButton(label: Int, id: Int, top: Int, click: () -> Unit): Button =
            uiBuilder.addCornerButton(getString(label), Gravity.TOP or Gravity.START, dp(4), dp(top)) { click() }.apply {
                this.id = id
                val params = layoutParams as FrameLayout.LayoutParams
                params.width = dp(116)
                params.height = dp(50)
                layoutParams = params
                setLines(2)
                setPadding(dp(3), 0, dp(3), 0)
                // Keep the shortcuts legible on small windows; the full panel uses system text sizing.
                androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this,
                    9, 15, 1, android.util.TypedValue.COMPLEX_UNIT_DIP)
                translationZ = dp(2).toFloat()
            }
        btnConnect = toolbarButton(R.string.toolbar_bridge, R.id.toolbarBridge, 4, ::showBridgeMenu)
        btnCamera = toolbarButton(R.string.toolbar_camera, R.id.toolbarCamera, 56, ::togglePracticeCamera)
        btnAllControls = toolbarButton(R.string.toolbar_tools, R.id.toolbarTools, 108, ::showAllControls)

        btnPageManager = uiBuilder.addCornerButton(
            "Editing page: Base ▾",
            Gravity.TOP or Gravity.END,
            dp(16),
            dp(128)
        ) {
            showControllerPageManager()
        }

        fun orientationButton(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            minHeight = dp(48)
            uiBuilder.styleAppButton(this)
            setOnClickListener { view ->
                view.performHapticFeedback(
                    android.view.HapticFeedbackConstants.KEYBOARD_TAP,
                    android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                )
                onClick()
            }
        }

        btnEditOrientation = orientationButton("Edit in Landscape") {
            requestEditorOrientation(activeLayoutOrientation.opposite())
        }
        btnPlayOrientation = orientationButton("Play lock: Portrait") {
            val updated = orientationStore.readPlayOrientation().opposite()
            orientationStore.writePlayOrientation(updated)
            updateOrientationButtonLabels()
        }
        orientationControlsRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            addView(btnEditOrientation)
            addView(btnPlayOrientation)
        }
        canvas.addView(
            orientationControlsRow,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        /* --- Switches --------------------------------------------------- */
        switchSnap = uiBuilder.createSwitch("Snap", true) { on ->
            GlobalSettings.snapEnabled = on
            controls.filter { it.type != ControlType.BUTTON }
                .forEach { it.autoCenter = on }
        }

        switchHold = uiBuilder.createSwitch("Hold", false) { on ->
            GlobalSettings.globalHold = on
        }

        switchTurbo = uiBuilder.createSwitch("Turbo", false) { on ->
            GlobalSettings.globalTurbo = on
            turboSpeedContainer.visibility = if (on) View.VISIBLE else View.GONE
        }

        switchSwipe = uiBuilder.createSwitch("Swipe mode", false) { }
        switchSwipe.setOnCheckedChangeListener(null)
        switchSwipe.setOnClickListener {
            updateProfileSwipePresentation()
            showProfileSwipeOptions()
        }
        switchSwipe.setOnLongClickListener { showProfileSwipeOptions(); true }
        GlobalSettings.swipeMode = controllerProfile.swipeMode
        updateProfileSwipePresentation()

        // Add all switches to canvas
        uiBuilder.addVerticalSwitches(
            listOf(switchSnap, switchHold, switchTurbo, switchSwipe),
            Gravity.TOP or Gravity.START,  // TOP instead of CENTER_VERTICAL
            dp(8), dp(12), dp(30)
        )

        // Add the turbo speed control (initially hidden)
        turboSpeedControl = uiBuilder.addEditWithApplyButton(
            turboSpeed.toString(),
            "ms",
            Gravity.TOP or Gravity.START,
            0,
            0,
            100,
        ) { value ->
            // Parse and apply the new turbo speed
            val newSpeed = value.toLongOrNull() ?: 16L
            turboSpeed = newSpeed.coerceIn(10L, 1000L) // Limit between 10ms and 1000ms

            // Update GlobalSettings with the new speed
            GlobalSettings.turboSpeed = turboSpeed

            // Update the field to show the validated value
            turboSpeedControl.first.setText(turboSpeed.toString())
        }
        turboSpeedContainer = turboSpeedControl.first.parent as View
        turboSpeedContainer.visibility = View.GONE
        /* --- Save / Load ------------------------------------------------- */
        btnSave = uiBuilder.addCornerButton("Save", Gravity.BOTTOM or Gravity.START) {
            layoutManager.showSaveDialog()
        }.also { uiBuilder.styleAppButton(it, primary = true) }

        btnLoad = uiBuilder.addCornerButton("Load", Gravity.BOTTOM or Gravity.END) {
            showProfilesWithInputReset()
        }

        /* --- Add-FAB ---------------------------------------------------- */
        fabAdd = uiBuilder.addFloatingActionButton(
            android.R.drawable.ic_input_add,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            0, 90
        ) {
            showAddPicker()
        }

        // These original switches/inputs retain their listeners and state. The full-sized
        // tools panel hosts them on demand without placing them over game controls.
        for (view in listOf(switchSnap, switchHold, switchTurbo, switchSwipe, turboSpeedContainer,
            btnSave, btnLoad, fabAdd, btnPageManager, orientationControlsRow)) {
            canvas.removeView(view)
        }

        // Hide edit widgets by default
        updateOrientationButtonLabels()
        updateEditUi(GlobalSettings.editMode)
    }

    /**
     * Observe connection status changes and update UI
     */
    private fun observeConnectionStatus() {
        lifecycleScope.launch {
            var previousStatus = NetworkClient.connectionStatus.value
            NetworkClient.connectionStatus.collectLatest { status ->
                if (NetworkClient.outputMode != NetworkClient.OutputMode.SCX2 &&
                    previousStatus == NetworkClient.ConnectionStatus.CONNECTED &&
                    status != NetworkClient.ConnectionStatus.CONNECTED
                ) {
                    // Cancel armed/delayed Button Aim and Auto-tap state locally. In particular,
                    // a scheduled payload must not fire after reconnection.
                    releaseOutputs(showFeedback = false)
                    cancelTouchAimCalibration("Calibration stopped because the connection changed.")
                    resetToHomeAfterCleanup()
                }
                previousStatus = status
                updateConnectionStatusUI(status)
                updatePlayerRoleIndicator()
                if (status == NetworkClient.ConnectionStatus.CONNECTED) {
                    UdpClient.resendCameraFollowState()
                }
            }
        }

        lifecycleScope.launch {
            NetworkClient.localOutput.collectLatest { updatePlayerRoleIndicator() }
        }
        lifecycleScope.launch {
            NetworkClient.cameraFollowSnapshot.collectLatest { updatePlayerRoleIndicator() }
        }

        // Also observe error messages
        lifecycleScope.launch {
            NetworkClient.lastErrorMessage.collectLatest { errorMsg ->
                errorMsg?.let {
                    Toast.makeText(this@MainActivity, it, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Update connection status UI elements
     */
    private fun updateConnectionStatusUI(status: NetworkClient.ConnectionStatus) {
        connectionStatusText.text = when (status) {
            NetworkClient.ConnectionStatus.DISCONNECTED -> "Disconnected"
            NetworkClient.ConnectionStatus.CONNECTING -> "Connecting..."
            NetworkClient.ConnectionStatus.CONNECTED -> "Connected"
            NetworkClient.ConnectionStatus.ERROR -> "Connection Error"
        }

        val color = when (status) {
            NetworkClient.ConnectionStatus.DISCONNECTED -> ContextCompat.getColor(
                this,
                R.color.dark_text_primary
            )

            NetworkClient.ConnectionStatus.CONNECTING -> ContextCompat.getColor(
                this,
                R.color.app_status_connecting_text
            )

            NetworkClient.ConnectionStatus.CONNECTED -> ContextCompat.getColor(
                this,
                R.color.app_status_connected_text
            )

            NetworkClient.ConnectionStatus.ERROR -> ContextCompat.getColor(
                this,
                R.color.app_status_error_text
            )
        }

        connectionStatusText.setTextColor(color)

        updateToolbar()
    }

    private fun disconnectFromMenu() {
        releaseOutputs(showFeedback = false)
        resetToHomeAfterCleanup()
        cancelTouchAimCalibration("Calibration stopped before disconnecting.")
        NetworkClient.close()
    }

    private fun updateToolbar() {
        val mode = NetworkClient.outputMode
        val status = NetworkClient.connectionStatus.value
        val bridgeStatus = when (mode) {
            NetworkClient.OutputMode.PRACTICE -> "Practice"
            NetworkClient.OutputMode.SCX2 -> when (status) {
                NetworkClient.ConnectionStatus.CONNECTED -> "Connected"
                NetworkClient.ConnectionStatus.CONNECTING -> "Connecting"
                NetworkClient.ConnectionStatus.ERROR -> "Stopped"
                else -> "Disconnected"
            }
            NetworkClient.OutputMode.WINDOWS -> "Windows"
            NetworkClient.OutputMode.LEGACY_CB -> "Legacy bridge"
        }
        val camera = NetworkClient.cameraFollowSnapshot.value
        val cameraStatus = when {
            GlobalSettings.editMode -> "Editing"
            mode != NetworkClient.OutputMode.PRACTICE -> "Practice only"
            !camera.requestedEnabled -> "Off"
            !camera.effectiveEnabled || camera.source in setOf(
                com.example.simplecontroller.net.scx2.CameraFollowOutputSource.MACRO,
                com.example.simplecontroller.net.scx2.CameraFollowOutputSource.MANUAL_OVERRIDE) -> "Paused"
            else -> "On"
        }
        fun update(button: Button, title: Int, detail: String) {
            val label = getString(title) + "\n" + detail
            if (button.text.toString() != label) button.text = label
        }
        update(btnConnect, R.string.toolbar_bridge, bridgeStatus)
        update(btnCamera, R.string.toolbar_camera, cameraStatus)
        btnCamera.isEnabled = mode == NetworkClient.OutputMode.PRACTICE &&
            status == NetworkClient.ConnectionStatus.CONNECTED && !GlobalSettings.editMode
        toolsDialog?.showState(cameraStatus, btnCamera.isEnabled, UdpClient.localCameraSettings().strengthPercent.toInt(),
            connectionStatusText.text.toString())
    }

    private fun togglePracticeCamera() {
        if (NetworkClient.outputMode == NetworkClient.OutputMode.PRACTICE && btnCamera.isEnabled) UdpClient.toggleCameraFollow()
    }

    private fun showAllControls() {
        if (toolsDialog != null) return
        val edits = buildList<Pair<String, () -> Unit>> {
            add(getString(R.string.help_title) to ::showSupport)
            add((if (GlobalSettings.editMode) "Done editing" else "Edit layout") to { btnEdit.performClick() })
            add("Profiles · $layoutName" to { showProfilesWithInputReset() })
            if (!GlobalSettings.editMode) {
                add("Page · ${controllerProfile.pageName(displayedPageId).orEmpty()}" to ::showPlayPages)
            }
            if (GlobalSettings.editMode) {
                add("Save profile" to { btnSave.performClick() })
                add("Add control" to { fabAdd.performClick() })
                add(btnPageManager.text.toString() to { btnPageManager.performClick() })
                add(btnEditOrientation.text.toString() to { btnEditOrientation.performClick() })
                add(btnPlayOrientation.text.toString() to { btnPlayOrientation.performClick() })
            }
            add("Floating controls" to {
                if (GlobalSettings.editMode) floatingEditor.showManager() else floatingControls.showFinishList()
            })
            add("Toggle dark mode" to { ThemeManager.toggleDarkMode(this@MainActivity); recreate() })
        }
        val panel = ControllerToolsDialog(this, ::togglePracticeCamera,
            { strength -> if (btnCamera.isEnabled) UdpClient.setCameraFollowStrength(strength.toFloat()) },
            { activateReleaseAll() }, ::showBridgeMenu,
            listOf(switchSnap, switchHold, switchTurbo, turboSpeedContainer, switchSwipe), edits)
        toolsDialog = panel
        panel.open { toolsDialog = null }
        updateToolbar()
    }

    private fun showPlayPages() {
        val pages = controllerProfile.pages
        AlertDialog.Builder(this).setTitle("Choose page")
            .setSingleChoiceItems(pages.map { it.name }.toTypedArray(), pages.indexOfFirst { it.id == displayedPageId }) { dialog, index ->
                dialog.dismiss()
                activateControllerPageAction(PageAction.GO_TO, pages[index].id)
            }
            .setNegativeButton(R.string.toolbar_done, null).show()
    }

    private fun showProfilesWithInputReset() {
        releaseOutputs(showFeedback = false)
        layoutManager.showLoadDialog()
    }

    private fun showSupport() {
        releaseOutputs(showFeedback = false)
        CommunityHelp.showSupport(this) { showConnectionSettingsDialog() }
    }

    private fun showBridgeMenu() {
        val mode = NetworkClient.outputMode
        val live = NetworkClient.bridgeConnectionRequested || NetworkClient.connectionStatus.value in listOf(NetworkClient.ConnectionStatus.CONNECTED, NetworkClient.ConnectionStatus.CONNECTING)
        val actions = buildList<Pair<String, () -> Unit>> {
            if (mode != NetworkClient.OutputMode.PRACTICE && live) {
                val text = if (NetworkClient.connectionStatus.value == NetworkClient.ConnectionStatus.CONNECTING) "Cancel connection" else "Disconnect"
                add(text to ::disconnectFromMenu)
            }
            if (mode != NetworkClient.OutputMode.SCX2 || !live) {
                add(getString(R.string.toolbar_connect) to ::connectPicoFromMenu)
            }
            add(getString(R.string.toolbar_settings) to { showConnectionSettingsDialog() })
            add(getString(R.string.help_title) to ::showSupport)
            add(getString(R.string.toolbar_practice) to {
                if (NetworkClient.outputMode != NetworkClient.OutputMode.PRACTICE ||
                    NetworkClient.connectionStatus.value != NetworkClient.ConnectionStatus.CONNECTED) {
                    startOutput(NetworkClient.OutputMode.PRACTICE, networkPrefs.getString("serverHost", "").orEmpty(),
                        networkPrefs.getInt("serverPort", 9001), false, NetworkClient.getPlayerRole(),
                        savedTriggerMode())
                }
            })
        }
        AlertDialog.Builder(this).setTitle(R.string.toolbar_bridge)
            .setItems(actions.map { it.first }.toTypedArray()) { _, index -> actions[index].second() }
            .setNegativeButton(R.string.toolbar_done, null).show()
    }

    /** Keep each receiver's endpoint when hopping between Practice, Windows and a Pico.
     * The active receiver also reads the original shared keys written by earlier releases. */
    private fun savedEndpoint(mode: NetworkClient.OutputMode): Pair<String, Int> {
        val defaultHost = if (mode == NetworkClient.OutputMode.WINDOWS) "10.0.2.2" else ""
        val defaultPort = when (mode) {
            NetworkClient.OutputMode.SCX2 -> 42736
            NetworkClient.OutputMode.LEGACY_CB -> 9010
            else -> 9001
        }
        val wasActive = mode == savedOutputMode()
        val previousHost = if (wasActive) networkPrefs.getString("serverHost", defaultHost) else defaultHost
        val previousPort = if (wasActive) networkPrefs.getInt("serverPort", defaultPort) else defaultPort
        return networkPrefs.getString("receiverHost.${mode.name}", previousHost).orEmpty() to
            networkPrefs.getInt("receiverPort.${mode.name}", previousPort)
    }

    private fun connectPicoFromMenu() {
        val (host, port) = savedEndpoint(NetworkClient.OutputMode.SCX2)
        if (host.isBlank() || port !in 1..65535 || decodedSavedTriggerMode() == null) showConnectionSettingsDialog(NetworkClient.OutputMode.SCX2)
        else startOutput(NetworkClient.OutputMode.SCX2, host, port, false, NetworkClient.PlayerRole.PLAYER1,
            savedTriggerMode())
    }

    private fun openUsbTetherSettings() {
        networkPrefs.edit().putBoolean(USB_TETHER_RETURN_PENDING, true).apply()

        val settingsIntents = listOf(
            Intent(TETHER_SETTINGS_ACTION),
            Intent(Settings.ACTION_WIRELESS_SETTINGS)
        )
        var launched = false
        for (intent in settingsIntents) {
            try {
                startActivity(intent)
                launched = true
                break
            } catch (_: ActivityNotFoundException) {
                // Try the broader wireless settings screen on devices without a tether shortcut.
            } catch (_: SecurityException) {
                // Some manufacturers expose the action but prevent third-party launching.
            }
        }

        if (!launched) {
            networkPrefs.edit().remove(USB_TETHER_RETURN_PENDING).apply()
            Toast.makeText(
                this,
                "Unable to open tether settings on this device.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun connectViaUsbTether() {
        Toast.makeText(this, "Searching for the PC receiver over USB…", Toast.LENGTH_SHORT).show()
        releaseOutputs(showFeedback = false)
        resetToHomeAfterCleanup()
        cancelTouchAimCalibration("Calibration stopped before changing connections.")
        UdpClient.close()

        NetworkClient.discoverUsbTetherReceiver { endpoint ->
            if (endpoint == null) return@discoverUsbTetherReceiver

            val autoReconnect = networkPrefs.getBoolean("autoReconnect", false)
            // Use the discovered endpoint only for this runtime connection. The saved
            // manual host, port, and ConsoleBridge choice remain untouched.
            NetworkClient.setOutputMode(NetworkClient.OutputMode.WINDOWS)
            UdpClient.setConsoleBridgeEnabled(false)
            NetworkClient.updateSettings(endpoint.host, endpoint.port, autoReconnect)
            UdpClient.initialize(endpoint.host, endpoint.port)
            NetworkClient.start()

            Toast.makeText(
                this,
                "USB receiver found at ${endpoint.host}:${endpoint.port}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Load network settings from SharedPreferences
     */
    private fun loadNetworkSettings() {
        val host = networkPrefs.getString("serverHost", "10.0.2.2") ?: "10.0.2.2"
        val port = networkPrefs.getInt("serverPort", 9001)
        val autoReconnect = networkPrefs.getBoolean("autoReconnect", false)

        val savedMode = savedOutputMode()
        // Unknown or contradictory saved trigger choices cannot silently reconnect in another mode.
        val mode = if (savedMode == NetworkClient.OutputMode.SCX2 && decodedSavedTriggerMode() == null)
            NetworkClient.OutputMode.PRACTICE else savedMode
        NetworkClient.setOutputMode(mode, savedTriggerMode())

        // Load player role
        val savedPlayerRole =
            networkPrefs.getString("playerRole", NetworkClient.PlayerRole.PLAYER1.name)
        val playerRole = try {
            NetworkClient.PlayerRole.valueOf(savedPlayerRole ?: "PLAYER1")
        } catch (e: Exception) {
            NetworkClient.PlayerRole.PLAYER1
        }

        // Set player role and update connection settings
        NetworkClient.setPlayerRole(playerRole)
        NetworkClient.updateSettings(host, port, autoReconnect)

        // Also initialize UdpClient with the same settings
        UdpClient.initialize(host, port)

        // Read saved CBv0 preference and apply
        UdpClient.setConsoleBridgeEnabled(mode == NetworkClient.OutputMode.LEGACY_CB)
    }

    /**
     * Show dialog to configure server connection settings
     */
    private fun showConnectionSettingsDialog(initialMode: NetworkClient.OutputMode = savedOutputMode()) {
        releaseOutputs(showFeedback = false)
        val dialogView =
            LayoutInflater.from(this).inflate(R.layout.dialog_connection_settings, null)

        // Get references to dialog views
        val editHost = dialogView.findViewById<EditText>(R.id.editServerHost)
        val editPort = dialogView.findViewById<EditText>(R.id.editServerPort)
        val checkAutoReconnect = dialogView.findViewById<CheckBox>(R.id.checkboxAutoReconnect)
        val buttonUsbTetherMode =
            dialogView.findViewById<Button>(R.id.buttonUsbTetherMode)

        val destinations = listOf(NetworkClient.OutputMode.PRACTICE, NetworkClient.OutputMode.WINDOWS,
            NetworkClient.OutputMode.LEGACY_CB, NetworkClient.OutputMode.SCX2)
        val labels = listOf("Practice — nothing is sent", "Windows receiver", "Legacy ConsoleBridge (CBv0)", "Pico W / Xbox bridge (SCX2)")
        val target = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, labels)
            setSelection(destinations.indexOf(initialMode))
            contentDescription = "Output destination"
        }
        val triggerModes = com.example.simplecontroller.net.scx2.BridgeTriggerMode.entries
        val pressure = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, triggerModes.map { it.label })
            setSelection(triggerModes.indexOf(savedTriggerMode()))
            contentDescription = "LT and RT output"
        }
        val targetContainer = checkAutoReconnect.parent as ViewGroup
        targetContainer.addView(TextView(this).apply { text = "Output destination" }, 0)
        targetContainer.addView(target, 1)
        val triggerLabel = TextView(this).apply { text = "LT and RT output" }
        targetContainer.addView(triggerLabel, 2)
        targetContainer.addView(pressure, 3)
        val triggerHelp = TextView(this).apply {
            text = "USB buttons: full presses through X1/X2 on the Adaptive Controller. Adjustable (circuit): separate trigger hardware. USB adjustable: your chosen pressure through Cronus Zen with matching Pico firmware. Saved layouts stay unchanged."
        }
        targetContainer.addView(triggerHelp, 4)

        // Add radio buttons for player selection
        val radioPlayer1 = dialogView.findViewById<RadioButton>(R.id.radioPlayer1)
        val radioPlayer2 = dialogView.findViewById<RadioButton>(R.id.radioPlayer2)

        // Set default selected player based on current setting
        if (NetworkClient.getPlayerRole() == NetworkClient.PlayerRole.PLAYER1) {
            radioPlayer1.isChecked = true
        } else {
            radioPlayer2.isChecked = true
        }

        // Fill in current values
        fun fillEndpoint(mode: NetworkClient.OutputMode) {
            val (host, port) = savedEndpoint(mode)
            editHost.setText(host); editPort.setText(port.toString())
        }
        fillEndpoint(initialMode)
        checkAutoReconnect.isChecked = networkPrefs.getBoolean("autoReconnect", false)

        var previousTarget = initialMode
        target.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val mode = destinations[position]
                val network = mode != NetworkClient.OutputMode.PRACTICE
                editHost.isEnabled = network; editPort.isEnabled = network
                pressure.visibility = if (mode == NetworkClient.OutputMode.SCX2) View.VISIBLE else View.GONE
                triggerLabel.visibility = pressure.visibility
                triggerHelp.visibility = pressure.visibility
                checkAutoReconnect.isEnabled = mode == NetworkClient.OutputMode.WINDOWS || mode == NetworkClient.OutputMode.LEGACY_CB
                radioPlayer1.isEnabled = mode == NetworkClient.OutputMode.WINDOWS
                radioPlayer2.isEnabled = mode == NetworkClient.OutputMode.WINDOWS
                buttonUsbTetherMode.visibility = if (mode == NetworkClient.OutputMode.WINDOWS) View.VISIBLE else View.GONE
                if (mode != previousTarget) {
                    fillEndpoint(mode)
                    previousTarget = mode
                }
            }
        }

        // Apply theme to dialog elements
        editHost.setTextColor(ContextCompat.getColor(this, R.color.dark_text_primary))
        editHost.setHintTextColor(ContextCompat.getColor(this, R.color.dark_text_secondary))
        editPort.setTextColor(ContextCompat.getColor(this, R.color.dark_text_primary))
        editPort.setHintTextColor(ContextCompat.getColor(this, R.color.dark_text_secondary))
        checkAutoReconnect.setTextColor(ContextCompat.getColor(this, R.color.dark_text_primary))
        radioPlayer1.setTextColor(ContextCompat.getColor(this, R.color.dark_text_primary))
        radioPlayer2.setTextColor(ContextCompat.getColor(this, R.color.dark_text_primary))

        // Apply dark theme to dialog background
        dialogView.setBackgroundColor(ContextCompat.getColor(this, R.color.ui_surface))
        val connectionStyle = PropertySheetStyle(this)
        connectionStyle.styleContent(dialogView)

        // Show the dialog with themed appearance
        val dialog = AlertDialog.Builder(this)
            .setTitle("Connection")
            .setView(dialogView)
            .setPositiveButton("Connect", null)
            .setNegativeButton("Cancel", null)
            .create()

        // Apply styling to dialog buttons
        dialog.show()
        connectionStyle.dialog(dialog)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val mode = destinations[target.selectedItemPosition]
            val host = editHost.text.toString().trim()
            val port = editPort.text.toString().toIntOrNull()
            if (mode != NetworkClient.OutputMode.PRACTICE && host.isBlank()) {
                editHost.error = "Enter the receiver's Wi-Fi address."
                editHost.requestFocus()
                return@setOnClickListener
            }
            if (mode != NetworkClient.OutputMode.PRACTICE && (port == null || port !in 1..65535)) {
                editPort.error = "Enter a port from 1 to 65535."
                editPort.requestFocus()
                return@setOnClickListener
            }
            if (mode == NetworkClient.OutputMode.SCX2 &&
                !com.example.simplecontroller.net.scx2.BridgeConnectionIntent.Endpoint(host, port ?: 0, triggerModes[pressure.selectedItemPosition]).valid) {
                editHost.error = "Enter a valid Pico network address without spaces."
                editHost.requestFocus()
                return@setOnClickListener
            }
            val player = if (radioPlayer1.isChecked) NetworkClient.PlayerRole.PLAYER1 else NetworkClient.PlayerRole.PLAYER2
            dialog.dismiss()
            startOutput(mode, host, port ?: 9001, checkAutoReconnect.isChecked, player, triggerModes[pressure.selectedItemPosition])
        }
        buttonUsbTetherMode.setOnClickListener {
            dialog.dismiss()
            openUsbTetherSettings()
        }
    }

    private fun startOutput(mode: NetworkClient.OutputMode, host: String, port: Int, autoReconnect: Boolean,
                            playerRole: NetworkClient.PlayerRole, triggers: com.example.simplecontroller.net.scx2.BridgeTriggerMode) {
        val useLegacy = mode == NetworkClient.OutputMode.LEGACY_CB
        val prefs = networkPrefs.edit().putString("outputMode", mode.name)
            .putBoolean("useCbv0", useLegacy).putBoolean("adjustableTriggers", triggers == com.example.simplecontroller.net.scx2.BridgeTriggerMode.ADJUSTABLE)
            .putString("triggerMode", triggers.name)
        val oldMode = savedOutputMode()
        if (oldMode != NetworkClient.OutputMode.PRACTICE && oldMode != mode) {
            val (oldHost, oldPort) = savedEndpoint(oldMode)
            prefs.putString("receiverHost.${oldMode.name}", oldHost).putInt("receiverPort.${oldMode.name}", oldPort)
        }
        if (mode != NetworkClient.OutputMode.PRACTICE) {
            prefs.putString("receiverHost.${mode.name}", host).putInt("receiverPort.${mode.name}", port)
                .putString("serverHost", host).putInt("serverPort", port)
                .putBoolean("autoReconnect", autoReconnect).putString("playerRole", playerRole.name)
        }
        prefs.apply()
        releaseOutputs(showFeedback = false)
        resetToHomeAfterCleanup()
        cancelTouchAimCalibration("Calibration stopped before changing connections.")
        NetworkClient.setOutputMode(mode, triggers)
        NetworkClient.setPlayerRole(playerRole)
        NetworkClient.updateSettings(host, port, autoReconnect)
        UdpClient.initialize(host, port)
        UdpClient.setConsoleBridgeEnabled(useLegacy)
        NetworkClient.start()
        updatePlayerRoleIndicator()
    }

    /**
     * Update the UI to indicate which player role is active
     */
    private fun decodedSavedTriggerMode(): com.example.simplecontroller.net.scx2.BridgeTriggerMode? = runCatching {
        com.example.simplecontroller.net.scx2.BridgeTriggerMode.decode(networkPrefs.getString("triggerMode", null),
            networkPrefs.getBoolean("adjustableTriggers", false))
    }.getOrNull()

    private fun savedTriggerMode(): com.example.simplecontroller.net.scx2.BridgeTriggerMode =
        decodedSavedTriggerMode() ?: com.example.simplecontroller.net.scx2.BridgeTriggerMode.OFF

    private fun savedOutputMode(): NetworkClient.OutputMode {
        val saved = networkPrefs.getString("outputMode", null)
        return runCatching { NetworkClient.OutputMode.valueOf(saved.orEmpty()) }.getOrElse {
            when {
                networkPrefs.getBoolean("useCbv0", false) -> NetworkClient.OutputMode.LEGACY_CB
                networkPrefs.contains("serverHost") -> NetworkClient.OutputMode.WINDOWS
                else -> NetworkClient.OutputMode.PRACTICE
            }
        }
    }

    private fun updatePlayerRoleIndicator() {
        if (NetworkClient.localOutputSelected) {
            val state = NetworkClient.localOutput.value
            val g = if (NetworkClient.outputMode == NetworkClient.OutputMode.SCX2)
                NetworkClient.triggerMode.output(state.gamepad, state.leftTriggerRequested, state.rightTriggerRequested) else state.gamepad
            val buttons = com.example.simplecontroller.net.scx2.Scx2CanonicalOutput.BUTTONS
                .filterIndexed { index, _ -> g.buttons and (1 shl index) != 0 }.joinToString(" ").ifEmpty { "None" }
            val dpad = listOf("Up", "Up-right", "Right", "Down-right", "Down", "Down-left", "Left", "Up-left")
                .getOrNull(g.hat)?.let { " · D-pad $it" }.orEmpty()
            val title = if (NetworkClient.outputMode == NetworkClient.OutputMode.PRACTICE)
                "Practice — nothing sent" else "Pico: ${NetworkClient.connectionStatus.value.name.lowercase()} · LT/RT ${when (NetworkClient.triggerMode) {
                    com.example.simplecontroller.net.scx2.BridgeTriggerMode.OFF -> "off"
                    com.example.simplecontroller.net.scx2.BridgeTriggerMode.USB_BUTTONS -> "USB full press"
                    com.example.simplecontroller.net.scx2.BridgeTriggerMode.ADJUSTABLE -> "adjustable"
                    com.example.simplecontroller.net.scx2.BridgeTriggerMode.CRONUS_ZEN -> "USB adjustable · Cronus Zen"
                }}"
            val camera = NetworkClient.cameraFollowSnapshot.value
            val cameraLabel = when {
                NetworkClient.outputMode != NetworkClient.OutputMode.PRACTICE -> "Unavailable on Pico"
                !camera.requestedEnabled -> "OFF"
                !camera.effectiveEnabled || camera.source == com.example.simplecontroller.net.scx2.CameraFollowOutputSource.MACRO ||
                    camera.source == com.example.simplecontroller.net.scx2.CameraFollowOutputSource.MANUAL_OVERRIDE -> "PAUSED"
                else -> "ON"
            }
            val readout = "$title\n$buttons$dpad · LT ${g.leftTrigger * 100 / 65535}% · RT ${g.rightTrigger * 100 / 65535}%" +
                "\nL ${g.leftX * 100 / 32767},${g.leftY * 100 / 32767} · R ${g.rightX * 100 / 32767},${g.rightY * 100 / 32767}" +
                "\nCamera: $cameraLabel" +
                if (state.heldKeys.isNotEmpty() || state.heldMouseButtons.isNotEmpty() || state.pointerX != 0.0 || state.pointerY != 0.0 || state.scroll != 0.0)
                    "\nKeys: ${state.heldKeys.joinToString(" ").ifEmpty { "None" }} · Mouse: ${state.heldMouseButtons.joinToString(" ").ifEmpty { "None" }}" +
                    "\nX ${state.pointerX.toInt()} Y ${state.pointerY.toInt()} · Scroll ${state.scroll.toInt()}" else ""
            connectionStatusText.text = if (controllerProfile.safeControlArea &&
                resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                val lines = readout.lines()
                listOf(lines[0] + " · " + lines[3], lines[1],
                    (listOf(lines[2]) + lines.drop(4)).joinToString(" · ")).joinToString("\n")
            } else readout
            updateToolbar()
            return
        }

        // Get current player role
        val playerRole = NetworkClient.getPlayerRole()

        // Update the connection status text to include player role
        val statusText = when (NetworkClient.connectionStatus.value) {
            NetworkClient.ConnectionStatus.DISCONNECTED -> "Disconnected"
            NetworkClient.ConnectionStatus.CONNECTING -> "Connecting..."
            NetworkClient.ConnectionStatus.CONNECTED -> "Connected"
            NetworkClient.ConnectionStatus.ERROR -> "Connection Error"
        }

        connectionStatusText.text = when (playerRole) {
            NetworkClient.PlayerRole.PLAYER1 -> "P1: $statusText"
            NetworkClient.PlayerRole.PLAYER2 -> "P2: $statusText"
        }

        // Optional: Update the background color of the status indicator
        val bgColor = when (playerRole) {
            NetworkClient.PlayerRole.PLAYER1 -> ContextCompat.getColor(this, R.color.player1_color)
            NetworkClient.PlayerRole.PLAYER2 -> ContextCompat.getColor(this, R.color.player2_color)
        }
        connectionStatusText.backgroundTintList = ColorStateList.valueOf(bgColor)
        updateToolbar()
    }

    /**
     * Show picker dialog to add a new control or create a template layout
     */
    private fun showAddPicker() {
        val options = arrayOf(
            "Button",
            "Stick",
            "Response Curve Stick",
            "Radial Response Curve Stick",
            "TouchPad",
            "Touch Aim",
            "Touch Aim (Game Mouse)",
            "Re-center Button",
            "--- Layouts ---",
            "Xbox Controller Layout",
            "Player 1 Xbox Layout",
            "Player 2 Xbox Layout",
            "Floating ring",
            "Floating Button / group from selection",
            "Manage floating controls"
        )

        // Show dialog with dark theme
        val dialog = AlertDialog.Builder(this)
            .setTitle("Add control or layout…")
            .setItems(options) { _, i ->
                when (i) {
                    0 -> layoutManager.createControl(ControlType.BUTTON)
                    1 -> layoutManager.createControl(ControlType.STICK)
                    2 -> layoutManager.createControl(ControlType.CURVED_STICK)
                    3 -> layoutManager.createControl(ControlType.RADIAL_CURVED_STICK)
                    4 -> layoutManager.createControl(ControlType.TOUCHPAD)
                    5 -> layoutManager.createControl(ControlType.TOUCH_AIM)
                    6 -> layoutManager.createControl(ControlType.TOUCH_AIM, touchAimGameMouse = true)
                    7 -> layoutManager.createControl(ControlType.RECENTER)
                    8 -> {} // This is just a divider item
                    9 -> createXboxControllerLayout()
                    10 -> createPlayer1XboxLayout()
                    11 -> createPlayer2XboxLayout()
                    12 -> floatingEditor.showRingMenu()
                    13 -> floatingEditor.selectButtons()
                    14 -> floatingEditor.showManager()
                }
            }
            .create()

        dialog.window?.setBackgroundDrawableResource(R.color.dark_surface)
        dialog.show()

        // Apply text color to list items
        dialog.listView?.let { listView ->
            for (i in 0 until listView.count) {
                val v = listView.getChildAt(i)
                if (v is TextView) {
                    v.setTextColor(ContextCompat.getColor(this, R.color.dark_text_primary))
                }
            }
        }
    }

    /**
     * Update UI components when edit mode changes
     */
    private fun updateEditUi(edit: Boolean) {
        val editWidgets = listOf(
            btnSave,
            btnLoad,
            fabAdd,
            btnPageManager,
            orientationControlsRow
        )
        uiBuilder.updateViewsVisibility(editWidgets, edit)
        if (edit) orientationControlsRow.bringToFront()

        if (::btnCamera.isInitialized) updateToolbar()

        // Global switches stay available in All controls in both modes
        val globalSwitches = listOf(switchSnap, switchHold, switchTurbo, switchSwipe)
        uiBuilder.updateViewsVisibility(globalSwitches, true)

        // Connection UI always visible
        connectionStatusText.visibility = View.VISIBLE
        btnConnect.visibility = View.VISIBLE
        if (controllerProfile.safeControlArea) {
            // The header keeps fixed metrics; edit-only tools change visibility, never the plane.
            controlAreaRoot.updateEditorVisibility(editWidgets, edit)
        }
    }

    /**
     * Remove a control from the layout
     */
    fun removeControl(c: Control) {
        if (controllerProfile.page(displayedPageId)?.floatingZones?.any { c.id in it.memberIds } == true) {
            Toast.makeText(this, "Ungroup this Button before removing it.", Toast.LENGTH_LONG).show()
            return
        }
        if (activeTouchAimCalibration != null) {
            cancelTouchAimCalibration("Calibration stopped because a control was removed.")
        }
        controls.remove(c)

        val viewToRemove = canvas.children.firstOrNull {
            it is ControlView && it.model.id == c.id
        }

        if (viewToRemove != null) {
            canvas.removeView(viewToRemove)
        }
    }

    // === LayoutManager.LayoutCallback implementation ===

    /**
     * Called when a layout is loaded
     */
    override fun onLayoutLoaded(layoutName: String) {
        this.layoutName = layoutName
        profileAutosaveBlocked = false
    }

    /**
     * Called when a layout is saved
     */
    override fun onLayoutSaved(layoutName: String) {
        this.layoutName = layoutName
        profileAutosaveBlocked = false
    }

    override fun controllerProfileForSave(): ControllerProfile {
        syncDisplayedPageIntoProfile()
        return controllerProfile
    }

    override fun activeLayoutName(): String = layoutName

    override fun onControllerProfileLoaded(
        layoutName: String,
        result: ControllerProfileLoadResult
    ): Boolean {
        releaseOutputs(showFeedback = false)
        profileAutosaveBlocked = false
        installProfile(layoutName, result.profile)
        if (result.warnings.isNotEmpty()) {
            Toast.makeText(
                this,
                result.warnings.take(3).joinToString("\n"),
                Toast.LENGTH_LONG
            ).show()
        }
        return true
    }

    override fun onGameStartersRequested() {
        showGameStarterDialog(this, firstLaunch = false, ::openGameStarter)
    }

    private fun openGameStarter(starter: GameStarter): Boolean {
        if ((GlobalSettings.editMode || hasEditsToKeepBeforeStarter) && profileAutosaveBlocked) {
            Toast.makeText(this, R.string.starter_save_needed, Toast.LENGTH_LONG).show()
            return false
        }
        val saved = runCatching {
            val template = GameStarters.read(this, starter)
            val currentHasNoFile = readControllerProfile(this, layoutName) == StoredControllerProfileResult.NotFound
            if (!profileAutosaveBlocked && (GlobalSettings.editMode || hasEditsToKeepBeforeStarter || currentHasNoFile)) {
                saveControllerProfile(this, layoutName, controllerProfileForSave()).getOrThrow()
            }
            GameStarters.saveCopy(this, getString(starter.title), template, layoutName)
        }.getOrElse {
            Toast.makeText(this, R.string.starter_open_failed, Toast.LENGTH_LONG).show()
            return false
        }
        onControllerProfileLoaded(saved.first, saved.second)
        if (GlobalSettings.editMode) finishLeavingEditMode()
        Toast.makeText(this, getString(R.string.starter_opened, saved.first), Toast.LENGTH_SHORT).show()
        return true
    }

    override fun onNewControllerProfileRequested(): Boolean {
        releaseOutputs(showFeedback = false)
        profileAutosaveBlocked = false
        val baseId = newPageId()
        installProfile(
            "untitled",
            ControllerProfile(
                homePageId = baseId,
                pages = listOf(ControllerPage(baseId, "Base", emptyList()))
            )
        )
        Toast.makeText(this, "New blank profile created", Toast.LENGTH_SHORT).show()
        return true
    }

    override fun onExternalProfileImportRequested(): Boolean {
        runCatching {
            openProfileDocumentLauncher.launch(
                arrayOf("application/json", "text/json", "text/plain", "application/octet-stream")
            )
        }.onFailure {
            Toast.makeText(
                this,
                "Android's document picker could not be opened: ${friendlyProfileFileError(it)}",
                Toast.LENGTH_LONG
            ).show()
        }
        return true
    }

    override fun onExternalProfileExportRequested(
        profileName: String,
        profile: ControllerProfile
    ): Boolean {
        lifecycleScope.launch {
            val staged = withContext(Dispatchers.IO) {
                stageControllerProfileTransfer(this@MainActivity, profileName, profile)
            }
            if (staged.isFailure) {
                withContext(Dispatchers.IO) {
                    clearStagedControllerProfileTransfer(this@MainActivity)
                }
                Toast.makeText(
                    this@MainActivity,
                    "Could not prepare the profile export: ${friendlyProfileFileError(staged.exceptionOrNull())}",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                runCatching {
                    createProfileDocumentLauncher.launch(
                        controllerProfileTransferFileName(profileName)
                    )
                }.onFailure {
                    withContext(Dispatchers.IO) {
                        clearStagedControllerProfileTransfer(this@MainActivity)
                    }
                    Toast.makeText(
                        this@MainActivity,
                        "Android's document picker could not be opened: ${friendlyProfileFileError(it)}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        return true
    }

    override fun onAllProfilesExportRequested(): Boolean {
        val activeName = layoutName
        val activeProfile = controllerProfileForSave()
        lifecycleScope.launch {
            val staged = withContext(Dispatchers.IO) {
                createControllerProfilesBackup(
                    this@MainActivity,
                    activeName,
                    activeProfile
                ).mapCatching(::encodeControllerProfilesBackup)
                    .fold(
                        onSuccess = { stageProfileTransferDocument(this@MainActivity, it) },
                        onFailure = { Result.failure(it) }
                    )
            }
            if (staged.isFailure) {
                withContext(Dispatchers.IO) {
                    clearStagedControllerProfileTransfer(this@MainActivity)
                }
                Toast.makeText(
                    this@MainActivity,
                    "Could not prepare the all-profiles export: ${friendlyProfileFileError(staged.exceptionOrNull())}",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                runCatching {
                    createProfileDocumentLauncher.launch(controllerProfilesBackupFileName())
                }.onFailure {
                    withContext(Dispatchers.IO) {
                        clearStagedControllerProfileTransfer(this@MainActivity)
                    }
                    Toast.makeText(
                        this@MainActivity,
                        "Android's document picker could not be opened: ${friendlyProfileFileError(it)}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        return true
    }

    private fun writeAndVerifyProfileExport(uri: Uri) {
        lifecycleScope.launch {
            val attempt = withContext(Dispatchers.IO) {
                var writeCompleted = false
                val result = runCatching {
                    val stagedText = readStagedControllerProfileTransfer(this@MainActivity)
                        .getOrThrow()
                    val expected = decodeProfileTransferDocument(stagedText, "Exported Profile")
                    val output = contentResolver.openOutputStream(uri, "wt")
                        ?: error("The selected location could not be opened for writing.")
                    output.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                        writer.write(stagedText)
                    }
                    writeCompleted = true

                    val writtenText = contentResolver.openInputStream(uri)?.use {
                        readControllerProfileTransferText(it)
                    } ?: error("The exported file could not be reopened for verification.")
                    val verified = decodeProfileTransferDocument(
                        writtenText,
                        "Exported Profile"
                    )
                    require(verified == expected) {
                        "The exported profile data did not match the data prepared by SimpleController."
                    }
                    verified
                }
                clearStagedControllerProfileTransfer(this@MainActivity)
                writeCompleted to result
            }

            val verified = attempt.second.getOrNull()
            if (verified != null) {
                val message = when (verified) {
                    is ProfileTransferDocument.Single ->
                        "Export verified: ${verified.imported.suggestedName} " +
                            "(${verified.imported.result.profile.pages.size} pages)"
                    is ProfileTransferDocument.AllProfiles -> {
                        val backup = verified.loaded.backup
                        val reusableCount = backup.touchAimCalibrations.size +
                            backup.touchAimManualProfiles.size + backup.buttonAimProfiles.size +
                            backup.stickDirectionalProfiles.size
                        "All-profile export verified: ${backup.controllerProfiles.size} controller, " +
                            "$reusableCount reusable profiles"
                    }
                }
                Toast.makeText(
                    this@MainActivity,
                    message,
                    Toast.LENGTH_LONG
                ).show()
            } else {
                val prefix = if (attempt.first) {
                    "The file was written, but verification failed"
                } else {
                    "Profile export failed"
                }
                Toast.makeText(
                    this@MainActivity,
                    "$prefix: ${friendlyProfileFileError(attempt.second.exceptionOrNull())}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun readAndPromptForProfileImport(uri: Uri) {
        lifecycleScope.launch {
            val document = withContext(Dispatchers.IO) {
                runCatching {
                    val displayName = profileDocumentDisplayName(uri)
                    val text = contentResolver.openInputStream(uri)?.use {
                        readControllerProfileTransferText(it)
                    } ?: error("The selected file could not be opened.")
                    displayName to decodeProfileTransferDocument(text, displayName)
                }
            }
            document.fold(
                onSuccess = { (displayName, document) ->
                    when (document) {
                        is ProfileTransferDocument.Single ->
                            promptForProfileImport(document.imported, displayName)
                        is ProfileTransferDocument.AllProfiles ->
                            promptForAllProfilesImport(document.loaded, displayName)
                    }
                },
                onFailure = {
                    Toast.makeText(
                        this@MainActivity,
                        "Profile import failed: ${friendlyProfileFileError(it)}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }
    }

    private fun promptForProfileImport(
        imported: ImportedControllerProfile,
        sourceDisplayName: String
    ) {
        val input = EditText(this).apply {
            setText(uniqueImportedProfileName(imported.suggestedName, listLayouts(this@MainActivity)))
            selectAll()
        }
        val warnings = imported.result.warnings
        val dialog = AlertDialog.Builder(this)
            .setTitle("Import controller profile")
            .setMessage(
                buildString {
                    append("File: $sourceDisplayName\n")
                    append("Pages: ${imported.result.profile.pages.size}\n\n")
                    append("The imported profile will be saved as a new profile and loaded at its Home page. ")
                    append("Your current profile will not be overwritten.")
                    if (imported.result.migratedFromSinglePage) {
                        append("\n\nThis older one-page layout will be imported as a Base page.")
                    }
                    if (warnings.isNotEmpty()) {
                        append("\n\nProfile warning:\n")
                        append(warnings.take(3).joinToString("\n"))
                    }
                }
            )
            .setView(input)
            .setPositiveButton("Import and load", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            val importButton = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
            importButton.setOnClickListener {
                val requestedName = input.text.toString().trim()
                val problem = validateImportedProfileName(requestedName)
                if (problem != null) {
                    input.error = problem
                    return@setOnClickListener
                }
                if (listLayouts(this).any { it.equals(requestedName, ignoreCase = true) }) {
                    input.error = "That profile already exists. Choose a new name."
                    return@setOnClickListener
                }

                importButton.isEnabled = false
                lifecycleScope.launch {
                    val verified = withContext(Dispatchers.IO) {
                        if (listLayouts(this@MainActivity).any {
                                it.equals(requestedName, ignoreCase = true)
                            }
                        ) return@withContext null

                        val saved = saveControllerProfile(
                            this@MainActivity,
                            requestedName,
                            imported.result.profile
                        )
                        if (saved.isFailure) return@withContext null
                        val reloaded = (readControllerProfile(
                            this@MainActivity,
                            requestedName
                        ) as? StoredControllerProfileResult.Loaded)?.result
                        reloaded?.takeIf { it.profile == imported.result.profile } ?: run {
                            deleteControllerProfile(this@MainActivity, requestedName)
                            null
                        }
                    }
                    importButton.isEnabled = true
                    if (verified == null) {
                        Toast.makeText(
                            this@MainActivity,
                            "The imported profile could not be saved and verified; the current profile is unchanged.",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        dialog.dismiss()
                        onControllerProfileLoaded(requestedName, verified)
                        Toast.makeText(
                            this@MainActivity,
                            "Imported \"$requestedName\" with ${verified.profile.pages.size} pages",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun promptForAllProfilesImport(
        loaded: ControllerProfilesBackupLoadResult,
        sourceDisplayName: String
    ) {
        val backup = loaded.backup
        val reusableCount = backup.touchAimCalibrations.size +
            backup.touchAimManualProfiles.size + backup.buttonAimProfiles.size +
            backup.stickDirectionalProfiles.size
        val dialog = AlertDialog.Builder(this)
            .setTitle("Import all profiles")
            .setMessage(
                buildString {
                    append("File: $sourceDisplayName\n")
                    append("Controller profiles: ${backup.controllerProfiles.size}\n")
                    append("Reusable profiles: $reusableCount\n\n")
                    append("This adds every profile in the backup. Existing profiles are kept, ")
                    append("and duplicate names receive an Imported suffix. ")
                    append("Your currently loaded profile stays unchanged.")
                    if (loaded.warnings.isNotEmpty()) {
                        append("\n\nProfile warning:\n")
                        append(loaded.warnings.take(3).joinToString("\n"))
                    }
                }
            )
            .setPositiveButton("Import all", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            val importButton = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
            importButton.setOnClickListener {
                importButton.isEnabled = false
                lifecycleScope.launch {
                    val imported = withContext(Dispatchers.IO) {
                        importControllerProfilesBackup(this@MainActivity, loaded)
                    }
                    importButton.isEnabled = true
                    imported.fold(
                        onSuccess = { result ->
                            dialog.dismiss()
                            val importedReusable = result.touchAimCalibrationCount +
                                result.touchAimManualProfileCount + result.buttonAimProfileCount +
                                result.stickDirectionalProfileCount + result.floatingBoostProfileCount +
                                result.floatingRingPresetCount
                            Toast.makeText(
                                this@MainActivity,
                                "Imported ${result.controllerProfileCount} controller and " +
                                    "$importedReusable reusable profiles",
                                Toast.LENGTH_LONG
                            ).show()
                        },
                        onFailure = {
                            Toast.makeText(
                                this@MainActivity,
                                "All-profile import failed; no existing profile was overwritten: " +
                                    friendlyProfileFileError(it),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    )
                }
            }
        }
        dialog.show()
    }

    private fun profileDocumentDisplayName(uri: Uri): String {
        val displayName = runCatching {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) null else {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index < 0) null else cursor.getString(index)
                }
            }
        }.getOrNull()
        return sanitizeSuggestedProfileName(displayName.orEmpty())
    }

    private fun friendlyProfileFileError(error: Throwable?): String =
        error?.message?.takeIf(String::isNotBlank) ?: "unknown file error"

    /**
     * Clear all control views
     */
    override fun clearControlViews() {
        cancelFloatingSelection()
        floatingControls.clear()
        cancelTouchAimCalibration("Calibration stopped because the layout changed.")
        canvas.children.filter { it.tag == "control" }.toList()
            .forEach { canvas.removeView(it) }
    }

    fun controllerPageOptions(): List<ControllerPageOption> =
        controllerProfile.pages.map { ControllerPageOption(it.id, it.name) }

    private fun syncDisplayedPageIntoProfile() {
        if (orientationRenderPending || !::controllerProfile.isInitialized || displayedPageId.isBlank()) {
            return
        }
        val index = controllerProfile.pages.indexOfFirst { it.id == displayedPageId }
        if (index < 0) return
        val updated = controllerProfile.pages.toMutableList()
        val existing = updated[index]
        val copiedControls = deepCopyControls(controls)
        val controlIds = copiedControls.mapTo(mutableSetOf()) { it.id }
        val priorCurrentGeometry = existing.geometryFor(activeLayoutOrientation)
        val canvasWidth = renderedCanvasWidth.takeIf { controllerProfile.safeControlArea && it > 0f }
            ?: canvas.width.toFloat().takeIf { it > 0f }
            ?: priorCurrentGeometry?.canvasWidth
            ?: 0f
        val canvasHeight = renderedCanvasHeight.takeIf { controllerProfile.safeControlArea && it > 0f }
            ?: canvas.height.toFloat().takeIf { it > 0f }
            ?: priorCurrentGeometry?.canvasHeight
            ?: 0f
        val displayedGeometry = capturePageGeometry(copiedControls, canvasWidth, canvasHeight)
        val geometryEdited = displayedGeometry.controls != renderedControlGeometry
        val savedGeometry = if (controllerProfile.safeControlArea) {
            com.example.simplecontroller.ui.captureEditedSafeGeometry(priorCurrentGeometry,
                renderedControlGeometry, copiedControls, canvasWidth, canvasHeight)
        } else displayedGeometry
        val updatedZones = if (!GlobalSettings.editMode || (controllerProfile.safeControlArea && !geometryEdited))
            existing.floatingZones else existing.floatingZones.map { zone ->
            if (zone.kind == FloatingZoneKind.RING) zone else {
                val geometry = copiedControls.filter { it.id in zone.memberIds }.associate {
                    it.id to ControlGeometry(it.x, it.y, it.w, it.h)
                }
                val wrapper = zone.geometryFor(activeLayoutOrientation) ?: run {
                    val source = zone.geometryFor(activeLayoutOrientation.opposite())!!
                    val sx = if (source.canvasWidth > 0f) canvasWidth / source.canvasWidth else 1f
                    val sy = if (source.canvasHeight > 0f) canvasHeight / source.canvasHeight else 1f
                    FloatingZoneGeometry(canvasWidth, canvasHeight, source.zone.let {
                        ControlGeometry(it.x * sx, it.y * sy, it.w * sx, it.h * sy)
                    })
                }
                zone.withGeometry(activeLayoutOrientation, wrapper).let {
                    if (activeLayoutOrientation == LayoutOrientation.PORTRAIT) it.copy(
                        portraitMemberGeometry = geometry,
                        portraitRestorationGeometry = geometry + it.portraitRestorationGeometry
                    ) else it.copy(
                        landscapeMemberGeometry = geometry,
                        landscapeRestorationGeometry = geometry + it.landscapeRestorationGeometry
                    )
                }
            }
        }
        var page = existing.copy(controls = copiedControls, floatingZones = updatedZones).withGeometry(
            activeLayoutOrientation,
            savedGeometry
        )
        val otherOrientation = activeLayoutOrientation.opposite()
        page = page.withGeometry(
            otherOrientation,
            page.geometryFor(otherOrientation)?.withOnlyControlIds(controlIds)
        )
        updated[index] = page
        controllerProfile = controllerProfile.copy(pages = updated)
    }

    private fun clearControlViewsSafely() {
        cancelFloatingSelection()
        floatingControls.clear()
        cancelTouchAimCalibration("Calibration stopped because the page changed.")
        canvas.children.filterIsInstance<ControlView>().toList().forEach { view ->
            view.markSafeForSilentDetach()
            canvas.removeView(view)
        }
    }

    private fun renderPage(pageId: String, safeToDetach: Boolean) {
        val page = controllerProfile.page(pageId) ?: return
        if (safeToDetach) clearControlViewsSafely() else clearControlViews()
        controls.clear()
        controls.addAll(
            deepCopyControls(
                page.controlsForFloatingOrientation(
                    activeLayoutOrientation,
                    canvas.width.toFloat(),
                    canvas.height.toFloat()
                )
            )
        )
        displayedPageId = page.id
        val hidden = floatingControls.hiddenMemberIds()
        layoutManager.spawnControlViews { it.id !in hidden }
        floatingControls.bindPage()
        renderedCanvasWidth = canvas.width.toFloat()
        renderedCanvasHeight = canvas.height.toFloat()
        renderedControlGeometry = capturePageGeometry(controls, renderedCanvasWidth, renderedCanvasHeight).controls
        updatePageSelectorLabel()
    }

    private fun installProfile(name: String, profile: ControllerProfile) {
        hasEditsToKeepBeforeStarter = false
        if (controlAreaRoot.usesSafeControlArea != profile.safeControlArea) {
            // Loading a different plane is asynchronous. Never save old controls into the new
            // profile, or accept input on the detached geometry while waiting for measurement.
            orientationRenderPending = true
            clearControlViewsSafely()
            controls.clear()
        }
        renderedCanvasWidth = 0f
        renderedCanvasHeight = 0f
        renderedControlGeometry = emptyMap()
        controllerProfile = profile
        GlobalSettings.swipeMode = profile.swipeMode
        updateProfileSwipePresentation()
        pageSession = ControllerPageSession(profile.homePageId, profile.pages.map { it.id })
        displayedPageId = profile.homePageId
        editingPageId = profile.homePageId
        layoutName = name
        if (configureControlArea()) {
            switchToOrientation(activeLayoutOrientation, profile.homePageId)
        } else renderPage(profile.homePageId, safeToDetach = true)
    }

    private fun configureControlArea(): Boolean {
        val changed = controlAreaRoot.configure(
            controllerProfile.safeControlArea, canvas, connectionStatusText,
            listOf(btnConnect, btnCamera, btnAllControls, btnEdit),
            onReleaseAll = { activateReleaseAll() }
        )
        if (controllerProfile.safeControlArea) {
            orientationControlsRow.orientation = LinearLayout.HORIZONTAL
            listOf(btnEditOrientation, btnPlayOrientation).forEach {
                it.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            controlAreaRoot.updateEditorVisibility(
                listOf(btnSave, btnLoad, fabAdd, btnPageManager, orientationControlsRow), GlobalSettings.editMode)
        } else updateOrientationControlsPlacement()
        return changed
    }

    private fun updateProfileSwipePresentation() {
        if (!::switchSwipe.isInitialized) return
        val mode = controllerProfile.swipeMode
        val label = when (mode) {
            ProfileSwipeMode.OFF -> "Off"
            ProfileSwipeMode.BUTTONS -> "Buttons"
            ProfileSwipeMode.CONTROLS -> "Controls"
        }
        switchSwipe.isChecked = mode != ProfileSwipeMode.OFF
        switchSwipe.text = "Swipe: $label ▾"
        switchSwipe.contentDescription = "Swipe mode: $label. Tap to choose for this profile."
    }

    private fun showProfileSwipeOptions() {
        fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val labels = listOf(
            ProfileSwipeMode.OFF to "Keep touch on starting control",
            ProfileSwipeMode.BUTTONS to "Slide between buttons",
            ProfileSwipeMode.CONTROLS to "Slide between controls"
        )
        val explanations = mapOf(
            ProfileSwipeMode.OFF to "Keep the starting control until you lift.",
            ProfileSwipeMode.BUTTONS to "Buttons release when you leave and activate the next enabled button. Sticks and touchpads keep their captured finger.",
            ProfileSwipeMode.CONTROLS to "Move between enabled buttons, sticks and touchpads. Buttons and sticks keep their current output across gaps; sticks use the final finger direction on transfer or lift. Touchpads keep tracking outside until another enabled control is entered. Brief re-entry cooldown prevents rapid accidental switches."
        )
        val dialog = AlertDialog.Builder(this).setTitle("Swipe mode for this profile")
            .setView(ScrollView(this).apply { addView(content) })
            .setNegativeButton("Cancel", null).create()
        labels.forEach { (mode, label) ->
            content.addView(Button(this).apply {
                text = (if (controllerProfile.swipeMode == mode) "✓ " else "") + label
                isAllCaps = false
                setSingleLine(false)
                minHeight = dp(48)
                setOnClickListener {
                    GlobalSettings.swipeMode = mode
                    if (controllerProfile.swipeMode != mode) hasEditsToKeepBeforeStarter = true
                    controllerProfile = controllerProfile.copy(swipeMode = mode)
                    updateProfileSwipePresentation()
                    dialog.dismiss()
                }
            })
            content.addView(TextView(this).apply {
                text = explanations.getValue(mode)
                setPadding(0, 0, 0, dp(12))
            })
        }
        content.addView(TextView(this).apply {
            text = "Saved with this controller profile. Re-center, page navigation, Release All, Scroll, Camera and floating actions always require a direct tap. Button Aim and TouchAim keep their direct gesture."
        })
        dialog.show()
    }

    private fun resetToHomeAfterCleanup() {
        if (!::controllerProfile.isInitialized) return
        syncDisplayedPageIntoProfile()
        pageSession.resetToHome()
        editingPageId = controllerProfile.homePageId
        if (displayedPageId != controllerProfile.homePageId) {
            renderPage(controllerProfile.homePageId, safeToDetach = true)
        } else {
            updatePageSelectorLabel()
        }
    }

    private fun activateControllerPageAction(action: PageAction, targetPageId: String) {
        if (GlobalSettings.editMode) return
        val result = pageSession.perform(
            action = action,
            targetPageId = targetPageId,
            beforeSwitch = {
                syncDisplayedPageIntoProfile()
                releaseOutputs(showFeedback = false)
            },
            showPage = { renderPage(it, safeToDetach = true) }
        )
        result.warning?.let {
            Toast.makeText(this, it, Toast.LENGTH_LONG).show()
            return
        }
        if (result.changed) {
            val name = controllerProfile.pageName(result.toPageId).orEmpty()
            canvas.performHapticFeedback(
                android.view.HapticFeedbackConstants.KEYBOARD_TAP,
                android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            )
            Toast.makeText(this, name, Toast.LENGTH_SHORT).show()
        }
    }

    private fun pageActionDisplayLabel(control: Control): String? {
        if (!control.isLocalPageAction()) return null
        val target = controllerProfile.pageName(control.pageTargetId)
        return when (control.pageAction) {
            PageAction.GO_TO -> target?.let { "Go to $it" } ?: "⚠ Missing page"
            PageAction.TOGGLE -> target?.let { "Toggle $it" } ?: "⚠ Missing page"
            PageAction.RETURN -> "Previous page"
            PageAction.HOME -> "Home page"
            PageAction.NONE -> null
        }
    }

    private fun updatePageSelectorLabel() {
        if (!::btnPageManager.isInitialized) return
        val pageName = controllerProfile.pageName(editingPageId)
            ?: controllerProfile.pageName(displayedPageId)
            ?: "Missing"
        val home = if (editingPageId == controllerProfile.homePageId) " · Home" else ""
        btnPageManager.text = "Editing page: $pageName$home ▾"
    }

    private fun requestEditModeChange(edit: Boolean) {
        if (edit) {
            hasEditsToKeepBeforeStarter = true
            syncDisplayedPageIntoProfile()
            GlobalSettings.editMode = true
            releaseOutputs(showFeedback = false)
            pageSession.resetToHome()
            editingPageId = controllerProfile.homePageId
            renderPage(controllerProfile.homePageId, safeToDetach = true)
            btnEdit.text = "Done"
            updateEditUi(true)
            return
        }

        syncDisplayedPageIntoProfile()
        finishLeavingEditMode()
    }

    private fun finishLeavingEditMode() {
        syncDisplayedPageIntoProfile()
        releaseOutputs(showFeedback = false)
        cancelTouchAimCalibration("Calibration stopped because Play Mode started.")
        pageSession.resetToHome()
        editingPageId = controllerProfile.homePageId
        GlobalSettings.editMode = false
        renderPage(controllerProfile.homePageId, safeToDetach = true)
        btnEdit.text = "Edit"
        updateEditUi(false)
        switchToOrientation(
            orientation = orientationStore.readPlayOrientation(),
            pageId = controllerProfile.homePageId
        )
        prepareFloatingControlsForPlay()
    }

    private fun requestEditorOrientation(orientation: LayoutOrientation) {
        if (!GlobalSettings.editMode || orientation == activeLayoutOrientation) return
        syncDisplayedPageIntoProfile()
        releaseOutputs(showFeedback = false)
        cancelTouchAimCalibration("Calibration stopped because the editor orientation changed.")
        switchToOrientation(orientation, editingPageId.ifBlank { displayedPageId })
    }

    private fun switchToOrientation(orientation: LayoutOrientation, pageId: String) {
        pendingOrientationPageId = pageId
        orientationRenderPending = true
        if (::btnEditOrientation.isInitialized) btnEditOrientation.isEnabled = false
        requestedOrientation = requestedOrientationFor(orientation)
        updateOrientationButtonLabels()

        // Android does not send another configuration callback when the requested orientation is
        // already active. Complete the render after layout in that case.
        if (layoutOrientationFrom(resources.configuration) == orientation) {
            completeOrientationRender(orientation, pageId, waitForNextLayout = false)
            return
        }

        canvas.postDelayed({
            if (orientationRenderPending && pendingOrientationPageId == pageId) {
                val actualOrientation = layoutOrientationFrom(resources.configuration)
                completeOrientationRender(actualOrientation, pageId, waitForNextLayout = false)
                if (actualOrientation != orientation) {
                    Toast.makeText(
                        this,
                        "Android did not allow the requested orientation on this display.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }, 1500L)
    }

    private fun completeOrientationRender(
        orientation: LayoutOrientation,
        pageId: String,
        waitForNextLayout: Boolean
    ) {
        val complete = complete@{
            if (!orientationRenderPending || pendingOrientationPageId != pageId) return@complete
            activeLayoutOrientation = orientation
            renderPage(pageId, safeToDetach = true)
            canvas.requestLayout()
            pendingOrientationPageId = null
            orientationRenderPending = false
            if (::btnEditOrientation.isInitialized) btnEditOrientation.isEnabled = true
            updateOrientationButtonLabels()
            updatePlayerRoleIndicator()
        }

        if (waitForNextLayout || !canvas.isLaidOut || canvas.isLayoutRequested ||
            canvas.width <= 0 || canvas.height <= 0
        ) {
            // doOnNextLayout runs from inside the parent's layout pass. Adding ControlViews there
            // can leave them measured at 0x0 on Samsung devices, so render on the next UI-loop turn.
            canvas.doOnNextLayout { canvas.post { complete() } }
            // Some devices deliver onConfigurationChanged after their only layout pass. Force a
            // new pass, and never allow the controller to remain blank if no callback follows.
            canvas.requestLayout()
            canvas.postDelayed({ complete() }, 300L)
        } else {
            complete()
        }
    }

    private fun updateOrientationButtonLabels() {
        if (::btnEditOrientation.isInitialized) {
            btnEditOrientation.text = when (activeLayoutOrientation.opposite()) {
                LayoutOrientation.PORTRAIT -> "Edit in Portrait"
                LayoutOrientation.LANDSCAPE -> "Edit in Landscape"
            }
        }
        if (::btnPlayOrientation.isInitialized) {
            btnPlayOrientation.text = when (orientationStore.readPlayOrientation()) {
                LayoutOrientation.PORTRAIT -> "Play lock: Portrait"
                LayoutOrientation.LANDSCAPE -> "Play lock: Landscape"
            }
        }
        updateOrientationControlsPlacement()
    }

    private fun updateOrientationControlsPlacement() {
        if (!::orientationControlsRow.isInitialized || controllerProfile.safeControlArea) return
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val rowParams = orientationControlsRow.layoutParams as? FrameLayout.LayoutParams ?: return

        if (activeLayoutOrientation == LayoutOrientation.LANDSCAPE) {
            orientationControlsRow.orientation = LinearLayout.HORIZONTAL
            rowParams.width = minOf(
                dp(410),
                (canvas.width - canvas.paddingLeft - canvas.paddingRight - dp(16))
                    .takeIf { it > 0 } ?: dp(410)
            )
            rowParams.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            rowParams.setMargins(dp(8), dp(60), dp(8), dp(8))
            btnEditOrientation.layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            ).apply { marginEnd = dp(4) }
            btnPlayOrientation.layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            ).apply { marginStart = dp(4) }
        } else {
            orientationControlsRow.orientation = LinearLayout.VERTICAL
            rowParams.width = dp(220)
            rowParams.gravity = Gravity.TOP or Gravity.END
            rowParams.setMargins(dp(16), dp(184), dp(16), dp(8))
            btnEditOrientation.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
            btnPlayOrientation.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }
        orientationControlsRow.layoutParams = rowParams
        if (orientationControlsRow.visibility == View.VISIBLE) {
            orientationControlsRow.bringToFront()
        }
    }

    private fun requestedOrientationFor(orientation: LayoutOrientation): Int = when (orientation) {
        LayoutOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        LayoutOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }

    private fun layoutOrientationFrom(configuration: Configuration): LayoutOrientation =
        if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            LayoutOrientation.LANDSCAPE
        } else {
            LayoutOrientation.PORTRAIT
        }

    private fun showControllerPageManager() {
        if (!GlobalSettings.editMode) return
        syncDisplayedPageIntoProfile()
        var dialog: AlertDialog? = null
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
        }
        content.addView(TextView(this).apply {
            text = "Select a page to edit"
            textSize = 18f
            setPadding(8, 8, 8, 8)
        })
        controllerProfile.pages.forEach { page ->
            content.addView(pageManagerButton(
                "${if (page.id == editingPageId) "✓ " else ""}${page.name}" +
                    if (page.id == controllerProfile.homePageId) "  (Home)" else ""
            ) {
                dialog?.dismiss()
                selectEditingPage(page.id)
            })
        }
        content.addView(pageManagerButton("Add blank page") {
            dialog?.dismiss()
            promptCreateBlankPage()
        })
        content.addView(pageManagerButton("Duplicate current page") {
            dialog?.dismiss()
            promptDuplicateCurrentPage()
        })
        content.addView(pageManagerButton("Import saved profile as page") {
            dialog?.dismiss()
            showImportProfilePicker()
        })
        content.addView(pageManagerButton("Rename current page") {
            dialog?.dismiss()
            promptRenameCurrentPage()
        })
        content.addView(pageManagerButton("Delete current page") {
            dialog?.dismiss()
            confirmDeleteCurrentPage()
        })
        content.addView(pageManagerButton("Set current page as Home") {
            dialog?.dismiss()
            setCurrentPageAsHome()
        }.apply { isEnabled = editingPageId != controllerProfile.homePageId })

        dialog = AlertDialog.Builder(this)
            .setTitle("Controller Pages")
            .setView(ScrollView(this).apply { addView(content) })
            .setNegativeButton("Close", null)
            .create()
        dialog.show()
    }

    private fun pageManagerButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        minHeight = (56 * resources.displayMetrics.density).toInt()
        setOnClickListener { action() }
    }

    private fun selectEditingPage(pageId: String) {
        if (!GlobalSettings.editMode || controllerProfile.page(pageId) == null) return
        syncDisplayedPageIntoProfile()
        editingPageId = pageId
        renderPage(pageId, safeToDetach = true)
    }

    private fun promptCreateBlankPage() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 8, 32, 0)
        }
        val name = EditText(this).apply { hint = "Page name" }
        val copyNavigation = CheckBox(this).apply {
            text = "Copy page-navigation buttons from the current page"
            isChecked = true
        }
        container.addView(name)
        container.addView(copyNavigation)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Add blank page")
            .setMessage("Copied navigation buttons keep their current positions; move them in Edit Mode if needed.")
            .setView(container)
            .setPositiveButton("Add", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val pageName = name.text.toString().trim()
                if (!validNewPageName(pageName)) return@setOnClickListener
                syncDisplayedPageIntoProfile()
                val id = newPageId()
                val source = controllerProfile.page(editingPageId) ?: return@setOnClickListener
                val page = if (copyNavigation.isChecked) {
                    detachedPageCopy(
                        source = source,
                        destinationPageId = id,
                        newName = pageName,
                        include = Control::isLocalPageAction,
                        redirectSelfTargets = false,
                        includeFloatingZones = false
                    )
                } else ControllerPage(id, pageName, emptyList())
                addNewPage(page)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun promptDuplicateCurrentPage() {
        val source = controllerProfile.page(editingPageId) ?: return
        val input = EditText(this).apply { setText(uniquePageName("${source.name} Copy")) }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Duplicate current page")
            .setMessage("The duplicate is an independent deep copy. Later edits will not affect the source page.")
            .setView(input)
            .setPositiveButton("Duplicate", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (!validNewPageName(name)) return@setOnClickListener
                syncDisplayedPageIntoProfile()
                val current = controllerProfile.page(editingPageId) ?: return@setOnClickListener
                val newId = newPageId()
                addNewPage(
                    detachedPageCopy(
                        source = current,
                        destinationPageId = newId,
                        newName = name,
                        redirectSelfTargets = true
                    )
                )
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showImportProfilePicker() {
        val names = listLayouts(this).sorted()
        if (names.isEmpty()) {
            Toast.makeText(this, "No saved profiles are available to import.", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Import from saved profile")
            .setItems(names.toTypedArray()) { _, index ->
                val sourceName = names[index]
                val stored = readControllerProfile(this, sourceName)
                val result = (stored as? StoredControllerProfileResult.Loaded)?.result
                if (result == null) {
                    val message = if (stored is StoredControllerProfileResult.NotFound) {
                        "'$sourceName' no longer exists."
                    } else "Could not read '$sourceName'; its file was not changed."
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                } else if (result.profile.pages.size == 1) {
                    promptImportPage(result.profile.pages.first(), result.warnings)
                } else {
                    val pages = result.profile.pages
                    AlertDialog.Builder(this)
                        .setTitle("Choose page from $sourceName")
                        .setItems(pages.map { it.name }.toTypedArray()) { _, pageIndex ->
                            promptImportPage(pages[pageIndex], result.warnings)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptImportPage(source: ControllerPage, warnings: List<String>) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 8, 32, 0)
        }
        val name = EditText(this).apply { setText(uniquePageName(source.name)) }
        val copyNavigation = CheckBox(this).apply {
            text = "Also copy page-navigation buttons from the current page"
            isChecked = true
        }
        container.addView(name)
        container.addView(copyNavigation)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Import '${source.name}'")
            .setMessage(
                buildString {
                    append("The imported page is an independent copy and keeps no link to its source file. ")
                    append("Copied navigation buttons keep their positions and may overlap imported controls; review them in Edit Mode.")
                    if (warnings.isNotEmpty()) {
                        append("\n\nSource warning:\n")
                        append(warnings.take(3).joinToString("\n"))
                    }
                }
            )
            .setView(container)
            .setPositiveButton("Import", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val pageName = name.text.toString().trim()
                if (!validNewPageName(pageName)) return@setOnClickListener
                syncDisplayedPageIntoProfile()
                val newId = newPageId()
                var importedPage = detachedPageCopy(
                    source = source,
                    destinationPageId = newId,
                    newName = pageName,
                    redirectSelfTargets = true
                )
                if (copyNavigation.isChecked) {
                    val current = controllerProfile.page(editingPageId)
                        ?: return@setOnClickListener
                    val navigationPage = detachedPageCopy(
                        source = current,
                        destinationPageId = newId,
                        newName = pageName,
                        include = Control::isLocalPageAction,
                        redirectSelfTargets = false,
                        includeFloatingZones = false
                    )
                    val width = canvas.width.toFloat()
                    val height = canvas.height.toFloat()
                    val importedControls = importedPage.controlsForOrientation(
                        activeLayoutOrientation, width, height
                    )
                    val navigationControls = navigationPage.controlsForOrientation(
                        activeLayoutOrientation, width, height
                    )
                    val combined = importedControls + navigationControls
                    importedPage = importedPage.copy(controls = combined).withGeometry(
                        activeLayoutOrientation,
                        capturePageGeometry(combined, width, height)
                    )
                }
                addNewPage(importedPage)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun addNewPage(page: ControllerPage) {
        syncDisplayedPageIntoProfile()
        controllerProfile = controllerProfile.copy(pages = controllerProfile.pages + page)
        pageSession.updateProfile(controllerProfile.homePageId, controllerProfile.pages.map { it.id })
        editingPageId = page.id
        renderPage(page.id, safeToDetach = true)
    }

    private fun detachedPageCopy(
        source: ControllerPage,
        destinationPageId: String,
        newName: String,
        include: (Control) -> Boolean = { true },
        redirectSelfTargets: Boolean,
        includeFloatingZones: Boolean = true
    ): ControllerPage {
        return detachedControllerPageCopy(
            source, destinationPageId, newName, include, redirectSelfTargets,
            includeFloatingZones = includeFloatingZones
        )
    }

    private fun promptRenameCurrentPage() {
        val page = controllerProfile.page(editingPageId) ?: return
        val input = EditText(this).apply { setText(page.name) }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Rename page")
            .setView(input)
            .setPositiveButton("Rename", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isBlank() || controllerProfile.hasPageName(name, exceptPageId = page.id)) {
                    Toast.makeText(this, "Page names must be unique.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                syncDisplayedPageIntoProfile()
                controllerProfile = controllerProfile.copy(
                    pages = controllerProfile.pages.map {
                        if (it.id == page.id) it.copy(name = name) else it
                    }
                )
                updatePageSelectorLabel()
                canvas.children.filterIsInstance<ControlView>()
                    .forEach(ControlView::refreshPageActionLabel)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun confirmDeleteCurrentPage() {
        syncDisplayedPageIntoProfile()
        val page = controllerProfile.page(editingPageId) ?: return
        when {
            controllerProfile.pages.size == 1 -> {
                Toast.makeText(this, "The only page cannot be deleted.", Toast.LENGTH_LONG).show()
                return
            }
            page.id == controllerProfile.homePageId -> {
                Toast.makeText(this, "Set another page as Home before deleting this one.", Toast.LENGTH_LONG).show()
                return
            }
        }
        val references = controllerProfile.referenceCount(page.id, excludingPageId = page.id)
        AlertDialog.Builder(this)
            .setTitle("Delete '${page.name}'?")
            .setMessage(
                "$references control${if (references == 1) "" else "s"} reference this page. " +
                    "Those controls will be visibly marked as missing. This cannot be undone."
            )
            .setPositiveButton("Delete") { _, _ ->
                syncDisplayedPageIntoProfile()
                controllerProfile = controllerProfile.copy(
                    pages = controllerProfile.pages.filterNot { it.id == page.id }
                )
                pageSession.updateProfile(
                    controllerProfile.homePageId,
                    controllerProfile.pages.map { it.id }
                )
                editingPageId = controllerProfile.homePageId
                renderPage(controllerProfile.homePageId, safeToDetach = true)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setCurrentPageAsHome() {
        val page = controllerProfile.page(editingPageId) ?: return
        syncDisplayedPageIntoProfile()
        controllerProfile = controllerProfile.copy(homePageId = page.id)
        pageSession.updateProfile(page.id, controllerProfile.pages.map { it.id })
        Toast.makeText(this, "${page.name} is now Home", Toast.LENGTH_SHORT).show()
        updatePageSelectorLabel()
    }

    private fun validNewPageName(name: String): Boolean {
        val valid = name.isNotBlank() && !controllerProfile.hasPageName(name)
        if (!valid) Toast.makeText(this, "Page names must be nonblank and unique.", Toast.LENGTH_LONG).show()
        return valid
    }

    private fun uniquePageName(baseName: String): String {
        val base = baseName.trim().ifBlank { "Page" }
        if (!controllerProfile.hasPageName(base)) return base
        var suffix = 2
        while (controllerProfile.hasPageName("$base $suffix")) suffix++
        return "$base $suffix"
    }

    /** Public so an assignable RELEASE_ALL ControlView uses the same safety path. */
    fun activateReleaseAll(showFeedback: Boolean = true) {
        syncDisplayedPageIntoProfile()
        releaseOutputs(showFeedback)
    }

    private fun releaseOutputs(showFeedback: Boolean) {
        cancelFloatingSelection()
        floatingControls.clear(removeEditorViews = false)
        if (showFeedback) triggerReleaseAllHaptic()
        val practice = NetworkClient.outputMode == NetworkClient.OutputMode.PRACTICE
        ReleaseAllCoordinator.releaseAll { status ->
            if (!showFeedback || (practice && status != ReleaseAllCoordinator.ReceiverStatus.PENDING)) return@releaseAll
            runOnUiThread {
                val message = if (practice) "All Practice controls released"
                else if (NetworkClient.bridgeConnectionRequested) "All controls released; confirming neutral before resuming"
                else when (status) {
                    ReleaseAllCoordinator.ReceiverStatus.PENDING ->
                        "Android outputs cleared; awaiting receiver confirmation"
                    ReleaseAllCoordinator.ReceiverStatus.CONFIRMED ->
                        "Receiver confirmed: all outputs released"
                    ReleaseAllCoordinator.ReceiverStatus.UNAVAILABLE ->
                        "Android outputs cleared — receiver confirmation unavailable"
                    ReleaseAllCoordinator.ReceiverStatus.CONSOLEBRIDGE_UNAVAILABLE ->
                        "Android outputs cleared — Pico release is not implemented yet"
                }
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun startTouchAimCalibration(
        controlId: String,
        existingProfile: TouchAimCalibrationProfile? = null
    ) {
        cancelTouchAimCalibration()
        val launchToken = ++touchAimCalibrationLaunchGeneration
        val view = canvas.children.filterIsInstance<ControlView>()
            .firstOrNull { it.model.id == controlId }
        if (view == null || view.model.type != ControlType.TOUCH_AIM) {
            Toast.makeText(this, "TouchAim control is unavailable.", Toast.LENGTH_LONG).show()
            return
        }
        val launch = launch@{
            if (launchToken != touchAimCalibrationLaunchGeneration || view.parent == null) return@launch
            val wizard = TouchAimCalibrationWizard(this, view, view.model, existingProfile) { applied ->
                activeTouchAimCalibration = null
                if (applied) {
                    syncDisplayedPageIntoProfile()
                    if (!profileAutosaveBlocked) {
                        saveControllerProfile(this, layoutName, controllerProfile)
                    }
                }
            }
            activeTouchAimCalibration = wizard
            wizard.show()
        }
        if (view.isLayoutRequested) view.doOnNextLayout { launch() } else view.post(launch)
    }

    fun cancelTouchAimCalibration(reason: String? = null) {
        touchAimCalibrationLaunchGeneration++
        val wizard = activeTouchAimCalibration ?: return
        activeTouchAimCalibration = null
        wizard.cancel(reason)
    }

    private fun triggerReleaseAllHaptic() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (!vibrator.hasVibrator()) return
        val pattern = longArrayOf(0L, 35L, 45L, 90L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }

    /**
     * Creates a standard Xbox controller layout
     */
    private fun createXboxControllerLayout() {
        // First clear existing controls
        controls.clear()
        clearControlViewsSafely()

        // Screen dimensions for positioning
        val screenWidth = canvas.width.toFloat()
        val screenHeight = canvas.height.toFloat()

        // Define standard sizes
        val buttonSize = 120f
        val stickSize = 200f
        val shoulderSize = 100f
        val dpadSize = 160f

        // Function to create and add a control
        fun addControl(
            id: String,
            type: ControlType,
            x: Float,
            y: Float,
            w: Float,
            h: Float,
            payload: String,
            name: String = ""
        ): Control {
            val control = Control(
                id = id,
                type = type,
                x = x,
                y = y,
                w = w,
                h = h,
                payload = payload,
                name = name
            )
            controls.add(control)
            canvas.addView(layoutManager.createControlView(control))
            return control
        }

        // Left stick (positioned on the left side)
        addControl(
            "left_stick",
            ControlType.STICK,
            x = screenWidth * 0.2f - stickSize / 2,
            y = screenHeight * 0.6f - stickSize / 2,
            w = stickSize,
            h = stickSize,
            payload = "STICK_L",
            name = "Left Stick"
        )

        // Right stick (positioned on the right side)
        addControl(
            "right_stick",
            ControlType.STICK,
            x = screenWidth * 0.75f - stickSize / 2,
            y = screenHeight * 0.6f - stickSize / 2,
            w = stickSize,
            h = stickSize,
            payload = "STICK_R",
            name = "Right Stick"
        )

        // Face buttons (ABXY) positioned on the right
        // A Button (bottom)
        addControl(
            "a_button",
            ControlType.BUTTON,
            x = screenWidth * 0.8f - buttonSize / 2,
            y = screenHeight * 0.75f - buttonSize / 2,
            w = buttonSize,
            h = buttonSize,
            payload = "X360A",
            name = "A"
        )

        // B Button (right)
        addControl(
            "b_button",
            ControlType.BUTTON,
            x = screenWidth * 0.9f - buttonSize / 2,
            y = screenHeight * 0.65f - buttonSize / 2,
            w = buttonSize,
            h = buttonSize,
            payload = "X360B",
            name = "B"
        )

        // X Button (left)
        addControl(
            "x_button",
            ControlType.BUTTON,
            x = screenWidth * 0.7f - buttonSize / 2,
            y = screenHeight * 0.65f - buttonSize / 2,
            w = buttonSize,
            h = buttonSize,
            payload = "X360X",
            name = "X"
        )

        // Y Button (top)
        addControl(
            "y_button",
            ControlType.BUTTON,
            x = screenWidth * 0.8f - buttonSize / 2,
            y = screenHeight * 0.55f - buttonSize / 2,
            w = buttonSize,
            h = buttonSize,
            payload = "X360Y",
            name = "Y"
        )

        // D-Pad (positioned on the left)
        // D-Pad Up
        addControl(
            "dpad_up",
            ControlType.BUTTON,
            x = screenWidth * 0.2f - buttonSize / 2,
            y = screenHeight * 0.35f - buttonSize / 2,
            w = buttonSize,
            h = buttonSize,
            payload = "X360UP",
            name = "↑"
        )

        // D-Pad Down
        addControl(
            "dpad_down",
            ControlType.BUTTON,
            x = screenWidth * 0.2f - buttonSize / 2,
            y = screenHeight * 0.5f - buttonSize / 2,
            w = buttonSize,
            h = buttonSize,
            payload = "X360DOWN",
            name = "↓"
        )

        // D-Pad Left
        addControl(
            "dpad_left",
            ControlType.BUTTON,
            x = screenWidth * 0.1f - buttonSize / 2,
            y = screenHeight * 0.425f - buttonSize / 2,
            w = buttonSize,
            h = buttonSize,
            payload = "X360LEFT",
            name = "←"
        )

        // D-Pad Right
        addControl(
            "dpad_right",
            ControlType.BUTTON,
            x = screenWidth * 0.3f - buttonSize / 2,
            y = screenHeight * 0.425f - buttonSize / 2,
            w = buttonSize,
            h = buttonSize,
            payload = "X360RIGHT",
            name = "→"
        )

        // Shoulder Buttons (positioned at the top)
        // Left Shoulder (LB)
        addControl(
            "lb_button",
            ControlType.BUTTON,
            x = screenWidth * 0.2f - shoulderSize / 2,
            y = screenHeight * 0.15f - shoulderSize / 2,
            w = shoulderSize,
            h = shoulderSize,
            payload = "X360LB",
            name = "LB"
        )

        // Right Shoulder (RB)
        addControl(
            "rb_button",
            ControlType.BUTTON,
            x = screenWidth * 0.8f - shoulderSize / 2,
            y = screenHeight * 0.15f - shoulderSize / 2,
            w = shoulderSize,
            h = shoulderSize,
            payload = "X360RB",
            name = "RB"
        )

        // Triggers (positioned at the top)
        // Left Trigger (LT) - using button for now, could make special trigger control later
        addControl(
            "lt_button",
            ControlType.BUTTON,
            x = screenWidth * 0.2f - shoulderSize / 2,
            y = screenHeight * 0.05f - shoulderSize / 2,
            w = shoulderSize,
            h = shoulderSize,
            payload = "LT:1.0", // Fully pressed trigger
            name = "LT"
        )

        // Right Trigger (RT)
        addControl(
            "rt_button",
            ControlType.BUTTON,
            x = screenWidth * 0.8f - shoulderSize / 2,
            y = screenHeight * 0.05f - shoulderSize / 2,
            w = shoulderSize,
            h = shoulderSize,
            payload = "RT:1.0",
            name = "RT"
        )

        // Center buttons (Start/Back/Guide)
        // Start button
        addControl(
            "start_button",
            ControlType.BUTTON,
            x = screenWidth * 0.6f - buttonSize / 2,
            y = screenHeight * 0.3f - buttonSize / 2,
            w = buttonSize,
            h = buttonSize * 0.6f,
            payload = "X360START",
            name = "Start"
        )

        // Back button
        addControl(
            "back_button",
            ControlType.BUTTON,
            x = screenWidth * 0.4f - buttonSize / 2,
            y = screenHeight * 0.3f - buttonSize / 2,
            w = buttonSize,
            h = buttonSize * 0.6f,
            payload = "X360BACK",
            name = "Back"
        )

        // Stick clickable buttons
        // Left stick button (press)
        addControl(
            "ls_button",
            ControlType.BUTTON,
            x = screenWidth * 0.2f - buttonSize / 3,
            y = screenHeight * 0.7f - buttonSize / 3,
            w = buttonSize * 0.6f,
            h = buttonSize * 0.6f,
            payload = "X360LS",
            name = "LS"
        )

        // Right stick button (press)
        addControl(
            "rs_button",
            ControlType.BUTTON,
            x = screenWidth * 0.75f - buttonSize / 3,
            y = screenHeight * 0.7f - buttonSize / 3,
            w = buttonSize * 0.6f,
            h = buttonSize * 0.6f,
            payload = "X360RS",
            name = "RS"
        )

        // Save the layout
        finalizeBuiltInPage()

        Toast.makeText(this, "Current page replaced with Xbox controls", Toast.LENGTH_SHORT).show()
    }

    /**
     * Creates a Player 1 Xbox controller layout
     */
    private fun createPlayer1XboxLayout() {
        // First clear existing controls
        controls.clear()
        clearControlViewsSafely()

        // Screen dimensions for positioning
        val screenWidth = canvas.width.toFloat()
        val screenHeight = canvas.height.toFloat()

        // Define standard sizes
        val buttonSize = 120f
        val stickSize = 200f
        val shoulderSize = 100f
        val dpadSize = 160f

        // Function to create and add a control
        fun addControl(
            id: String,
            type: ControlType,
            x: Float,
            y: Float,
            w: Float,
            h: Float,
            payload: String,
            name: String = ""
        ): Control {
            val control = Control(
                id = id,
                type = type,
                x = x,
                y = y,
                w = w,
                h = h,
                payload = payload,
                name = name
            )
            controls.add(control)
            canvas.addView(layoutManager.createControlView(control))
            return control
        }

        // Left stick (positioned on the left side)
        addControl(
            "p1_left_stick",
            ControlType.STICK,
            x = screenWidth * 0.2f - stickSize / 2,
            y = screenHeight * 0.6f - stickSize / 2,
            w = stickSize,
            h = stickSize,
            payload = "STICK_L",
            name = "P1 Left Stick"
        )

        // Right stick (positioned on the right side)
        addControl(
            "p1_right_stick",
            ControlType.STICK,
            x = screenWidth * 0.75f - stickSize / 2,
            y = screenHeight * 0.6f - stickSize / 2,
            w = stickSize,
            h = stickSize,
            payload = "STICK_R",
            name = "P1 Right Stick"
        )

        // Face buttons (ABXY) positioned on the right
        // A Button (bottom)
        addControl(
            "p1_a_button",
            ControlType.BUTTON,
            x = screenWidth * 0.8f - buttonSize / 2,
            y = screenHeight * 0.75f - buttonSize / 2,
            w = buttonSize,
            h = buttonSize,
            payload = "X360A",
            name = "P1 A"
        )

        // B Button (right)
        addControl(
            "p1_b_button",
            ControlType.BUTTON,
            x = screenWidth * 0.9f - buttonSize / 2,
            y = screenHeight * 0.65f - buttonSize / 2,
            w = buttonSize,
            h = buttonSize,
            payload = "X360B",
            name = "P1 B"
        )

        // X Button (left)
        addControl(
            "p1_x_button",
            ControlType.BUTTON,
            x = screenWidth * 0.7f - buttonSize / 2,
            y = screenHeight * 0.65f - buttonSize / 2,
            w = buttonSize,
            h = buttonSize,
            payload = "X360X",
            name = "P1 X"
        )

        // Y Button (top)
        addControl(
            "p1_y_button",
            ControlType.BUTTON,
            x = screenWidth * 0.8f - buttonSize / 2,
            y = screenHeight * 0.55f - buttonSize / 2,
            w = buttonSize,
            h = buttonSize,
            payload = "X360Y",
            name = "P1 Y"
        )

        // D-Pad (positioned on the left)
        // D-Pad Up
        addControl(
            "p1_dpad_up",
            ControlType.BUTTON,
            x = screenWidth * 0.2f - buttonSize / 2,
            y = screenHeight * 0.35f - buttonSize / 2,
            w = buttonSize,
            h = buttonSize,
            payload = "X360UP",
            name = "P1 ↑"
        )

        // D-Pad Down
        addControl(
            "p1_dpad_down",
            ControlType.BUTTON,
            x = screenWidth * 0.2f - buttonSize / 2,
            y = screenHeight * 0.5f - buttonSize / 2,
            w = buttonSize,
            h = buttonSize,
            payload = "X360DOWN",
            name = "P1 ↓"
        )

        // D-Pad Left
        addControl(
            "p1_dpad_left",
            ControlType.BUTTON,
            x = screenWidth * 0.1f - buttonSize / 2,
            y = screenHeight * 0.425f - buttonSize / 2,
            w = buttonSize,
            h = buttonSize,
            payload = "X360LEFT",
            name = "P1 ←"
        )

        // D-Pad Right
        addControl(
            "p1_dpad_right",
            ControlType.BUTTON,
            x = screenWidth * 0.3f - buttonSize / 2,
            y = screenHeight * 0.425f - buttonSize / 2,
            w = buttonSize,
            h = buttonSize,
            payload = "X360RIGHT",
            name = "P1 →"
        )

        // Shoulder Buttons (positioned at the top)
        // Left Shoulder (LB)
        addControl(
            "p1_lb_button",
            ControlType.BUTTON,
            x = screenWidth * 0.2f - shoulderSize / 2,
            y = screenHeight * 0.15f - shoulderSize / 2,
            w = shoulderSize,
            h = shoulderSize,
            payload = "X360LB",
            name = "P1 LB"
        )

        // Right Shoulder (RB)
        addControl(
            "p1_rb_button",
            ControlType.BUTTON,
            x = screenWidth * 0.8f - shoulderSize / 2,
            y = screenHeight * 0.15f - shoulderSize / 2,
            w = shoulderSize,
            h = shoulderSize,
            payload = "X360RB",
            name = "P1 RB"
        )

        // Triggers (positioned at the top)
        // Left Trigger (LT) - using button for now, could make special trigger control later
        addControl(
            "p1_lt_button",
            ControlType.BUTTON,
            x = screenWidth * 0.2f - shoulderSize / 2,
            y = screenHeight * 0.05f - shoulderSize / 2,
            w = shoulderSize,
            h = shoulderSize,
            payload = "LT:1.0", // Fully pressed trigger
            name = "P1 LT"
        )

        // Right Trigger (RT)
        addControl(
            "p1_rt_button",
            ControlType.BUTTON,
            x = screenWidth * 0.8f - shoulderSize / 2,
            y = screenHeight * 0.05f - shoulderSize / 2,
            w = shoulderSize,
            h = shoulderSize,
            payload = "RT:1.0",
            name = "P1 RT"
        )

        // Center buttons (Start/Back/Guide)
        // Start button
        addControl(
            "p1_start_button",
            ControlType.BUTTON,
            x = screenWidth * 0.6f - buttonSize / 2,
            y = screenHeight * 0.3f - buttonSize / 2,
            w = buttonSize,
            h = buttonSize * 0.6f,
            payload = "X360START",
            name = "P1 Start"
        )

        // Back button
        addControl(
            "p1_back_button",
            ControlType.BUTTON,
            x = screenWidth * 0.4f - buttonSize / 2,
            y = screenHeight * 0.3f - buttonSize / 2,
            w = buttonSize,
            h = buttonSize * 0.6f,
            payload = "X360BACK",
            name = "P1 Back"
        )

        // Stick clickable buttons
        // Left stick button (press)
        addControl(
            "p1_ls_button",
            ControlType.BUTTON,
            x = screenWidth * 0.2f - buttonSize / 3,
            y = screenHeight * 0.7f - buttonSize / 3,
            w = buttonSize * 0.6f,
            h = buttonSize * 0.6f,
            payload = "X360LS",
            name = "P1 LS"
        )

        // Right stick button (press)
        addControl(
            "p1_rs_button",
            ControlType.BUTTON,
            x = screenWidth * 0.75f - buttonSize / 3,
            y = screenHeight * 0.7f - buttonSize / 3,
            w = buttonSize * 0.6f,
            h = buttonSize * 0.6f,
            payload = "X360RS",
            name = "P1 RS"
        )

        // Save the layout
        finalizeBuiltInPage()

        Toast.makeText(this, "Current page replaced with Player 1 controls", Toast.LENGTH_SHORT).show()
    }


    /**
     * Creates a Player 2 Xbox controller layout
     */
    private fun createPlayer2XboxLayout() {
        // First clear existing controls
        controls.clear()
        clearControlViewsSafely()

        // Screen dimensions for positioning
        val screenWidth = canvas.width.toFloat()
        val screenHeight = canvas.height.toFloat()

        // Define standard sizes
        val buttonSize = 120f
        val stickSize = 200f
        val shoulderSize = 100f
        val dpadSize = 160f

        // Function to create and add a control
        fun addControl(
            id: String,
            type: ControlType,
            x: Float,
            y: Float,
            w: Float,
            h: Float,
            payload: String,
            name: String = ""
        ): Control {
            val control = Control(
                id = id,
                type = type,
                x = x,
                y = y,
                w = w,
                h = h,
                payload = payload,
                name = name
            )
            controls.add(control)
            canvas.addView(layoutManager.createControlView(control))
            return control
        }

        // Left stick (positioned on the left side)
        addControl(
            "p2_left_stick",
            ControlType.STICK,
            x = screenWidth * 0.2f - stickSize / 2,
            y = screenHeight * 0.6f - stickSize / 2,
            w = stickSize,
            h = stickSize,
            payload = "STICK_L",
            name = "P2 Left Stick"
        )

        // Right stick (positioned on the right side)
        addControl(
            "p2_right_stick",
            ControlType.STICK,
            x = screenWidth * 0.75f - stickSize / 2,
            y = screenHeight * 0.6f - stickSize / 2,
            w = stickSize,
            h = stickSize,
            payload = "STICK_R",
            name = "P2 Right Stick"
        )

        // Face buttons (ABXY) positioned on the right
        // A Button (bottom)
        addControl(
            "p2_a_button",
            ControlType.BUTTON,
            x = screenWidth * 0.8f - buttonSize / 2,
            y = screenHeight * 0.75f - buttonSize / 2,
            w = buttonSize,
            h = buttonSize,
            payload = "X360A",
            name = "P2 A"
        )

        // B Button (right)
        addControl(
            "p2_b_button",
            ControlType.BUTTON,
            x = screenWidth * 0.9f - buttonSize / 2,
            y = screenHeight * 0.65f - buttonSize / 2,
            w = buttonSize,
            h = buttonSize,
            payload = "X360B",
            name = "P2 B"
        )

        // X Button (left)
        addControl(
            "p2_x_button",
            ControlType.BUTTON,
            x = screenWidth * 0.7f - buttonSize / 2,
            y = screenHeight * 0.65f - buttonSize / 2,
            w = buttonSize,
            h = buttonSize,
            payload = "X360X",
            name = "P2 X"
        )

        // Y Button (top)
        addControl(
            "p2_y_button",
            ControlType.BUTTON,
            x = screenWidth * 0.8f - buttonSize / 2,
            y = screenHeight * 0.55f - buttonSize / 2,
            w = buttonSize,
            h = buttonSize,
            payload = "X360Y",
            name = "P2 Y"
        )

        // D-Pad (positioned on the left)
        // D-Pad Up
        addControl(
            "p2_dpad_up",
            ControlType.BUTTON,
            x = screenWidth * 0.2f - buttonSize / 2,
            y = screenHeight * 0.35f - buttonSize / 2,
            w = buttonSize,
            h = buttonSize,
            payload = "X360UP",
            name = "P2 ↑"
        )

        // D-Pad Down
        addControl(
            "p2_dpad_down",
            ControlType.BUTTON,
            x = screenWidth * 0.2f - buttonSize / 2,
            y = screenHeight * 0.5f - buttonSize / 2,
            w = buttonSize,
            h = buttonSize,
            payload = "X360DOWN",
            name = "P2 ↓"
        )

        // D-Pad Left
        addControl(
            "p2_dpad_left",
            ControlType.BUTTON,
            x = screenWidth * 0.1f - buttonSize / 2,
            y = screenHeight * 0.425f - buttonSize / 2,
            w = buttonSize,
            h = buttonSize,
            payload = "X360LEFT",
            name = "P2 ←"
        )

        // D-Pad Right
        addControl(
            "p2_dpad_right",
            ControlType.BUTTON,
            x = screenWidth * 0.3f - buttonSize / 2,
            y = screenHeight * 0.425f - buttonSize / 2,
            w = buttonSize,
            h = buttonSize,
            payload = "X360RIGHT",
            name = "P2 →"
        )

        // Shoulder Buttons (positioned at the top)
        // Left Shoulder (LB)
        addControl(
            "p2_lb_button",
            ControlType.BUTTON,
            x = screenWidth * 0.2f - shoulderSize / 2,
            y = screenHeight * 0.15f - shoulderSize / 2,
            w = shoulderSize,
            h = shoulderSize,
            payload = "X360LB",
            name = "P2 LB"
        )

        // Right Shoulder (RB)
        addControl(
            "p2_rb_button",
            ControlType.BUTTON,
            x = screenWidth * 0.8f - shoulderSize / 2,
            y = screenHeight * 0.15f - shoulderSize / 2,
            w = shoulderSize,
            h = shoulderSize,
            payload = "X360RB",
            name = "P2 RB"
        )

        // Triggers (positioned at the top)
        // Left Trigger (LT) - using button for now, could make special trigger control later
        addControl(
            "p2_lt_button",
            ControlType.BUTTON,
            x = screenWidth * 0.2f - shoulderSize / 2,
            y = screenHeight * 0.05f - shoulderSize / 2,
            w = shoulderSize,
            h = shoulderSize,
            payload = "LT:1.0", // Fully pressed trigger
            name = "P2 LT"
        )

        // Right Trigger (RT)
        addControl(
            "p2_rt_button",
            ControlType.BUTTON,
            x = screenWidth * 0.8f - shoulderSize / 2,
            y = screenHeight * 0.05f - shoulderSize / 2,
            w = shoulderSize,
            h = shoulderSize,
            payload = "RT:1.0",
            name = "P2 RT"
        )

        // Center buttons (Start/Back/Guide)
        // Start button
        addControl(
            "p2_start_button",
            ControlType.BUTTON,
            x = screenWidth * 0.6f - buttonSize / 2,
            y = screenHeight * 0.3f - buttonSize / 2,
            w = buttonSize,
            h = buttonSize * 0.6f,
            payload = "X360START",
            name = "P2 Start"
        )

        // Back button
        addControl(
            "p2_back_button",
            ControlType.BUTTON,
            x = screenWidth * 0.4f - buttonSize / 2,
            y = screenHeight * 0.3f - buttonSize / 2,
            w = buttonSize,
            h = buttonSize * 0.6f,
            payload = "X360BACK",
            name = "P2 Back"
        )

        // Stick clickable buttons
        // Left stick button (press)
        addControl(
            "p2_ls_button",
            ControlType.BUTTON,
            x = screenWidth * 0.2f - buttonSize / 3,
            y = screenHeight * 0.7f - buttonSize / 3,
            w = buttonSize * 0.6f,
            h = buttonSize * 0.6f,
            payload = "X360LS",
            name = "P2 LS"
        )

        // Right stick button (press)
        addControl(
            "p2_rs_button",
            ControlType.BUTTON,
            x = screenWidth * 0.75f - buttonSize / 3,
            y = screenHeight * 0.7f - buttonSize / 3,
            w = buttonSize * 0.6f,
            h = buttonSize * 0.6f,
            payload = "X360RS",
            name = "P2 RS"
        )

        // Save the layout
        finalizeBuiltInPage()

        Toast.makeText(this, "Current page replaced with Player 2 controls", Toast.LENGTH_SHORT).show()
    }

    private fun finalizeBuiltInPage() {
        syncDisplayedPageIntoProfile()
        updatePageSelectorLabel()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        cancelTouchAimCalibration("Calibration stopped because the screen configuration changed.")
        val newOrientation = layoutOrientationFrom(newConfig)
        if (newOrientation == activeLayoutOrientation && pendingOrientationPageId == null) return

        // Explicit editor/Play transitions release before requesting orientation. Keep this
        // defensive cleanup for any configuration transition Android delivers independently.
        releaseOutputs(showFeedback = false)
        val pageId = pendingOrientationPageId ?: displayedPageId
        orientationRenderPending = true
        pendingOrientationPageId = pageId
        completeOrientationRender(newOrientation, pageId, waitForNextLayout = true)
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean) {
        super.onMultiWindowModeChanged(isInMultiWindowMode)
        cancelTouchAimCalibration("Calibration stopped because the window size changed.")
        // Do nothing - keep controls exactly as they are
    }

    private fun refreshLayout() {
        // Clear existing views first to prevent duplicates
        clearControlViews()
        
        // Re-spawn all control views with current canvas dimensions
        layoutManager.spawnControlViews()
    }

    private fun setupWindowInsetsHandling() {
        // Handle window insets for split screen mode
        ViewCompat.setOnApplyWindowInsetsListener(controlAreaRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            controlAreaRoot.applySystemInsets(bars.left, bars.top, bars.right,
                bars.bottom + ime.bottom)
            insets
        }
        
        // Monitor canvas size changes - DISABLED to allow manual resizing
        // User can manually adjust controls after entering split screen mode
        canvas.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val currentHeight = canvas.height
                
                // Only store initial dimensions, don't auto-resize on changes
                if (!isLayoutInitialized && currentHeight > 0) {
                    lastCanvasHeight = currentHeight
                    originalCanvasHeight = currentHeight.toFloat()
                    originalCanvasWidth = canvas.width.toFloat()
                    isLayoutInitialized = true
                    
                    // Store original control dimensions for scaling
                    storeOriginalControlDimensions()
                }
                
                // Auto-resizing disabled - user can manually adjust controls
                // if (isLayoutInitialized && currentHeight > 0 && 
                //     kotlin.math.abs(currentHeight - lastCanvasHeight) > 50) {
                //     lastCanvasHeight = currentHeight
                //     canvas.post { refreshLayoutForNewDimensions() }
                // }
            }
        })
    }

    private fun refreshLayoutForNewDimensions() {
        // Scale and reposition controls to fit the new canvas dimensions
        val canvasWidth = canvas.width.toFloat()
        val canvasHeight = canvas.height.toFloat()
        
        if (canvasWidth <= 0 || canvasHeight <= 0 || originalCanvasHeight <= 0 || originalCanvasWidth <= 0) return
        
        // Calculate scaling factors
        val heightScale = canvasHeight / originalCanvasHeight
        val widthScale = canvasWidth / originalCanvasWidth
        
        // For split screen, we primarily want to scale based on height reduction,
        // but allow width to expand if needed. Only scale down on height, allow width to stay full.
        val scaleFactor = if (heightScale < 1.0f) {
            // In split screen (height reduced), use height scale but don't constrain width
            heightScale
        } else {
            // Full screen or width change only
            minOf(heightScale, widthScale, 1.0f)
        }
        
        // Apply scaling to all controls
        controls.forEach { control ->
            val originalDims = originalControlDimensions[control.id]
            if (originalDims != null) {
                if (heightScale < 1.0f) {
                    // In split screen mode: scale everything by height reduction,
                    // but allow horizontal positions to utilize the full width
                    control.x = originalDims.x * (canvasWidth / originalCanvasWidth)
                    control.y = originalDims.y * scaleFactor
                    control.w = originalDims.w * scaleFactor
                    control.h = originalDims.h * scaleFactor
                } else {
                    // Full screen mode: normal proportional scaling
                    control.x = originalDims.x * scaleFactor
                    control.y = originalDims.y * scaleFactor
                    control.w = originalDims.w * scaleFactor
                    control.h = originalDims.h * scaleFactor
                }
                
                // Ensure controls still fit within bounds after scaling
                control.x = control.x.coerceIn(0f, (canvasWidth - control.w).coerceAtLeast(0f))
                control.y = control.y.coerceIn(0f, (canvasHeight - control.h).coerceAtLeast(0f))
            }
        }
        
        // Refresh the layout
        refreshLayout()
    }

    private fun storeOriginalControlDimensions() {
        originalControlDimensions.clear()
        controls.forEach { control ->
            originalControlDimensions[control.id] = OriginalDimensions(
                x = control.x,
                y = control.y,
                w = control.w,
                h = control.h
            )
        }
    }
}
