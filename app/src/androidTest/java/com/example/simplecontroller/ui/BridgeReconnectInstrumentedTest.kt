package com.example.simplecontroller.ui

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.simplecontroller.MainActivity
import com.example.simplecontroller.model.*
import com.example.simplecontroller.net.NetworkClient
import com.example.simplecontroller.net.UdpClient
import com.example.simplecontroller.net.PhoneOutputScope
import com.example.simplecontroller.net.scx2.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android activity, gesture timers, ownership and loopback UDP; no Xbox output is involved. */
@RunWith(AndroidJUnit4::class)
class BridgeReconnectInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private data class Received(val packet: Scx2Packet, val address: InetAddress, val port: Int)
    private class Peer : AutoCloseable {
        val socket = DatagramSocket(0, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 100 }
        val hello = LinkedBlockingQueue<Received>()
        val states = LinkedBlockingQueue<Received>()
        val boot = UUID.randomUUID()
        val challenge = UUID.randomUUID()
        @Volatile var available = true
        @Volatile var acknowledge = true
        @Volatile var running = true
        private val worker = Thread {
            while (running) try {
                val datagram = DatagramPacket(ByteArray(2048), 2048)
                socket.receive(datagram)
                val packet = Scx2Codec.decode(datagram.data.copyOf(datagram.length))
                val received = Received(packet, datagram.address, datagram.port)
                when (packet.kind) {
                    Scx2Kind.HELLO -> {
                        hello.offer(received)
                        send(received, packet.copy(kind = Scx2Kind.READY, boot = boot,
                            challenge = if (available) challenge else Scx2Packet.ZERO, hidReady = available,
                            pressureCapability = available && packet.revision == Scx2Revision.PRESSURE,
                            usbButtonCapability = available && packet.revision == Scx2Revision.USB_BUTTONS,
                            usbPressureCapability = available && packet.revision == Scx2Revision.USB_PRESSURE))
                    }
                    Scx2Kind.STATE -> {
                        states.offer(received)
                        if (acknowledge) send(received, packet.copy(kind = Scx2Kind.ACK, hidReady = true, output = null))
                    }
                    else -> Unit
                }
            } catch (_: SocketTimeoutException) {} catch (_: Exception) { if (running) throw AssertionError("Peer failed") }
        }.apply { isDaemon = true; start() }
        fun send(target: Received, packet: Scx2Packet) {
            val bytes = Scx2Codec.encode(packet)
            socket.send(DatagramPacket(bytes, bytes.size, target.address, target.port))
        }
        fun nextHello(previous: UUID? = null): Received {
            val until = SystemClock.uptimeMillis() + 5000
            while (SystemClock.uptimeMillis() < until) {
                val value = hello.poll(100, TimeUnit.MILLISECONDS) ?: continue
                if (value.packet.session != previous) return value
            }
            throw AssertionError("No fresh HELLO")
        }
        fun nextState(session: UUID, predicate: (Scx2Output) -> Boolean = { true }): Received {
            val until = SystemClock.uptimeMillis() + 5000
            while (SystemClock.uptimeMillis() < until) {
                val value = states.poll(100, TimeUnit.MILLISECONDS) ?: continue
                if (value.packet.session == session && predicate(value.packet.output!!)) return value
            }
            throw AssertionError("No expected state for $session")
        }
        override fun close() { running = false; socket.close(); worker.join(1000) }
    }
    @Before fun prepare() = main {
        NetworkClient.close()
        GlobalSettings.editMode = false; GlobalSettings.globalTurbo = false; GlobalSettings.globalHold = false
        GlobalSettings.globalSwipe = false
        instrumentation.targetContext.getSharedPreferences("network", Context.MODE_PRIVATE).edit()
            .putString("outputMode", NetworkClient.OutputMode.PRACTICE.name).commit()
        NetworkClient.setOutputMode(NetworkClient.OutputMode.PRACTICE); NetworkClient.start()
    }
    @After fun cleanUp() = main { NetworkClient.close() }
    private fun connect(peer: Peer, mode: BridgeTriggerMode = BridgeTriggerMode.ADJUSTABLE) = main {
        NetworkClient.setOutputMode(NetworkClient.OutputMode.SCX2, mode)
        NetworkClient.updateSettings("127.0.0.1", peer.socket.localPort, false)
        NetworkClient.start()
    }
    private fun awaitConnected() {
        val until = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < until) {
            if (NetworkClient.connectionStatus.value == NetworkClient.ConnectionStatus.CONNECTED) return
            SystemClock.sleep(10)
        }
        fail("Bridge did not confirm neutral")
    }
    private fun touch(view: ControlView, action: Int) {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, 70f, 70f, 0)
        try { view.onTouchEvent(event) } finally { event.recycle() }
    }
    private fun page(activity: MainActivity, id: String) {
        activity.javaClass.getDeclaredMethod("activateControllerPageAction", PageAction::class.java, String::class.java)
            .apply { isAccessible = true }.invoke(activity, PageAction.GO_TO, id)
    }
    private fun displayedPage(activity: MainActivity): String = activity.javaClass.getDeclaredField("displayedPageId")
        .apply { isAccessible = true }.get(activity) as String

    @Test fun pageChangesKeepLatestPageButDiscardMacroRetainedStickAndOldOwnership() {
        Peer().use { peer -> ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var macro: ControlView
            scenario.onActivity { activity ->
                val profile = ControllerProfile(homePageId = "walk", pages = listOf(
                    ControllerPage("walk", "Walk", listOf(Control("macro", ControlType.BUTTON,
                        0f, 0f, 140f, 140f, "X360A(U2000),X360B(D700)(U200)").apply { macroModeEnabled = true })),
                    ControllerPage("drive", "Drive", emptyList()), ControllerPage("extras", "Extras", emptyList())))
                activity.javaClass.getDeclaredMethod("installProfile", String::class.java, ControllerProfile::class.java)
                    .apply { isAccessible = true }.invoke(activity, "bridge-transition-test", profile)
            }
            connect(peer); val old = peer.nextHello(); awaitConnected()
            lateinit var held: PhoneOutputScope
            scenario.onActivity { activity ->
                val canvas = activity.findViewById<android.widget.FrameLayout>(com.example.simplecontroller.R.id.canvas)
                macro = (0 until canvas.childCount).map { canvas.getChildAt(it) }.filterIsInstance<ControlView>().single()
                touch(macro, MotionEvent.ACTION_DOWN); touch(macro, MotionEvent.ACTION_UP)
                held = UdpClient.beginOutputScope("retained-test")
                held.sendStick("L", 0.6f, 0.8f); held.sendCommand("LT:0.5")
            }
            val oldPress = peer.nextState(old.packet.session) { it.buttons != 0 }
            peer.available = false; peer.acknowledge = false
            scenario.onActivity { activity ->
                page(activity, "drive"); page(activity, "extras")
                assertEquals("extras", displayedPage(activity))
                assertFalse(held.isActive)
                assertFalse(held.sendCommand("RT:1.0"))
                assertEquals(Scx2Output.NEUTRAL, NetworkClient.localOutput.value.gamepad)
                assertFalse(macro.isPressed)
            }
            val fresh = peer.nextHello(old.packet.session)
            // Deliver the previous session's ACK to the replacement socket; it cannot arm it.
            peer.send(fresh, oldPress.packet.copy(kind = Scx2Kind.ACK, hidReady = true, output = null))
            SystemClock.sleep(100)
            assertEquals(NetworkClient.ConnectionStatus.CONNECTING, NetworkClient.connectionStatus.value)
            peer.available = true
            val neutral = peer.nextState(fresh.packet.session)
            assertEquals(1uL, neutral.packet.sequence); assertEquals(Scx2Output.NEUTRAL, neutral.packet.output)
            assertEquals(NetworkClient.ConnectionStatus.CONNECTING, NetworkClient.connectionStatus.value)
            peer.send(neutral, neutral.packet.copy(kind = Scx2Kind.ACK, hidReady = true, output = null))
            peer.acknowledge = true; awaitConnected(); SystemClock.sleep(800)
            scenario.onActivity { activity ->
                assertEquals("extras", displayedPage(activity))
                assertEquals(Scx2Output.NEUTRAL, NetworkClient.localOutput.value.gamepad)
                assertFalse(held.isActive)
            }
            for (received in peer.states.toList().filter { it.packet.session == fresh.packet.session })
                assertEquals(Scx2Output.NEUTRAL, received.packet.output)
        } }
    }

    @Test fun releaseAllAndShortBackgroundRearmFreshNeutralButDisconnectCancelsRetry() {
        Peer().use { peer ->
            connect(peer); val first = peer.nextHello(); awaitConnected()
            main { UdpClient.beginOutputScope("held").sendCommand("RT:0.7"); ReleaseAllCoordinator.releaseAll() }
            val afterRelease = peer.nextHello(first.packet.session); awaitConnected()
            assertEquals(Scx2Output.NEUTRAL, peer.nextState(afterRelease.packet.session).packet.output)
            main { NetworkClient.appDidEnterBackground() }
            assertTrue(NetworkClient.bridgeConnectionRequested)
            main { NetworkClient.appDidEnterForeground() }
            val resumed = peer.nextHello(afterRelease.packet.session); awaitConnected()
            assertEquals(Scx2Output.NEUTRAL, peer.nextState(resumed.packet.session).packet.output)
            peer.available = false
            main { ReleaseAllCoordinator.releaseAll() }
            peer.nextHello(resumed.packet.session)
            main { NetworkClient.close(); ReleaseAllCoordinator.releaseAll(); NetworkClient.appDidEnterForeground() }
            SystemClock.sleep(850)
            assertFalse(NetworkClient.bridgeConnectionRequested)
            assertEquals(NetworkClient.ConnectionStatus.DISCONNECTED, NetworkClient.connectionStatus.value)
            peer.hello.clear(); SystemClock.sleep(1100)
            assertNull(peer.hello.poll())
        }
    }

    @Test fun transientFaultAutoRecoversAndUnsupportedKeysDoNotDisconnect() {
        Peer().use { peer ->
            connect(peer); val first = peer.nextHello(); awaitConnected()
            main {
                val unsupported = UdpClient.beginOutputScope("keyboard")
                unsupported.sendCommand("CTRL")
                assertEquals(NetworkClient.ConnectionStatus.CONNECTED, NetworkClient.connectionStatus.value)
                NetworkClient.updateSettings("bad host", 0, false)
                assertEquals(NetworkClient.ConnectionStatus.CONNECTED, NetworkClient.connectionStatus.value)
                assertTrue(NetworkClient.bridgeConnectionRequested)
            }
            peer.acknowledge = false
            SystemClock.sleep(900) // let the original acknowledged lease expire
            peer.acknowledge = true
            val recovered = peer.nextHello(first.packet.session); awaitConnected()
            assertEquals(Scx2Output.NEUTRAL, peer.nextState(recovered.packet.session).packet.output)
            main {
                NetworkClient.setOutputMode(NetworkClient.OutputMode.PRACTICE); NetworkClient.start()
                ReleaseAllCoordinator.releaseAll()
            }
            assertFalse(NetworkClient.bridgeConnectionRequested)
            peer.hello.clear(); SystemClock.sleep(1100); assertNull(peer.hello.poll())
        }
    }
    @Test fun persistedPermissionIsValidatedWithoutRestoringAnyHeldAction() {
        Peer().use { peer ->
            val prefs = instrumentation.targetContext.getSharedPreferences("bridge-intent-test", Context.MODE_PRIVATE)
            fun resetReader() {
                NetworkClient.close()
                NetworkClient.javaClass.getDeclaredField("bridgePersistenceInitialized").apply { isAccessible = true }
                    .setBoolean(NetworkClient, false)
                NetworkClient.javaClass.getDeclaredField("bridgeIntentPreferences").apply { isAccessible = true }
                    .set(NetworkClient, null)
                NetworkClient.setOutputMode(NetworkClient.OutputMode.SCX2, true)
                NetworkClient.updateSettings("127.0.0.1", peer.socket.localPort, false)
                prefs.edit().clear().commit()
            }
            try {
                main {
                    resetReader()
                    prefs.edit().putString("requested", "malformed").commit()
                    NetworkClient.initializeBridgeIntent(prefs)
                    assertFalse(NetworkClient.bridgeConnectionRequested)
                    assertFalse(prefs.contains("requested"))
                    resetReader()
                    prefs.edit().putBoolean("requested", true).putString("host", "127.0.0.1")
                        .putInt("port", peer.socket.localPort).putBoolean("pressure", true)
                        .putLong("lastPresentMillis", System.currentTimeMillis() - 300_000)
                        .putLong("lastPresentMonotonicMillis", 0).commit()
                    NetworkClient.initializeBridgeIntent(prefs); NetworkClient.appDidEnterForeground()
                    assertFalse(NetworkClient.bridgeConnectionRequested)
                    resetReader()
                    prefs.edit().putBoolean("requested", true).putString("host", "127.0.0.1")
                        .putInt("port", peer.socket.localPort).putBoolean("pressure", true)
                        .putLong("lastPresentMillis", System.currentTimeMillis())
                        .putLong("lastPresentMonotonicMillis", SystemClock.elapsedRealtime()).commit()
                    NetworkClient.initializeBridgeIntent(prefs)
                    assertTrue(NetworkClient.bridgeConnectionRequested)
                    assertEquals(NetworkClient.ConnectionStatus.DISCONNECTED, NetworkClient.connectionStatus.value)
                    assertEquals(Scx2Output.NEUTRAL, NetworkClient.localOutput.value.gamepad)
                }
                assertNull(peer.hello.poll(150, TimeUnit.MILLISECONDS))
                main { NetworkClient.appDidEnterForeground() }
                val hello = peer.nextHello(); awaitConnected()
                assertEquals(Scx2Output.NEUTRAL, peer.nextState(hello.packet.session).packet.output)
            } finally { main { resetReader(); NetworkClient.close() } }
        }
    }

    @Test fun usbTriggerGesturesKeepStoredPressuresAndIndependentOwnership() {
        Peer().use { peer ->
            connect(peer, BridgeTriggerMode.USB_BUTTONS)
            val hello = peer.nextHello(); awaitConnected()
            assertEquals(Scx2Revision.USB_BUTTONS, hello.packet.revision)
            assertTrue(hello.packet.usbButtonCapability)
            lateinit var aim: ControlView
            lateinit var secondLt: PhoneOutputScope
            val payload = "LT:0.5,RT:0.7"
            val model = Control("usb-aim", ControlType.BUTTON, 0f, 0f, 140f, 140f, payload)
            try {
                main {
                    val context = android.view.ContextThemeWrapper(instrumentation.targetContext, androidx.appcompat.R.style.Theme_AppCompat)
                    aim = ControlView(context, model).also { it.layout(0, 0, 140, 140) }
                    touch(aim, MotionEvent.ACTION_DOWN)
                    assertEquals(Scx2Output.pressure(0.5), NetworkClient.localOutput.value.gamepad.leftTrigger)
                    assertEquals(Scx2Output.pressure(0.7), NetworkClient.localOutput.value.gamepad.rightTrigger)
                }
                val both = peer.nextState(hello.packet.session) { it.leftTrigger == 65535 && it.rightTrigger == 65535 }
                assertEquals(Scx2Revision.USB_BUTTONS, both.packet.revision)
                main {
                    secondLt = UdpClient.beginOutputScope("other-lt")
                    secondLt.sendCommand("LT:0.000001")
                    secondLt.sendStick("L", 0.6f, 0f)
                    touch(aim, MotionEvent.ACTION_UP)
                    assertEquals(payload, model.payload)
                }
                peer.nextState(hello.packet.session) { it.leftTrigger == 65535 && it.rightTrigger == 0 && it.leftX > 0 }
                main { secondLt.sendCommand("LT:0.0"); secondLt.sendStick("L", 0f, 0f) }
                peer.nextState(hello.packet.session) { it == Scx2Output.NEUTRAL }
                assertEquals(NetworkClient.ConnectionStatus.CONNECTED, NetworkClient.connectionStatus.value)
            } finally { main { aim.releaseEverythingLocally(); SwipeManager.unregisterControl(aim) } }
        }
    }

    @Test fun cronusZenGesturesKeepPartialPressuresAndIndependentOwnership() {
        Peer().use { peer ->
            connect(peer, BridgeTriggerMode.CRONUS_ZEN)
            val hello = peer.nextHello(); awaitConnected()
            assertEquals(Scx2Revision.USB_PRESSURE, hello.packet.revision)
            assertTrue(hello.packet.usbPressureCapability)
            lateinit var aim: ControlView
            lateinit var secondLt: PhoneOutputScope
            val payload = "LT:0.5,RT:0.7"
            val model = Control("usb-aim", ControlType.BUTTON, 0f, 0f, 140f, 140f, payload)
            try {
                main {
                    val context = android.view.ContextThemeWrapper(instrumentation.targetContext, androidx.appcompat.R.style.Theme_AppCompat)
                    aim = ControlView(context, model).also { it.layout(0, 0, 140, 140) }
                    touch(aim, MotionEvent.ACTION_DOWN)
                    assertEquals(Scx2Output.pressure(0.5), NetworkClient.localOutput.value.gamepad.leftTrigger)
                    assertEquals(Scx2Output.pressure(0.7), NetworkClient.localOutput.value.gamepad.rightTrigger)
                }
                val both = peer.nextState(hello.packet.session) { it.leftTrigger == 32768 && it.rightTrigger == 45875 }
                assertEquals(Scx2Revision.USB_PRESSURE, both.packet.revision)
                main {
                    secondLt = UdpClient.beginOutputScope("other-lt")
                    secondLt.sendCommand("LT:0.25")
                    secondLt.sendStick("L", 0.6f, 0f)
                    touch(aim, MotionEvent.ACTION_UP)
                    assertEquals(payload, model.payload)
                }
                peer.nextState(hello.packet.session) { it.leftTrigger == 16384 && it.rightTrigger == 0 && it.leftX > 0 }
                main { secondLt.sendCommand("LT:0.0"); secondLt.sendStick("L", 0f, 0f) }
                peer.nextState(hello.packet.session) { it == Scx2Output.NEUTRAL }
                assertEquals(NetworkClient.ConnectionStatus.CONNECTED, NetworkClient.connectionStatus.value)
            } finally { main { aim.releaseEverythingLocally(); SwipeManager.unregisterControl(aim) } }
        }
    }

    @Test fun usbMacroUses200msDefaultAndReleaseAllRearmsWithoutReplaying() {
        Peer().use { peer ->
            connect(peer, BridgeTriggerMode.USB_BUTTONS)
            val first = peer.nextHello(); awaitConnected()
            lateinit var button: ControlView
            val model = Control("usb-macro", ControlType.BUTTON, 0f, 0f, 140f, 140f, "RT:0.1").apply { macroModeEnabled = true }
            try {
                main {
                    val context = android.view.ContextThemeWrapper(instrumentation.targetContext, androidx.appcompat.R.style.Theme_AppCompat)
                    button = ControlView(context, model).also { it.layout(0, 0, 140, 140) }
                    touch(button, MotionEvent.ACTION_DOWN); touch(button, MotionEvent.ACTION_UP)
                }
                peer.nextState(first.packet.session) { it.rightTrigger == 65535 }
                // The default completes after finger-up without changing the stored pressure.
                SystemClock.sleep(350)
                main { assertEquals(0, NetworkClient.localOutput.value.gamepad.rightTrigger); assertEquals("RT:0.1", model.payload) }
                peer.nextState(first.packet.session) { it.rightTrigger == 0 }
                main { touch(button, MotionEvent.ACTION_DOWN); touch(button, MotionEvent.ACTION_UP); ReleaseAllCoordinator.releaseAll() }
                val replacement = peer.nextHello(first.packet.session); awaitConnected()
                assertEquals(Scx2Revision.USB_BUTTONS, replacement.packet.revision)
                assertEquals(Scx2Output.NEUTRAL, peer.nextState(replacement.packet.session).packet.output)
                SystemClock.sleep(350)
                assertTrue(NetworkClient.bridgeConnectionRequested)
                assertEquals(Scx2Output.NEUTRAL, NetworkClient.localOutput.value.gamepad)
            } finally { main { button.releaseEverythingLocally(); SwipeManager.unregisterControl(button) } }
        }
    }

    @Test fun cronusZenMacroReleasesPartialPressureAndRearmsNeutral() {
        Peer().use { peer ->
            connect(peer, BridgeTriggerMode.CRONUS_ZEN)
            val first = peer.nextHello(); awaitConnected()
            lateinit var button: ControlView
            val model = Control("usb-macro", ControlType.BUTTON, 0f, 0f, 140f, 140f, "RT:0.1").apply { macroModeEnabled = true }
            try {
                main {
                    val context = android.view.ContextThemeWrapper(instrumentation.targetContext, androidx.appcompat.R.style.Theme_AppCompat)
                    button = ControlView(context, model).also { it.layout(0, 0, 140, 140) }
                    touch(button, MotionEvent.ACTION_DOWN); touch(button, MotionEvent.ACTION_UP)
                }
                peer.nextState(first.packet.session) { it.rightTrigger == 6554 }
                // The default completes after finger-up without changing the stored pressure.
                SystemClock.sleep(350)
                main { assertEquals(0, NetworkClient.localOutput.value.gamepad.rightTrigger); assertEquals("RT:0.1", model.payload) }
                peer.nextState(first.packet.session) { it.rightTrigger == 0 }
                main { touch(button, MotionEvent.ACTION_DOWN); touch(button, MotionEvent.ACTION_UP); ReleaseAllCoordinator.releaseAll() }
                val replacement = peer.nextHello(first.packet.session); awaitConnected()
                assertEquals(Scx2Revision.USB_PRESSURE, replacement.packet.revision)
                assertEquals(Scx2Output.NEUTRAL, peer.nextState(replacement.packet.session).packet.output)
                SystemClock.sleep(350)
                assertTrue(NetworkClient.bridgeConnectionRequested)
                assertEquals(Scx2Output.NEUTRAL, NetworkClient.localOutput.value.gamepad)
            } finally { main { button.releaseEverythingLocally(); SwipeManager.unregisterControl(button) } }
        }
    }

    @Test fun usbReconnectPermissionRestoresModeAndRejectsUnknownOrConflictingRecords() {
        Peer().use { peer ->
            val prefs = instrumentation.targetContext.getSharedPreferences("usb-bridge-intent-test", Context.MODE_PRIVATE)
            fun resetReader() {
                NetworkClient.close()
                NetworkClient.javaClass.getDeclaredField("bridgePersistenceInitialized").apply { isAccessible = true }.setBoolean(NetworkClient, false)
                NetworkClient.javaClass.getDeclaredField("bridgeIntentPreferences").apply { isAccessible = true }.set(NetworkClient, null)
                NetworkClient.setOutputMode(NetworkClient.OutputMode.SCX2, BridgeTriggerMode.USB_BUTTONS)
                NetworkClient.updateSettings("127.0.0.1", peer.socket.localPort, false)
                prefs.edit().clear().commit()
            }
            fun saved(mode: String?, pressure: Boolean) {
                prefs.edit().putBoolean("requested", true).putString("host", "127.0.0.1").putInt("port", peer.socket.localPort)
                    .putBoolean("pressure", pressure).putString("triggerMode", mode)
                    .putLong("lastPresentMillis", System.currentTimeMillis())
                    .putLong("lastPresentMonotonicMillis", SystemClock.elapsedRealtime()).commit()
            }
            try {
                main {
                    for ((mode, pressure) in listOf("future" to false, "USB_BUTTONS" to true, "ADJUSTABLE" to false, null to false)) {
                        resetReader(); saved(mode, pressure); NetworkClient.initializeBridgeIntent(prefs)
                        assertFalse(NetworkClient.bridgeConnectionRequested)
                    }
                    resetReader(); saved("USB_BUTTONS", false); NetworkClient.initializeBridgeIntent(prefs)
                    assertTrue(NetworkClient.bridgeConnectionRequested)
                    assertEquals(Scx2Output.NEUTRAL, NetworkClient.localOutput.value.gamepad)
                    NetworkClient.appDidEnterForeground()
                }
                val hello = peer.nextHello(); awaitConnected()
                assertEquals(Scx2Revision.USB_BUTTONS, hello.packet.revision)
                assertEquals(Scx2Output.NEUTRAL, peer.nextState(hello.packet.session).packet.output)
                main { NetworkClient.appDidEnterBackground(); NetworkClient.appDidEnterForeground() }
                val resumed = peer.nextHello(hello.packet.session); awaitConnected()
                assertEquals(Scx2Revision.USB_BUTTONS, resumed.packet.revision)
                assertEquals(Scx2Output.NEUTRAL, peer.nextState(resumed.packet.session).packet.output)
                assertEquals("USB_BUTTONS", prefs.getString("triggerMode", null))
            } finally { main { resetReader(); NetworkClient.close() } }
        }
    }

    @Test fun cronusZenReconnectRestoresModeAndRejectsConflictingRecords() {
        Peer().use { peer ->
            val prefs = instrumentation.targetContext.getSharedPreferences("usb-bridge-intent-test", Context.MODE_PRIVATE)
            fun resetReader() {
                NetworkClient.close()
                NetworkClient.javaClass.getDeclaredField("bridgePersistenceInitialized").apply { isAccessible = true }.setBoolean(NetworkClient, false)
                NetworkClient.javaClass.getDeclaredField("bridgeIntentPreferences").apply { isAccessible = true }.set(NetworkClient, null)
                NetworkClient.setOutputMode(NetworkClient.OutputMode.SCX2, BridgeTriggerMode.CRONUS_ZEN)
                NetworkClient.updateSettings("127.0.0.1", peer.socket.localPort, false)
                prefs.edit().clear().commit()
            }
            fun saved(mode: String?, pressure: Boolean) {
                prefs.edit().putBoolean("requested", true).putString("host", "127.0.0.1").putInt("port", peer.socket.localPort)
                    .putBoolean("pressure", pressure).putString("triggerMode", mode)
                    .putLong("lastPresentMillis", System.currentTimeMillis())
                    .putLong("lastPresentMonotonicMillis", SystemClock.elapsedRealtime()).commit()
            }
            try {
                main {
                    for ((mode, pressure) in listOf("future" to false, "CRONUS_ZEN" to true, "ADJUSTABLE" to false, null to false)) {
                        resetReader(); saved(mode, pressure); NetworkClient.initializeBridgeIntent(prefs)
                        assertFalse(NetworkClient.bridgeConnectionRequested)
                    }
                    resetReader(); saved("CRONUS_ZEN", false); NetworkClient.initializeBridgeIntent(prefs)
                    assertTrue(NetworkClient.bridgeConnectionRequested)
                    assertEquals(Scx2Output.NEUTRAL, NetworkClient.localOutput.value.gamepad)
                    NetworkClient.appDidEnterForeground()
                }
                val hello = peer.nextHello(); awaitConnected()
                assertEquals(Scx2Revision.USB_PRESSURE, hello.packet.revision)
                assertEquals(Scx2Output.NEUTRAL, peer.nextState(hello.packet.session).packet.output)
                main { NetworkClient.appDidEnterBackground(); NetworkClient.appDidEnterForeground() }
                val resumed = peer.nextHello(hello.packet.session); awaitConnected()
                assertEquals(Scx2Revision.USB_PRESSURE, resumed.packet.revision)
                assertEquals(Scx2Output.NEUTRAL, peer.nextState(resumed.packet.session).packet.output)
                assertEquals("CRONUS_ZEN", prefs.getString("triggerMode", null))
            } finally { main { resetReader(); NetworkClient.close() } }
        }
    }

    @Test fun activityLoadsOldTriggerChoicesWithoutRewritingAndRejectsBadNewMode() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val prefs = activity.getSharedPreferences("network", Context.MODE_PRIVATE)
                try {
                    for ((saved, pressure, expected) in listOf(
                        Triple(null, false, BridgeTriggerMode.OFF), Triple(null, true, BridgeTriggerMode.ADJUSTABLE),
                        Triple("USB_BUTTONS", false, BridgeTriggerMode.USB_BUTTONS), Triple("ADJUSTABLE", true, BridgeTriggerMode.ADJUSTABLE),
                        Triple("CRONUS_ZEN", false, BridgeTriggerMode.CRONUS_ZEN))) {
                        prefs.edit().putString("outputMode", "SCX2").putBoolean("adjustableTriggers", pressure)
                            .putString("triggerMode", saved).commit()
                        val original = prefs.all.toMap()
                        activity.javaClass.getDeclaredMethod("loadNetworkSettings").apply { isAccessible = true }.invoke(activity)
                        assertEquals(NetworkClient.OutputMode.SCX2, NetworkClient.outputMode)
                        assertEquals(expected, NetworkClient.triggerMode)
                        assertEquals(original, prefs.all)
                    }
                    for ((saved, pressure) in listOf("future" to false, "USB_BUTTONS" to true, "ADJUSTABLE" to false)) {
                        prefs.edit().putString("outputMode", "SCX2").putBoolean("adjustableTriggers", pressure)
                            .putString("triggerMode", saved).commit()
                        val original = prefs.all.toMap()
                        activity.javaClass.getDeclaredMethod("loadNetworkSettings").apply { isAccessible = true }.invoke(activity)
                        assertEquals(NetworkClient.OutputMode.PRACTICE, NetworkClient.outputMode)
                        assertFalse(NetworkClient.bridgeConnectionRequested)
                        assertEquals(original, prefs.all)
                    }
                } finally {
                    prefs.edit().putString("outputMode", "PRACTICE").putBoolean("adjustableTriggers", false).remove("triggerMode").commit()
                    NetworkClient.setOutputMode(NetworkClient.OutputMode.PRACTICE)
                }
            }
        }
    }

}
