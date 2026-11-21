package com.example.simplecontroller.net

import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.net.SocketTimeoutException
import com.example.simplecontroller.model.isPageTransportCommand
import com.example.simplecontroller.net.scx2.*


object NetworkClient {
    enum class OutputMode { WINDOWS, LEGACY_CB, SCX2, PRACTICE }
    @Volatile var outputMode: OutputMode = OutputMode.WINDOWS
        private set
    @Volatile var triggerMode: BridgeTriggerMode = BridgeTriggerMode.OFF
        private set
    val adjustableTriggers get() = triggerMode == BridgeTriggerMode.ADJUSTABLE
    val usbButtonTriggers get() = triggerMode == BridgeTriggerMode.USB_BUTTONS
    val localOutputSelected: Boolean get() = outputMode == OutputMode.SCX2 || outputMode == OutputMode.PRACTICE
    private val canonicalOutput = Scx2CanonicalOutput()
    private val _localOutput = MutableStateFlow(Scx2PracticeOutput())
    val localOutput = _localOutput.asStateFlow()
    @Volatile private var routeGeneration = 0L
    private var scx2Session: Scx2UdpSession? = null
    private val localHandler = Handler(Looper.getMainLooper())
    private val bridgeIntent = BridgeConnectionIntent()
    val bridgeConnectionRequested: Boolean get() = outputMode == OutputMode.SCX2 && bridgeIntent.active
    private var bridgeIntentPreferences: SharedPreferences? = null
    private var bridgePersistenceInitialized = false
    private var bridgeRetry: Runnable? = null
    private var localInputResetListener: (() -> Unit)? = null
    private val bridgeHeartbeat = object : Runnable {
        override fun run() {
            if (!bridgeIntent.mayConnect || outputMode != OutputMode.SCX2) return
            bridgeIntent.heartbeat(System.currentTimeMillis(), SystemClock.elapsedRealtime())
            persistBridgeIntent()
            localHandler.postDelayed(this, 1_000)
        }
    }

    fun setLocalInputResetListener(listener: () -> Unit) { localInputResetListener = listener }
    fun clearLocalInputResetListener(listener: () -> Unit) {
        if (localInputResetListener === listener) localInputResetListener = null
    }

    /** Separate storage: reconnect permission never rewrites a layout or restores held actions. */
    fun initializeBridgeIntent(preferences: SharedPreferences) {
        if (bridgePersistenceInitialized) return
        bridgePersistenceInitialized = true
        bridgeIntentPreferences = preferences
        val saved = runCatching {
            if (preferences.getBoolean("requested", false)) BridgeConnectionIntent.Snapshot(
                BridgeConnectionIntent.Endpoint(preferences.getString("host", "").orEmpty(),
                    preferences.getInt("port", 0),
                    BridgeTriggerMode.decode(preferences.getString("triggerMode", null), preferences.getBoolean("pressure", false))
                        ?: error("Invalid trigger mode")),
                preferences.getLong("lastPresentMillis", -1), preferences.getLong("lastPresentMonotonicMillis", -1)) else null
        }.getOrNull()
        if (outputMode == OutputMode.SCX2 && saved?.endpoint ==
            BridgeConnectionIntent.Endpoint(hostAddress, portNumber, triggerMode)) {
            bridgeIntent.restore(saved, System.currentTimeMillis(), SystemClock.elapsedRealtime())
        }
        if (preferences.contains("requested") && !bridgeIntent.active) persistBridgeIntent()
    }

    private fun persistBridgeIntent() {
        val preferences = bridgeIntentPreferences ?: return
        val snapshot = bridgeIntent.snapshot()
        if (snapshot == null) {
            if (preferences.contains("requested")) preferences.edit().clear().commit()
        } else preferences.edit().putBoolean("requested", true)
            .putString("host", snapshot.endpoint.host).putInt("port", snapshot.endpoint.port)
            .putBoolean("pressure", snapshot.endpoint.pressure)
            .putString("triggerMode", snapshot.endpoint.triggerMode.name)
            .putLong("lastPresentMillis", snapshot.lastPresentMillis)
            .putLong("lastPresentMonotonicMillis", snapshot.lastPresentMonotonicMillis).commit()
    }

    /** A paused app sends no continuing output. The first pause time is the five-minute boundary. */
    fun appDidEnterBackground() {
        UdpClient.pausePCReceiverLease()
        if (outputMode != OutputMode.SCX2) return
        if (bridgeIntent.foreground) bridgeIntent.background(System.currentTimeMillis(), SystemClock.elapsedRealtime())
        localHandler.removeCallbacks(bridgeHeartbeat)
        persistBridgeIntent()
        retireLocalSession()
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        clearBridgeInputs()
    }

    fun appDidEnterForeground() {
        UdpClient.resumePCReceiverLease()
        if (outputMode != OutputMode.SCX2) return
        val resume = bridgeIntent.foreground(System.currentTimeMillis(), SystemClock.elapsedRealtime())
        persistBridgeIntent()
        if (resume) {
            localHandler.removeCallbacks(bridgeHeartbeat); localHandler.post(bridgeHeartbeat)
            scheduleBridgeRecovery(0)
        }
    }

