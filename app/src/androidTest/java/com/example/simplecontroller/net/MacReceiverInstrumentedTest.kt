package com.example.simplecontroller.net

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Optional real UDP acceptance check with receiver/mac_recording_peer.py.
 * Run with macReceiverHost=10.0.2.2 and macReceiverPort=42734 on the emulator.
 * The peer records mapped outputs without posting any input to the host desktop.
 */
@RunWith(AndroidJUnit4::class)
class MacReceiverInstrumentedTest {
    private fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private fun waitFor(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return
            SystemClock.sleep(20)
        }
        fail("$message; status=${NetworkClient.connectionStatus.value}; error=${NetworkClient.lastErrorMessage.value}")
    }
    @After fun cleanup() = main {
        NetworkClient.close(); UdpClient.close()
        NetworkClient.setOutputMode(NetworkClient.OutputMode.PRACTICE)
        NetworkClient.appDidEnterForeground()
    }
    @Test fun phoneInputsReachMacMappingAndReleaseWithoutDisconnecting() {
        val arguments = InstrumentationRegistry.getArguments()
        val host = arguments.getString("macReceiverHost").orEmpty()
        assumeTrue("Start the non-injecting Mac recording peer and pass macReceiverHost to run this check", host.isNotBlank())
        val port = arguments.getString("macReceiverPort")?.toInt() ?: 42734
        main {
            NetworkClient.close(); UdpClient.close()
            NetworkClient.setOutputMode(NetworkClient.OutputMode.PRACTICE)
            NetworkClient.setOutputMode(NetworkClient.OutputMode.WINDOWS)
            NetworkClient.setPlayerRole(NetworkClient.PlayerRole.PLAYER1)
            NetworkClient.appDidEnterForeground()
            NetworkClient.updateSettings(host, port, false)
            UdpClient.initialize(host, port)
        }
        SystemClock.sleep(150)
        main { NetworkClient.start() }
        waitFor("Mac receiver never confirmed protected activation") { UdpClient.floatingSupported && !UdpClient.activationPending }
        assertNull(NetworkClient.lastErrorMessage.value)
        lateinit var scope: PhoneOutputScope
        main {
            scope = UdpClient.beginOutputScope("mac-acceptance")
            assertTrue(scope.sendCommand("X360A_HOLD"))
            assertTrue(scope.sendCommand("LT:0.5"))
            assertTrue(scope.sendCommand("RT:0.7"))
            assertTrue(scope.sendStick("L", 0.6f, 0f))
            assertTrue(scope.sendStick("R", 0.6f, 0f))
        }
        SystemClock.sleep(500)
        main { scope.releaseAllClaims() }
        SystemClock.sleep(300)
        main {
            assertTrue(scope.sendKey("W", true))
            assertTrue(scope.sendMouseDelta(12f, -6f))
            assertTrue(scope.sendScroll(2f))
        }
        SystemClock.sleep(300)
        repeat(4) {
            main {
                assertTrue(scope.sendMouseDelta(0.01f, 0.01f))
                assertTrue(scope.sendScroll(0.25f))
            }
            SystemClock.sleep(30)
        }
        main { UdpClient.prepareReleaseAll(); UdpClient.sendReleaseAll {} }
        waitFor("Release All did not restore neutral PC connection") { UdpClient.floatingSupported && !UdpClient.activationPending }
        SystemClock.sleep(400)
        assertEquals(NetworkClient.ConnectionStatus.CONNECTED, NetworkClient.connectionStatus.value)
        main { assertTrue(UdpClient.beginOutputScope("mac-background").sendCommand("X360B_HOLD")) }
        SystemClock.sleep(250)
        main { NetworkClient.appDidEnterBackground() }
        assertFalse(UdpClient.floatingSupported)
        SystemClock.sleep(3500) // Receiver lease must expire while the phone is inactive.
        main { NetworkClient.appDidEnterForeground() }
        waitFor("Foreground did not establish a fresh neutral connection") { UdpClient.floatingSupported && !UdpClient.activationPending }
        assertEquals(NetworkClient.ConnectionStatus.CONNECTED, NetworkClient.connectionStatus.value)
    }
}
