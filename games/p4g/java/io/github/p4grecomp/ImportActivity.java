package io.github.p4grecomp;

import android.app.Activity;
import android.app.AlertDialog;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.graphics.Typeface;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.IOException;

/** Preparation only: completion returns directly to the launcher. */
public class ImportActivity extends Activity {
    static final String EXTRA_URI = "uri", EXTRA_NAME = "name";
    TextView status;
    ProgressBar bar;
    volatile boolean running;
    final Handler ui = new Handler(Looper.getMainLooper());
    int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        P4gTheme.apply(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(P4gTheme.BACKGROUND);
        root.setPadding(dp(32), dp(32), dp(32), dp(32));
        TextView title = new TextView(this);
        title.setText("Preparando P4G");
        title.setTextColor(P4gTheme.YELLOW);
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(P4gTheme.YELLOW));
        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(24)));
        status = new TextView(this);
        status.setTextColor(android.graphics.Color.WHITE);
        status.setTextSize(16);
        status.setPadding(0, dp(16), 0, 0);
        root.addView(status);
        setContentView(root);
        start();
    }

    @Override public void onBackPressed() {
        if (!running) { setResult(RESULT_CANCELED); finish(); }
    }
    void fail(String message) {
        status.setText(message);
        new AlertDialog.Builder(this).setTitle("Preparação não concluída").setMessage(message)
            .setPositiveButton("OK", (d, w) -> { setResult(RESULT_CANCELED); finish(); })
            .setOnCancelListener(d -> { setResult(RESULT_CANCELED); finish(); }).show();
    }
    @Override protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
    void start() {
        String uriString =
            getIntent().getStringExtra(EXTRA_URI);

        if (uriString == null || uriString.isEmpty()) {
            status.setText(
                "Nenhum arquivo de jogo selecionado."
            );
            fail("Nenhum arquivo de jogo selecionado.");
            return;
        }

        final Uri uri = Uri.parse(uriString);

        final String name =
            getIntent().getStringExtra(EXTRA_NAME);

        final File keys =
            Launcher.keysFile(this, "prod.keys");

        final File titleKeys =
            Launcher.keysFile(this, "title.keys");

        final File data = Launcher.dataDir();

        final File work =
            new File(getCacheDir(), "import");

        running = true;


        status.setText("Abrindo o jogo...");

        new Thread(new Runnable() {
            @Override
            public void run() {
                int rc;
                String error = "";

                try (
                    ParcelFileDescriptor pfd =
                        getContentResolver()
                            .openFileDescriptor(uri, "r")
                ) {
                    if (pfd == null) {
                        throw new IOException(
                            "Nao foi possivel abrir o NSP/XCI"
                        );
                    }

                    int jobs = Math.max(
                        2,
                        Runtime.getRuntime()
                            .availableProcessors()
                    );

                    // Install the runtime hash pack separately from numbered GXP output.
                    BundledShaders.install(ImportActivity.this);
                    rc = Importer.run(
                        pfd.getFd(),
                        name == null ? "" : name,
                        keys.getPath(),
                        titleKeys.exists()
                            ? titleKeys.getPath()
                            : "",
                        data.getPath(),
                        work.getPath(),
                        jobs
                    );

                    if (rc != 0) {
                        error = Importer.error();
                    }

                } catch (Exception e) {
                    rc = 1;
                    error = String.valueOf(
                        e.getMessage()
                    );
                }

                final int result = rc;
                final String message = error;

                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        running = false;


                        if (isFinishing() || isDestroyed()) return;
                        if (result == 0) {
                            bar.setProgress(1000);
                            status.setText("Preparação concluída.");
                            Toast.makeText(ImportActivity.this, "Preparação concluída", Toast.LENGTH_LONG).show();
                            setResult(RESULT_OK);
                            finish();
                        } else {
                            fail("Preparação incompleta:\n\n" + message);
                        }
                    }
                });
            }
        }).start();

        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!running) {
                    return;
                }

                int[] p = Importer.progress();

                int phase = p[0];
                int done = p[1];
                int total = Math.max(1, p[2]);

                double share =
                    (double) Math.min(done, total) / total;

                int value;

                if (phase <= 1) {
                    value = 20;
                } else if (phase == 2) {
                    value = 50 + (int) (200 * share);
                } else if (phase == 3) {
                    value = 250 + (int) (730 * share);
                } else {
                    value = 990;
                }

                bar.setProgress(value);

                if (phase == 2) {
                    status.setText(
                        "Extraindo shaders (" +
                        done + " de " + total + ")..."
                    );
                } else if (phase == 3) {
                    status.setText(
                        "Processando shaders (" +
                        done + " de " + total + ")..."
                    );
                } else if (phase == 4) {
                    status.setText("Finalizando...");
                }

                ui.postDelayed(this, 500);
            }
        }, 500);
    }
}