    private fun retireLocalSession() {
        routeGeneration++
        bridgeRetry?.let(localHandler::removeCallbacks); bridgeRetry = null
        scx2Session?.stop(); scx2Session = null
    }

    private fun clearBridgeInputs() {
        UdpClient.invalidatePhoneCapability()
        resetLocalOutput()
        localInputResetListener?.invoke()
    }

    private fun scheduleBridgeRecovery(delayMillis: Long) {
        if (!bridgeIntent.mayConnect || outputMode != OutputMode.SCX2 || bridgeRetry != null) return
        val generation = routeGeneration
        val endpoint = bridgeIntent.endpoint
        _connectionStatus.value = ConnectionStatus.CONNECTING
        bridgeRetry = Runnable {
            bridgeRetry = null
            if (generation == routeGeneration && bridgeIntent.mayConnect &&
                bridgeIntent.endpoint == endpoint && outputMode == OutputMode.SCX2) startLocal(automatic = true)
        }.also { localHandler.postDelayed(it, delayMillis) }
    }

    private val cameraOriginNanos = System.nanoTime()
    private fun cameraNowMicros() = (System.nanoTime() - cameraOriginNanos) / 1000L
    private val practiceCamera = PracticeCameraProjection()
    private val _cameraFollowSnapshot = MutableStateFlow(practiceCamera.snapshot)
    val cameraFollowSnapshot = _cameraFollowSnapshot.asStateFlow()
    private var localAnalogOwnership = PhoneAnalogOwnership()
    private var cameraTimer: Runnable? = null
    private var cameraEpoch = 0L
    private var cameraRefreshPosted = false

    /** Coordinator updates are cached immediately; a queued refresh never observes a half-published state. */
    @Synchronized internal fun updateLocalAnalogOwnership(value: PhoneAnalogOwnership) {
        localAnalogOwnership = value
        requestPracticeCameraRefresh()
    }

    @Synchronized internal fun requestPracticeCameraRefresh() {
        if (outputMode != OutputMode.PRACTICE || cameraRefreshPosted) return
        cameraRefreshPosted = true
        val epoch = cameraEpoch
        localHandler.post {
            synchronized(this) {
                if (epoch != cameraEpoch) return@synchronized
                cameraRefreshPosted = false
                refreshPracticeCamera()
            }
        }
    }

    @Synchronized private fun refreshPracticeCamera() {
        cameraTimer?.let(localHandler::removeCallbacks); cameraTimer = null
        if (outputMode != OutputMode.PRACTICE || _connectionStatus.value != ConnectionStatus.CONNECTED) return
        val config = UdpClient.localCameraSettings()
        val now = cameraNowMicros()
        practiceCamera.update(canonicalOutput.state, localAnalogOwnership, config.enabled,
            config.strengthPercent, config.manualRightContact, config.mouseSuppressed, now)
        _localOutput.value = practiceCamera.output
        _cameraFollowSnapshot.value = practiceCamera.snapshot
        val deadline = practiceCamera.nextDeadlineMicros ?: return
        val epoch = cameraEpoch
        val task = Runnable { synchronized(this) { if (epoch == cameraEpoch) refreshPracticeCamera() } }
        cameraTimer = task
        // Ceiling conversion prevents executing a precise deadline slightly early and losing its next timer.
        localHandler.postDelayed(task, ((deadline - now).coerceAtLeast(1) + 999) / 1000)
    }

    /** Existing saved Windows/CBv0 routes remain explicit; SCX2 never falls back to either. */
    fun setOutputMode(mode: OutputMode, adjustableTriggers: Boolean = false) =
        setOutputMode(mode, if (adjustableTriggers) BridgeTriggerMode.ADJUSTABLE else BridgeTriggerMode.OFF)

    fun setOutputMode(mode: OutputMode, triggers: BridgeTriggerMode) {
        if (outputMode == mode && triggerMode == triggers) return
        close()
        UdpClient.close()
        outputMode = mode
        UdpClient.resetPCProtectionForDestination()
        triggerMode = triggers
        if (localOutputSelected) currentPlayerRole = PlayerRole.PLAYER1
        UdpClient.setConsoleBridgeEnabled(mode == OutputMode.LEGACY_CB)
        UdpClient.configureLocalOwnership(localOutputSelected)
        resetLocalOutput()
    }

    @Synchronized private fun resetLocalOutput() {
        cameraEpoch++
        cameraTimer?.let(localHandler::removeCallbacks); cameraTimer = null
        cameraRefreshPosted = false
        localAnalogOwnership = PhoneAnalogOwnership()
        canonicalOutput.clear()
        if (localOutputSelected) UdpClient.clearLocalCameraInputs()
        practiceCamera.reset(cameraNowMicros())
        _cameraFollowSnapshot.value = practiceCamera.snapshot
        _localOutput.value = canonicalOutput.state
    }

