package io.github.p4grecomp;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Locale;
import java.util.Map;

/** One sample per second, off the render/UI threads; no metric logs or overlay permission. */
final class PerformanceOverlay {
    private final GameActivity activity;
    private final boolean fps, cpu, gpu, ram;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private HandlerThread thread;
    private Handler worker;
    private TextView panel;
    private volatile int generation;
    private long lastTime, lastCpu, lastFrames;
    private boolean gpuUnavailable;

    PerformanceOverlay(GameActivity activity, Map<String, String> config) {
        this.activity = activity;
        fps = "1".equals(config.get("NX_SHOW_FPS"));
        cpu = "1".equals(config.get("NX_OVERLAY_CPU"));
        gpu = "1".equals(config.get("NX_OVERLAY_GPU"));
        ram = "1".equals(config.get("NX_OVERLAY_RAM"));
        if (!(fps || cpu || gpu || ram)) return;
        panel = new TextView(activity);
        panel.setTextSize(12);
        panel.setTypeface(android.graphics.Typeface.MONOSPACE);
        panel.setTextColor(P4gTheme.YELLOW);
        panel.setBackgroundColor(0xB3141414);
        int pad = P4gTheme.dp(activity, 6);
        panel.setPadding(pad, pad, pad, pad);
        panel.setClickable(false);
        panel.setFocusable(false);
        panel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        position.topMargin = pad;
        activity.addContentView(panel, position);
    }

    void start() {
        if (panel == null || thread != null) return;
        final int token = ++generation;
        thread = new HandlerThread("P4G-overlay", android.os.Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        worker = new Handler(thread.getLooper());
        final Handler sampler = worker;
        panel.setVisibility(View.VISIBLE);
        worker.post(new Runnable() {
            private boolean first = true;
            @Override public void run() {
                if (token != generation) return;
                long now = SystemClock.elapsedRealtime();
                long used = cpu ? android.os.Process.getElapsedCpuTime() : 0;
                long frames = -1;
                if (fps) try { frames = GameActivity.nativeFrames(); } catch (UnsatisfiedLinkError ignored) { }
                long elapsed = now - lastTime;
                StringBuilder text = new StringBuilder();
                if (fps) append(text, first || elapsed <= 0 || frames < 0 || lastFrames < 0 ? "FPS: —" :
                        String.format(Locale.US, "FPS: %.1f", Math.max(0L, frames - lastFrames) * 1000.0 / elapsed));
                if (cpu) append(text, first || elapsed <= 0 ? "CPU app: —" :
                        String.format(Locale.US, "CPU app: %.0f%%", Math.max(0L, used - lastCpu) * 100.0 / elapsed));
                if (gpu) append(text, "GPU: " + gpuUsage());
                if (ram) append(text, "RAM app: " + residentMemory());
                lastTime = now; lastCpu = used; lastFrames = frames; first = false;
                String label = text.toString();
                ui.post(() -> { if (token == generation && panel != null) panel.setText(label); });
                if (token == generation) sampler.postDelayed(this, 1000);
            }
        });
    }

    private static void append(StringBuilder text, String line) {
        if (text.length() != 0) text.append('\n');
        text.append(line);
    }

    private static String read(String path) throws Exception {
        try (BufferedReader in = new BufferedReader(new FileReader(path))) {
            String line = in.readLine();
            if (line == null) throw new java.io.IOException("Empty counter");
            return line.trim();
        }
    }

    private String gpuUsage() {
        if (gpuUnavailable) return "N/A";
        try {
            // KGSL supplies busy/total time for its sampling interval, not per-process usage.
            String[] values = read("/sys/class/kgsl/kgsl-3d0/gpubusy").split("\\s+");
            long busy = Long.parseLong(values[0]), total = Long.parseLong(values[1]);
            if (total <= 0 || busy < 0 || busy > total) return "N/A";
            return String.format(Locale.US, "%.0f%%", busy * 100.0 / total);
        } catch (Exception ignored) { gpuUnavailable = true; return "N/A"; }
    }

    private static String residentMemory() {
        try {
            String[] fields = read("/proc/self/statm").split("\\s+");
            long pages = Long.parseLong(fields[1]);
            long pageSize = Os.sysconf(OsConstants._SC_PAGESIZE);
            if (pageSize <= 0 || pages < 0) return "N/A";
            return String.format(Locale.US, "%.0f MiB", pages * (double) pageSize / 1048576.0);
        } catch (Exception ignored) { return "N/A"; }
    }

    void stop() {
        ++generation;
        if (worker != null) worker.removeCallbacksAndMessages(null);
        if (thread != null) thread.quitSafely();
        worker = null; thread = null;
        if (panel != null) panel.setVisibility(View.GONE);
    }

    void close() { stop(); ui.removeCallbacksAndMessages(null); }
}
