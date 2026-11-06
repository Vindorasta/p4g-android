package io.github.p4grecomp;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.view.View;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Locale;
import java.util.Map;

/** One sample per second, off the render/UI threads; no metric logs or overlay permission. */
final class PerformanceOverlay {
    private final GameActivity activity;
    private final boolean fps, cpu, gpu, ram;
    private HandlerThread thread;
    private Handler worker;
    private SurfaceView panel;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint background = new Paint();
    private volatile boolean surfaceReady;
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
        panel = new SurfaceView(activity);
        // NativeActivity gives its normal window surface directly to Vulkan. A TextView in that
        // window can therefore be rendered behind the game. This transparent SurfaceView is a
        // separate Android compositor layer and stays above the Vulkan surface.
        panel.setZOrderOnTop(true);
        panel.getHolder().setFormat(PixelFormat.TRANSLUCENT);
        panel.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) { surfaceReady = true; }
            @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { }
            @Override public void surfaceDestroyed(SurfaceHolder holder) { surfaceReady = false; }
        });
        panel.setClickable(false);
        panel.setFocusable(false);
        panel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        paint.setTypeface(android.graphics.Typeface.MONOSPACE);
        paint.setTextSize(P4gTheme.dp(activity, 12));
        paint.setColor(P4gTheme.YELLOW);
        background.setColor(0xB3141414);
        activity.addContentView(panel, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
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
                draw(label, token);
                if (token == generation) sampler.postDelayed(this, 1000);
            }
        });
    }

    private void draw(String text, int token) {
        if (token != generation || !surfaceReady || panel == null) return;
        SurfaceHolder holder = panel.getHolder();
        Canvas canvas = null;
        try {
            canvas = holder.lockCanvas();
            if (canvas == null) return;
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            String[] lines = text.split("\\n");
            float pad = P4gTheme.dp(activity, 7), lineHeight = paint.getTextSize() * 1.25f;
            float width = 0;
            for (String line : lines) width = Math.max(width, paint.measureText(line));
            float left = (canvas.getWidth() - width) * 0.5f - pad;
            float top = pad;
            canvas.drawRoundRect(left, top, left + width + pad * 2,
                    top + lines.length * lineHeight + pad * 1.4f, pad, pad, background);
            float y = top + pad + paint.getTextSize();
            for (String line : lines) { canvas.drawText(line, left + pad, y, paint); y += lineHeight; }
        } catch (IllegalArgumentException ignored) {
        } finally {
            if (canvas != null) holder.unlockCanvasAndPost(canvas);
        }
    }

    private void clear() {
        if (!surfaceReady || panel == null) return;
        Canvas canvas = null;
        try {
            canvas = panel.getHolder().lockCanvas();
            if (canvas != null) canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        } catch (IllegalArgumentException ignored) {
        } finally {
            if (canvas != null) panel.getHolder().unlockCanvasAndPost(canvas);
        }
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
        clear();
        if (panel != null) panel.setVisibility(View.GONE);
    }

    void close() { stop(); }
}