    /** Drop old work before cleanup. Recovery starts only after that synchronous cleanup returns. */
    @Synchronized internal fun releaseLocalOutput() {
        if (!localOutputSelected) return
        if (outputMode == OutputMode.SCX2) {
            // A connecting session has never accepted input. Keep its original handshake deadline
            // across rapid page changes instead of restarting the timeout for each selection.
            if (_connectionStatus.value == ConnectionStatus.CONNECTED) {
                retireLocalSession()
                _connectionStatus.value = ConnectionStatus.CONNECTING
            }
            clearBridgeInputs()
            if (bridgeIntent.mayConnect && scx2Session == null) scheduleBridgeRecovery(0)
            if (!bridgeIntent.active) _connectionStatus.value = ConnectionStatus.DISCONNECTED
        } else resetLocalOutput()
    }

    private fun startLocal(automatic: Boolean = false) {
        retireLocalSession()
        val generation = routeGeneration
        _lastErrorMessage.value = null
        if (outputMode == OutputMode.PRACTICE) {
            resetLocalOutput()
            _connectionStatus.value = ConnectionStatus.CONNECTED
            refreshPracticeCamera()
            return
        }
        val endpoint = bridgeIntent.endpoint ?: return
        if (!bridgeIntent.mayConnect) return
        _connectionStatus.value = ConnectionStatus.CONNECTING
        clearBridgeInputs()
        val revision = endpoint.triggerMode.revision
        scx2Session = Scx2UdpSession(endpoint.host, endpoint.port, revision,
            { action -> localHandler.post { action() } }, { phase, failure ->
                if (routeGeneration == generation && outputMode == OutputMode.SCX2 && bridgeIntent.mayConnect) {
                    when (phase) {
                        Scx2ClientMachine.Phase.CONNECTING, Scx2ClientMachine.Phase.WAITING_FOR_NEUTRAL ->
                            _connectionStatus.value = ConnectionStatus.CONNECTING
                        Scx2ClientMachine.Phase.READY -> {
                            // A touch that began while disabled must never acquire the fresh session.
                            clearBridgeInputs()
                            bridgeIntent.ready()
                            _lastErrorMessage.value = null
                            _connectionStatus.value = ConnectionStatus.CONNECTED
                        }
                        Scx2ClientMachine.Phase.STOPPED -> {
                            retireLocalSession()
                            _connectionStatus.value = ConnectionStatus.CONNECTING
                            clearBridgeInputs()
                            _lastErrorMessage.value = when (failure) {
                                Scx2ClientMachine.Failure.PRESSURE_UNAVAILABLE -> "Pico adjustable LT/RT output is not ready. Retrying automatically; no digital fallback."
                                Scx2ClientMachine.Failure.USB_TRIGGERS_UNAVAILABLE -> "Pico USB trigger output is not ready. Install the USB trigger firmware and map X1 to LT / X2 to RT in Xbox Accessories. Retrying automatically."
                                Scx2ClientMachine.Failure.USB_PRESSURE_UNAVAILABLE -> "Pico adjustable USB output is not ready. Check the Cronus Zen connection and install the matching Pico firmware. Retrying automatically."
                                Scx2ClientMachine.Failure.HELLO_TIMEOUT -> when (endpoint.triggerMode) {
                                    BridgeTriggerMode.CRONUS_ZEN -> "No Cronus Zen bridge reply. Install the matching Pico firmware; retrying automatically."
                                    BridgeTriggerMode.USB_BUTTONS -> "No USB trigger reply. Update the phone Pico firmware for USB button triggers; retrying automatically."
                                    else -> "Pico did not answer. Retrying automatically."
                                }
                                Scx2ClientMachine.Failure.USB_UNAVAILABLE -> "Pico USB output is not ready. Check its XAC connection; retrying automatically."
                                else -> "Pico paused (${failure?.name?.lowercase()?.replace('_', ' ') ?: "connection closed"}). Retrying automatically."
                            }
                            scheduleBridgeRecovery(bridgeIntent.retryDelayMillis())
                        }
                        Scx2ClientMachine.Phase.IDLE -> Unit
                    }
                }
            }, waitForAvailability = automatic).also { it.start() }
    }

    /** Scoped state arrives synchronously after ownership resolution, before either UDP queue. */
    @Synchronized internal fun acceptLocalPhonePacket(packet: PhoneOutputPacket) {
        if (!localOutputSelected || _connectionStatus.value != ConnectionStatus.CONNECTED || !packet.isCurrent()) {
            packet.onConfirmed(false); return
        }
        // Replay can emit release then press. Preserve each transition, and confirm only the final one.
        val outputs = mutableListOf<Scx2PracticeOutput>()
        if (!canonicalOutput.acceptFrame(packet.command, outputs::add)) { packet.onConfirmed(false); return }
        outputs.forEachIndexed { index, output ->
            publishLocalOutput(if (index == outputs.lastIndex) packet.onConfirmed else { _: Boolean -> }, output)
        }
    }

    @Synchronized private fun publishLocalOutput(onConfirmed: (Boolean) -> Unit = {}, state: Scx2PracticeOutput = canonicalOutput.state) {
        _localOutput.value = state
        val generation = routeGeneration
        if (outputMode == OutputMode.PRACTICE) {
            refreshPracticeCamera()
            localHandler.post { onConfirmed(generation == routeGeneration && outputMode == OutputMode.PRACTICE) }
        } else {
            // The explicit mode changes transmitted output only; requested profile pressures stay intact.
            val output = triggerMode.output(state.gamepad, state.leftTriggerRequested, state.rightTriggerRequested)
            val session = scx2Session
            if (session == null) { onConfirmed(false); return }
            session.submit(output) { okay -> onConfirmed(okay && generation == routeGeneration && session === scx2Session) }
        }
    }

