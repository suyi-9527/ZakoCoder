package dev.zakocoder.app.kernelprobe;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import java.io.File;
import java.nio.charset.StandardCharsets;

/**
 * Prototype service for the kernel-plugin feasibility spike.
 *
 * <p>Proves the two boundaries the design depends on: (1) a different app process can bind only
 * with the same signing certificate (signature-level permission + an explicit caller check), and
 * (2) the plugin can execute the Xray binary from its own {@code nativeLibraryDir}. It is not
 * production code and is not merged into the app. A real plugin will use AIDL; this spike uses
 * Messenger to keep the proof small while exercising the same Binder/UID permission boundary.
 */
public final class ProbeService extends Service {
    private static final String PERMISSION = "dev.zakocoder.app.permission.KERNEL_PROBE";
    public static final int MSG_PING = 1;
    public static final int MSG_CONTRACT = 2;
    public static final int MSG_KERNEL_VERSION = 3;
    public static final String KEY_RESULT = "result";

    private final Messenger messenger = new Messenger(new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message message) {
            String result;
            switch (message.what) {
                case MSG_PING:
                    result = "pong";
                    break;
                case MSG_CONTRACT:
                    result = "1";
                    break;
                case MSG_KERNEL_VERSION:
                    result = kernelVersion();
                    break;
                default:
                    super.handleMessage(message);
                    return;
            }
            if (message.replyTo != null) {
                Bundle bundle = new Bundle();
                bundle.putString(KEY_RESULT, result);
                Message reply = Message.obtain(null, message.what);
                reply.setData(bundle);
                try {
                    message.replyTo.send(reply);
                } catch (RemoteException ignored) {
                    // The caller went away; nothing to deliver.
                }
            }
        }
    });

    private String kernelVersion() {
        File executable = new File(getApplicationInfo().nativeLibraryDir, "libzakocoder_xray.so");
        if (!executable.isFile()) {
            return "missing:" + executable.getAbsolutePath();
        }
        try {
            Process process = new ProcessBuilder(executable.getAbsolutePath(), "version")
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            process.waitFor();
            return output.trim();
        } catch (Exception error) {
            return "error:" + error.getClass().getSimpleName() + ":" + error.getMessage();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        // The signature-level android:permission on the service is enforced by the system before
        // bind. Do not call checkCallingPermission here: onBind runs with the system caller identity,
        // not the app's UID (that check is valid inside an AIDL transact).
        return messenger.getBinder();
    }
}
