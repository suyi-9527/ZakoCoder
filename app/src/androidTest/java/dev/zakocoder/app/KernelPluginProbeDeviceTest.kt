package dev.zakocoder.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Prototype-only spike for the kernel-plugin design (not production code).
 *
 * Verifies three facts the design depends on:
 * 1. the app's own `nativeLibraryDir` executable runs;
 * 2. the same executable copied to `filesDir` cannot be executed on API 29+ (W^X);
 * 3. a separate APK signed with the same certificate can bind a signature-permission service and
 *    that service can execute Xray from *its own* nativeLibraryDir.
 *
 * Requires the prototype `plugin-probe` APK to be installed:
 * `adb install -r plugin-probe/build/outputs/apk/debug/plugin-probe-debug.apk`.
 */
class KernelPluginProbeDeviceTest {
    private fun exec(file: File): String {
        val process = ProcessBuilder(file.absolutePath, "version").redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return output
    }

    @Test
    fun executableBoundaryAndPluginBinderWork() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val nativeExecutable = File(context.applicationInfo.nativeLibraryDir, "libzakocoder_xray.so")
        assumeTrue("Probe needs the kernel packaged in the app APK", nativeExecutable.isFile)

        // 1) Executing from the app's own nativeLibraryDir is allowed.
        val nativeOutput = exec(nativeExecutable)
        assertTrue("nativeLibraryDir exec failed: $nativeOutput", nativeOutput.contains("Xray"))

        // 2) The same ELF copied into filesDir is blocked on API 29+ (W^X execute restriction).
        val copy = File(context.filesDir, "probe-xray")
        nativeExecutable.copyTo(copy, overwrite = true)
        copy.setExecutable(true)
        val blocked = runCatching { exec(copy) }
        assertTrue(
            "filesDir exec unexpectedly succeeded or failed for the wrong reason: $blocked",
            blocked.isFailure ||
                blocked.getOrThrow().contains("Permission denied") ||
                blocked.getOrThrow().contains("not executable"),
        )

        // 3) A same-signature plugin APK can bind this app and execute Xray in its own process.
        val binder = bindPlugin(context)
        assertNotNull("plugin service not reachable (same-signature permission?)", binder)
        val serviceBinder = requireNotNull(binder)
        val replies = ArrayBlockingQueue<String>(4)
        val client = Messenger(Handler(Looper.getMainLooper()) { message ->
            message.data?.getString("result")?.let(replies::offer)
            false
        })
        val plugin = Messenger(serviceBinder)
        fun request(what: Int): String {
            val message = Message.obtain(null, what)
            message.replyTo = client
            plugin.send(message)
            return replies.poll(10, TimeUnit.SECONDS) ?: "timeout"
        }
        assertEquals("pong", request(1))
        assertEquals("1", request(2))
        val pluginVersion = request(3)
        assertTrue("plugin nativeLibraryDir exec failed: $pluginVersion", pluginVersion.contains("Xray"))
    }

    private fun bindPlugin(context: Context): IBinder? {
        val latch = java.util.concurrent.CountDownLatch(1)
        var binder: IBinder? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                binder = service
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit

            override fun onNullBinding(name: ComponentName?) {
                latch.countDown()
            }
        }
        val intent = Intent().setComponent(
            ComponentName("dev.zakocoder.app.kernelprobe", "dev.zakocoder.app.kernelprobe.ProbeService"),
        )
        val bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        if (!bound) return null
        latch.await(10, TimeUnit.SECONDS)
        runCatching { context.unbindService(connection) }
        return binder
    }
}