    // Connection status enum
    enum class ConnectionStatus {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    // Define player roles
    enum class PlayerRole {
        PLAYER1,
        PLAYER2
    }

    data class ReceiverEndpoint(val host: String, val port: Int)

    private const val USB_DISCOVERY_REQUEST = "SIMPLE_CONTROLLER_DISCOVER"
    private const val USB_DISCOVERY_RESPONSE_PREFIX = "SIMPLE_CONTROLLER_HERE:"
    private const val CURRENT_RECEIVER_PORT = 42734
    private const val USB_DISCOVERY_TIMEOUT_MS = 12_000L

    // Current player selection
    private var currentPlayerRole = PlayerRole.PLAYER1

    // Expose connection status as state flow for UI updates
    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus = _connectionStatus.asStateFlow()

    // Add last error message
    private val _lastErrorMessage = MutableStateFlow<String?>(null)
    val lastErrorMessage = _lastErrorMessage.asStateFlow()

    // Connection settings with default values
    private var hostAddress = "10.0.2.2"
    private var portNumber = 9001

    // Auto-reconnect settings
    private var autoReconnect = false
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 5
    private val reconnectDelayMs = 3000L
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private val reconnectRunnable = Runnable { start() }

    // Heartbeat for connection verification
    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private val heartbeatInterval = 2000L // 2 seconds
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (_connectionStatus.value == ConnectionStatus.CONNECTED) {
                sendHeartbeat()
                heartbeatHandler.postDelayed(this, heartbeatInterval)
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val cleanupScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var closeCleanupJob: Job? = null
    private var socket: DatagramSocket? = null
    private var serverAddress: InetAddress? = null

    private val releaseHandler = Handler(Looper.getMainLooper())
    private var pendingReleaseCommand: String? = null
    private var pendingReleaseAcknowledgement: String? = null
    private var releaseRetryRunnable: Runnable? = null
    private var releaseTimeoutRunnable: Runnable? = null
    private var releaseResultCallback: ((Boolean) -> Unit)? = null

    private data class PhoneRequest(val guard: () -> Boolean, val callback: (Boolean) -> Unit,
                                    var retry: Runnable? = null, var timeout: Runnable? = null)
    private val phoneRequests = mutableMapOf<String, PhoneRequest>()

    /**
     * Configure socket for minimal latency
     */
    private fun setupLowLatencySocket(socket: DatagramSocket) {
        try {
            // Set minimal buffer sizes
            socket.sendBufferSize = 1024
            socket.receiveBufferSize = 1024

            // Set traffic class for low latency if on newer Android versions
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                socket.trafficClass = 0x10  // IPTOS_LOWDELAY
            }

            Log.d("NetworkClient", "Applied low-latency socket configuration")
        } catch (e: Exception) {
            Log.e("NetworkClient", "Failed to apply low-latency socket config: ${e.message}")
        }
    }

    /** Update connection settings */
    fun updateSettings(host: String, port: Int, autoReconnectEnabled: Boolean) {
        if (localOutputSelected) {
            if (outputMode == OutputMode.SCX2 && !BridgeConnectionIntent.Endpoint(host, port, triggerMode).valid) {
                _lastErrorMessage.value = "Enter a valid Pico network address and port from 1 to 65535."
                return
            }
            if (host != hostAddress || port != portNumber) close()
            hostAddress = host; portNumber = port; autoReconnect = false
            return
        }
        val changed = host != hostAddress || port != portNumber
        val restart = _connectionStatus.value == ConnectionStatus.CONNECTED ||
            _connectionStatus.value == ConnectionStatus.CONNECTING
        // Capture and retire the old endpoint before changing its fields. This also
        // invalidates a DNS/connection attempt that has not installed its socket yet.
        if (changed || restart) close()
        if (changed) UdpClient.resetPCProtectionForDestination()
        UdpClient.invalidatePhoneCapability()
        this.hostAddress = host
        this.portNumber = port
        this.autoReconnect = autoReconnectEnabled
        if (restart) start()
    }

    /** Set the player role for this client */
    fun setPlayerRole(role: PlayerRole) {
        if (localOutputSelected) { currentPlayerRole = PlayerRole.PLAYER1; return }
        if (currentPlayerRole != role) UdpClient.invalidatePhoneCapability()
        currentPlayerRole = role
        Log.d("NetworkClient", "Player role set to: $role")

        // If already connected, send player registration
        if (_connectionStatus.value == ConnectionStatus.CONNECTED) {
            val roleId = if (role == PlayerRole.PLAYER1) "player1" else "player2"
            send("REGISTER:$roleId")
            UdpClient.requestPhoneCapability()
        }
    }

    /** Get the current player role */
    fun getPlayerRole(): PlayerRole {
        return currentPlayerRole
    }

