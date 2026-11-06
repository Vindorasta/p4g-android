package io.github.p4grecomp;

import android.app.AlertDialog;
import android.app.NativeActivity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.view.InputDevice;
import android.view.KeyEvent;
import java.io.File;

/** The runtime lives in :game; its native exit must not kill the launcher. */
public class GameActivity extends NativeActivity {
    private AlertDialog exitDialog;
    private Object backCallback;

    @Override protected void onCreate(Bundle state) {
        // File descriptors and environment variables cannot be passed from
        // the launcher's process. Open the game here, before native loading.
        try {
            Uri uri = getIntent().getData();
            if (uri == null) throw new java.io.IOException("Select the game again.");
            ParcelFileDescriptor descriptor = getContentResolver().openFileDescriptor(uri, "r");
            if (descriptor == null) throw new java.io.IOException("Could not open the game.");
            File keys = Launcher.keysFile(this, "prod.keys");
            if (!keys.isFile() || keys.length() == 0) {
                descriptor.close();
                throw new java.io.IOException("Select prod.keys again.");
            }
            Os.setenv("NX_GAME_FD", String.valueOf(descriptor.detachFd()), true);
            String name = getIntent().getStringExtra(ImportActivity.EXTRA_NAME);
            Os.setenv("NX_GAME_NAME", name == null ? "P4G.nsp" : name, true);
            Os.setenv("NX_KEYS", keys.getPath(), true);
            File title = Launcher.keysFile(this, "title.keys");
            if (title.isFile()) Os.setenv("NX_TITLE_KEYS", title.getPath(), true);
            // File-open tracing is opt-in through NX_TRACE_FS=1 in config.txt.
        } catch (Exception e) {
            startActivity(menuIntent().putExtra("game_error", String.valueOf(e.getMessage())));
            android.os.Process.killProcess(android.os.Process.myPid());
            return;
        }
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 33) backCallback = Api33.register(this);
    }

    private Intent menuIntent() {
        return new Intent(this, Launcher.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    /** Also called by the native input queue when it receives phone Back. */
    public void confirmExit() {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed() || (exitDialog != null && exitDialog.isShowing())) return;
            exitDialog = new AlertDialog.Builder(this)
                .setTitle("Exit the game?")
                .setMessage("Do you want to exit the game and return to the main menu? Unsaved progress will be lost.")
                .setNegativeButton("Keep playing", null)
                .setPositiveButton("Exit", (dialog, which) -> {
                    startActivity(menuIntent());
                    // NativeActivity destruction stops the runtime. Since it is
                    // in :game, the launcher's process and menu stay alive.
                    finish();
                }).create();
            exitDialog.show();
        });
    }
    @Override public void onBackPressed() { confirmExit(); }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int source = event.getSource();
        boolean controller = (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                             (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && !controller) {
            if (event.getAction() == KeyEvent.ACTION_UP) confirmExit();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
    @Override protected void onDestroy() {
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) Api33.unregister(this, backCallback);
        if (exitDialog != null) exitDialog.dismiss();
        super.onDestroy();
    }
    private static final class Api33 {
        static Object register(GameActivity activity) {
            android.window.OnBackInvokedCallback callback = activity::confirmExit;
            activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
            return callback;
        }
        static void unregister(GameActivity activity, Object callback) {
            activity.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                (android.window.OnBackInvokedCallback) callback);
        }
    }
}