    /** Start connection to server */
    fun start() {
        if (localOutputSelected) {
            if (outputMode == OutputMode.SCX2) {
                val endpoint = BridgeConnectionIntent.Endpoint(hostAddress, portNumber, triggerMode)
                if (!bridgeIntent.connect(endpoint, System.currentTimeMillis(), SystemClock.elapsedRealtime())) {
                    _lastErrorMessage.value = "Enter a valid Pico network address and port from 1 to 65535."
                    return
                }
                persistBridgeIntent()
                localHandler.removeCallbacks(bridgeHeartbeat); localHandler.post(bridgeHeartbeat)
            }
            if (_connectionStatus.value != ConnectionStatus.CONNECTED && _connectionStatus.value != ConnectionStatus.CONNECTING) startLocal()
            else if (outputMode == OutputMode.SCX2 && scx2Session == null) startLocal()
            return
        }
        val generation = routeGeneration
        val connectingHost = hostAddress
        val connectingPort = portNumber
        val connectingRole = currentPlayerRole
        val previousCleanup = closeCleanupJob
        if (_connectionStatus.value == ConnectionStatus.CONNECTING) return
        if (outputMode == OutputMode.WINDOWS) UdpClient.beginPCConnection()

        _connectionStatus.value = ConnectionStatus.CONNECTING
        scope.launch {
            var prepared: DatagramSocket? = null
            var installed = false
            try {
                previousCleanup?.join()
                if (generation != routeGeneration || localOutputSelected) return@launch
                val resolved = InetAddress.getByName(connectingHost)
                prepared = DatagramSocket().also(::setupLowLatencySocket)
                if (generation != routeGeneration || localOutputSelected) return@launch
                // Android forbids network I/O on the UI thread, including this UDP greeting.
                val greeting = "CONNECT:${if (connectingRole == PlayerRole.PLAYER1) "player1" else "player2"}".toByteArray()
                prepared.send(DatagramPacket(greeting, greeting.size, resolved, connectingPort))
                withContext(Dispatchers.Main) {
                    if (generation != routeGeneration || localOutputSelected) return@withContext
                    reconnectHandler.removeCallbacks(reconnectRunnable)
                    socket?.close()
                    socket = prepared
                    serverAddress = resolved
                    installed = true
                    _connectionStatus.value = ConnectionStatus.CONNECTED
                    startListening()
                    reconnectAttempts = 0
                    heartbeatHandler.post(heartbeatRunnable)
                    send("REGISTER:${if (currentPlayerRole == PlayerRole.PLAYER1) "player1" else "player2"}")
                    UdpClient.requestPhoneCapability()
                }
            } catch (e: Exception) {
                withContext(NonCancellable + Dispatchers.Main) {
                    if (generation == routeGeneration && !localOutputSelected) {
                        _connectionStatus.value = ConnectionStatus.ERROR
                        _lastErrorMessage.value = "Failed to connect: ${e.message}"
                        if (autoReconnect && reconnectAttempts < maxReconnectAttempts) scheduleReconnect()
                    }
                }
            } finally { if (!installed) prepared?.close() }
        }
    }

    /**
     * Discover the Python receiver over the active USB-tether network.
     * Manual host/port settings are not changed unless a receiver explicitly replies.
     */
    fun discoverUsbTetherReceiver(onResult: (ReceiverEndpoint?) -> Unit) {
        if (localOutputSelected) { onResult(null); return }
        close()
        _lastErrorMessage.value = null
        _connectionStatus.value = ConnectionStatus.CONNECTING

        scope.launch {
            val endpoint = try {
                findUsbTetherReceiver()
            } catch (e: Exception) {
                Log.e("NetworkClient", "USB tether discovery failed", e)
                null
            }

            if (endpoint == null) {
                _connectionStatus.value = ConnectionStatus.ERROR
                _lastErrorMessage.value =
                    "USB receiver not found. Check USB tethering, the PC receiver, and Windows Firewall."
            } else {
                // Let the normal connection path take over using the verified endpoint.
                _connectionStatus.value = ConnectionStatus.DISCONNECTED
            }

            withContext(Dispatchers.Main.immediate) {
                onResult(endpoint)
            }
        }
    }

    private suspend fun findUsbTetherReceiver(): ReceiverEndpoint? {
        val deadline = System.currentTimeMillis() + USB_DISCOVERY_TIMEOUT_MS
        val requestBytes = USB_DISCOVERY_REQUEST.toByteArray()

        DatagramSocket().use { discoverySocket ->
            discoverySocket.broadcast = true

            while (System.currentTimeMillis() < deadline) {
                val ports = linkedSetOf(portNumber, CURRENT_RECEIVER_PORT, 9001)
                    .filter { it in 1..65535 }

                usbTetherBroadcastAddresses().forEach { address ->
                    ports.forEach { port ->
                        runCatching {
                            discoverySocket.send(
                                DatagramPacket(requestBytes, requestBytes.size, address, port)
                            )
                        }.onFailure {
                            Log.d("NetworkClient", "USB discovery send skipped for $address:$port")
                        }
                    }
                }

                val receiveUntil = minOf(deadline, System.currentTimeMillis() + 900L)
                while (System.currentTimeMillis() < receiveUntil) {
                    val remaining = (receiveUntil - System.currentTimeMillis())
                        .coerceIn(1L, 900L)
                    discoverySocket.soTimeout = remaining.toInt()

                    val responseBytes = ByteArray(256)
                    val response = DatagramPacket(responseBytes, responseBytes.size)
                    try {
                        discoverySocket.receive(response)
                    } catch (_: SocketTimeoutException) {
                        break
                    }

                    val message = String(response.data, 0, response.length).trim()
                    if (!message.startsWith(USB_DISCOVERY_RESPONSE_PREFIX)) continue

                    val advertisedPort = message
                        .substringAfter(USB_DISCOVERY_RESPONSE_PREFIX)
                        .toIntOrNull()
                        ?.takeIf { it in 1..65535 }
                        ?: continue
                    val discoveredHost = response.address.hostAddress ?: continue
                    Log.i(
                        "NetworkClient",
                        "Found USB tether receiver at $discoveredHost:$advertisedPort"
                    )
                    return ReceiverEndpoint(discoveredHost, advertisedPort)
                }

                delay(250L)
            }
        }

        return null
    }

    private fun usbTetherBroadcastAddresses(): Set<InetAddress> {
        val addresses = linkedSetOf<InetAddress>()

        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces != null && interfaces.hasMoreElements()) {
                val network = interfaces.nextElement()
                val name = network.name.lowercase()
                val looksLikeUsb = name.contains("rndis") ||
                    name.contains("usb") ||
                    name.contains("ncm") ||
                    name.contains("tether") ||
                    name.startsWith("eth")
                if (!looksLikeUsb || !network.isUp || network.isLoopback) continue

                network.interfaceAddresses.forEach { interfaceAddress ->
                    interfaceAddress.broadcast?.let(addresses::add)
                }
            }
        }.onFailure {
            Log.d("NetworkClient", "Could not enumerate USB network interfaces: ${it.message}")
        }

        // Common Android USB-tether subnets cover devices that hide interface details.
        listOf(
            "192.168.42.255",
            "192.168.137.255",
            "192.168.234.255"
        ).forEach { addresses.add(InetAddress.getByName(it)) }

        return addresses
    }

    /** Start listening for server responses */
    private fun startListening() {
        val listeningSocket = socket ?: return
        scope.launch {
            val buffer = ByteArray(1024)
            val packet = DatagramPacket(buffer, buffer.size)

            while (socket === listeningSocket && _connectionStatus.value == ConnectionStatus.CONNECTED) {
                try {
                    listeningSocket.soTimeout = 5000       // 5-second timeout
                    packet.length = buffer.size // Each reply may be longer than the previous datagram.
                    listeningSocket.receive(packet)        // blocks
                    if (socket !== listeningSocket) break
                    if (packet.address != serverAddress || packet.port != portNumber) continue
                    val received = String(packet.data, 0, packet.length)

                    // Handle received message (e.g., PONG responses, etc.)
                    handleServerMessage(received)

                } catch (e: SocketTimeoutException) {
                    // No packet within 5 s → harmless for an idle controller
                    continue

                } catch (e: SocketException) {
                    if (socket !== listeningSocket || _connectionStatus.value != ConnectionStatus.CONNECTED) {
                        // Socket was intentionally closed; exit the loop
                        break
                    }
                    Log.e("NetworkClient", "Socket error while listening", e)
                    handleDisconnect(listeningSocket)
                    break

                } catch (e: Exception) {
                    if (socket !== listeningSocket) break
                    Log.e("NetworkClient", "Error receiving data", e)
                    if (_connectionStatus.value == ConnectionStatus.CONNECTED) {
                        _lastErrorMessage.value = "Connection error: ${e.message}"
                    }
                }
            }
        }
    }


    /** Handle messages from the server */
    private fun handleServerMessage(message: String) {
        when {
            message.trim().startsWith("PHONE_") -> {
                val feedback = message.trim()
                val acknowledgement = if (feedback.startsWith("PHONE_ACK_V1:"))
                    feedback.split(':').take(3).joinToString(":") else feedback
                releaseHandler.post {
                    val request = phoneRequests[acknowledgement]
                    if (request?.guard?.invoke() == true) UdpClient.acceptPhoneOutputFeedback(feedback)
                    finishPhoneRequest(acknowledgement, true)
                }
            }
            message.trim() == pendingReleaseAcknowledgement -> {
                val acknowledgement = message.trim()
                releaseHandler.post {
                    if (pendingReleaseAcknowledgement == acknowledgement) {
                        finishReleaseRequest(acknowledged = true)
                    }
                }
            }
            message.startsWith("PONG") -> {
                // Heartbeat response, connection is still alive
                Log.d("NetworkClient", "Received heartbeat response")
            }
            // Add other message types as needed
        }
    }

    private var pcReceiverWarning: String? = null
    internal fun reportUnprotectedPCReceiver(capabilityConfirmed: Boolean) {
        pcReceiverWarning = if (capabilityConfirmed)
            "This older PC receiver cannot release input after Wi-Fi loss. Update the free receiver in Support."
        else "PC receiver protection was not confirmed. Check the IP and port, or install the updated free receiver in Support."
        _lastErrorMessage.value = pcReceiverWarning
    }
    internal fun clearPCReceiverWarning() {
        if (_lastErrorMessage.value == pcReceiverWarning) _lastErrorMessage.value = null
        pcReceiverWarning = null
    }
    internal fun pcReceiverLeaseLost() {
        if (outputMode != OutputMode.WINDOWS) return
        UdpClient.prepareReleaseAll()
        UdpClient.sendReleaseAll { }
        localInputResetListener?.invoke()
        close()
        pcReceiverWarning = "PC receiver stopped replying. Phone controls were cleared; the receiver releases held input within 3 seconds. Reconnect from neutral."
        _lastErrorMessage.value = pcReceiverWarning
        if (autoReconnect) scheduleReconnect()
    }

    /** Send a heartbeat to verify connection */
    private fun sendHeartbeat() {
        if (localOutputSelected) return
        val sendingSocket = socket ?: return
        val sendingAddress = serverAddress ?: return
        val sendingPort = portNumber
        sendUdpHeartbeat(scope, sendingSocket, sendingAddress, sendingPort,
            isCurrent = {
                socket === sendingSocket && serverAddress == sendingAddress &&
                    portNumber == sendingPort && _connectionStatus.value == ConnectionStatus.CONNECTED
            },
            onFailure = { error ->
                Log.e("NetworkClient", "Failed to send heartbeat", error)
                handleDisconnect(sendingSocket)
            })
    }

    /** Close the connection */
    fun close() {
        val closingSocket = socket
        val closingAddress = serverAddress
        val closingPort = portNumber
        val closingRelease = pendingReleaseCommand
        val sendDisconnect = !localOutputSelected && _connectionStatus.value == ConnectionStatus.CONNECTED
        bridgeIntent.disconnect()
        localHandler.removeCallbacks(bridgeHeartbeat)
        persistBridgeIntent()
        retireLocalSession()
        if (outputMode == OutputMode.SCX2) {
            _connectionStatus.value = ConnectionStatus.DISCONNECTED
            clearBridgeInputs()
        } else resetLocalOutput()
        UdpClient.invalidatePhoneCapability()
        finishPhoneRequests()
        reconnectHandler.removeCallbacks(reconnectRunnable)
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        finishReleaseRequest(acknowledged = false)
        reconnectAttempts = 0
        // Retire the route immediately, then flush only its captured socket off the UI
        // thread. The normal worker scope is canceled by closeConnection, so cleanup
        // has its own scope and cannot accidentally send to a newly selected receiver.
        closeConnection(closeSocket = false)
        if (closingSocket != null) {
            val previousCleanup = closeCleanupJob
            closeCleanupJob = cleanupScope.launch {
                previousCleanup?.join()
                flushAndCloseReceiverSocket(closingSocket, closingAddress, closingPort, closingRelease, sendDisconnect)
            }
        }
    }

    /**
     * Retry the same idempotent RELEASE_ALL command and wait briefly for its exact ACK.
     * Repeated taps replace only the UI callback; receiver duplicates remain harmless.
     */
    fun requestReleaseAll(
        command: String,
        expectedAcknowledgement: String,
        onResult: (Boolean) -> Unit
    ) {
        if (_connectionStatus.value != ConnectionStatus.CONNECTED) {
            onResult(false)
            return
        }

        finishReleaseRequest(acknowledged = false, notify = false)
        pendingReleaseCommand = command
        pendingReleaseAcknowledgement = expectedAcknowledgement
        releaseResultCallback = onResult

        var attempts = 0
        releaseRetryRunnable = object : Runnable {
            override fun run() {
                if (pendingReleaseAcknowledgement != expectedAcknowledgement) return
                send(command)
                attempts++
                if (attempts < RELEASE_RETRY_COUNT) {
                    releaseHandler.postDelayed(this, RELEASE_RETRY_INTERVAL_MS)
                } else {
                    releaseTimeoutRunnable = Runnable {
                        if (pendingReleaseAcknowledgement == expectedAcknowledgement) {
                            finishReleaseRequest(acknowledged = false)
                        }
                    }.also {
                        releaseHandler.postDelayed(it, RELEASE_ACK_TIMEOUT_MS)
                    }
                }
            }
        }.also { releaseHandler.post(it) }
    }

    private fun finishReleaseRequest(acknowledged: Boolean, notify: Boolean = true) {
        releaseRetryRunnable?.let { releaseHandler.removeCallbacks(it) }
        releaseTimeoutRunnable?.let { releaseHandler.removeCallbacks(it) }
        releaseRetryRunnable = null
        releaseTimeoutRunnable = null
        pendingReleaseCommand = null
        pendingReleaseAcknowledgement = null
        val callback = releaseResultCallback
        releaseResultCallback = null
        if (notify) callback?.invoke(acknowledged)
    }

    /** Internal method to close socket */
    private fun closeConnection(closeSocket: Boolean = true) {
        UdpClient.invalidatePhoneCapability()
        finishPhoneRequests()
        if (closeSocket) socket?.close()
        socket = null
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        scope.coroutineContext.cancelChildren()
    }

    /** Handle disconnection */
    private fun handleDisconnect(expectedSocket: DatagramSocket? = socket) {
        localHandler.post {
            if (localOutputSelected || socket !== expectedSocket) return@post
            closeConnection()
            if (autoReconnect && reconnectAttempts < maxReconnectAttempts) scheduleReconnect()
        }
    }

    /** Schedule a reconnection attempt */
    private fun scheduleReconnect() {
        if (localOutputSelected) return
        reconnectAttempts++
        Log.d("NetworkClient", "Scheduling reconnect attempt $reconnectAttempts/$maxReconnectAttempts")
        reconnectHandler.postDelayed(reconnectRunnable, reconnectDelayMs)
    }

    /**
     * Send a command with the current player prefix.
     * Non‑blocking, thread‑safe.
     */
    fun send(message: String) {
        sendGuarded(message) { true }
    }

    /** Capture routing before either send queue runs; guards survive fallback queuing. */
    fun sendGuarded(message: String, isCurrent: () -> Boolean) {
        if (localOutputSelected) {
            if (isCurrent()) UdpClient.sendLocalCommand(message)
            return
        }
        if (isPageTransportCommand(message)) {
            Log.w("NetworkClient", "Blocked Android-local page command from receiver transport")
            return
        }
        android.util.Log.d("NetworkClient", "Sending: $message")
        if (_connectionStatus.value == ConnectionStatus.CONNECTED) {
            val sendingSocket = socket ?: return
            val sendingAddress = serverAddress ?: return
            val sendingPort = portNumber
            val sendingRole = currentPlayerRole
            scope.launch {
                try {
                    if (localOutputSelected || !isCurrent() || socket !== sendingSocket || serverAddress != sendingAddress ||
                        portNumber != sendingPort || currentPlayerRole != sendingRole ||
                        _connectionStatus.value != ConnectionStatus.CONNECTED) return@launch
                    // Prefix message with player ID if it doesn't already have one
                    val prefixedMessage = if (message.startsWith("REGISTER:") ||
                        message.startsWith("player1:") ||
                        message.startsWith("player2:")) {
                        message
                    } else {
                        val playerPrefix = if (sendingRole == PlayerRole.PLAYER1) "player1:" else "player2:"
                        "$playerPrefix$message"
                    }

                    // Send the message via UDP
                    val buffer = prefixedMessage.toByteArray()
                    val packet = DatagramPacket(buffer, buffer.size, sendingAddress, sendingPort)
                    if (isCurrent()) sendingSocket.send(packet)
                } catch (e: Exception) {
                    if (socket !== sendingSocket || serverAddress != sendingAddress ||
                        portNumber != sendingPort || currentPlayerRole != sendingRole) return@launch
                    Log.e("NetworkClient", "Failed to send message: ${e.message}")
                    _lastErrorMessage.value = "Failed to send data: ${e.message}"

                    // Check if this is a connection error
                    if (e is SocketException) {
                        handleDisconnect(sendingSocket)
                    }
                }
            }
        } else {
            Log.w("NetworkClient", "Cannot send when not connected")
        }
    }

    /** Each scoped state/end identity has its own bounded, duplicate-safe retry. */
    fun requestPhoneFrame(command: String, expectedAck: String, isCurrent: () -> Boolean,
                          onResult: (Boolean) -> Unit) {
        releaseHandler.post {
            if (_connectionStatus.value != ConnectionStatus.CONNECTED || !isCurrent()) {
                onResult(false)
                return@post
            }
            finishPhoneRequest(expectedAck, false)
            val request = PhoneRequest(isCurrent, onResult)
            phoneRequests[expectedAck] = request
            var attempts = 0
            request.retry = object : Runnable {
                override fun run() {
                    if (phoneRequests[expectedAck] !== request) return
                    if (!request.guard()) { finishPhoneRequest(expectedAck, false); return }
                    sendGuarded(command, request.guard)
                    attempts++
                    if (attempts < RELEASE_RETRY_COUNT) releaseHandler.postDelayed(this, RELEASE_RETRY_INTERVAL_MS)
                    else request.timeout = Runnable { finishPhoneRequest(expectedAck, false) }.also {
                        releaseHandler.postDelayed(it, RELEASE_ACK_TIMEOUT_MS)
                    }
                }
            }.also(releaseHandler::post)
        }
    }

    private fun finishPhoneRequest(acknowledgement: String, confirmed: Boolean) {
        val request = phoneRequests.remove(acknowledgement) ?: return
        request.retry?.let(releaseHandler::removeCallbacks)
        request.timeout?.let(releaseHandler::removeCallbacks)
        request.callback(confirmed && request.guard())
    }

    private fun finishPhoneRequests() {
        releaseHandler.post { phoneRequests.keys.toList().forEach { finishPhoneRequest(it, false) } }
    }

    private const val RELEASE_RETRY_COUNT = 5
    private const val RELEASE_RETRY_INTERVAL_MS = 60L
    private const val RELEASE_ACK_TIMEOUT_MS = 300L
}
