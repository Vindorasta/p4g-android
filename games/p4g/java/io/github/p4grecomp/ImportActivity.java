
package io.github.p4grecomp;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;

/**
 * Persona 4 Golden Android
 *
 * Importa o jogo e extrai shaders GXP.
 *
 * Enquanto a traducao GXP para SPIR-V nao estiver
 * implementada, permite exportar um relatorio
 * tecnico dos cabecalhos GXP.
 *
 * Nao utiliza o tradutor Maxwell do P5R.
 */
public class ImportActivity extends Activity {

    static final String EXTRA_URI = "uri";
    static final String EXTRA_NAME = "name";

    private static final int REQUEST_EXPORT_GXP = 701;

    TextView status;
    ProgressBar bar;
    Button back;
    Button exportGxp;

    final Handler ui = new Handler(Looper.getMainLooper());

    volatile boolean running;

    File gxpReport;

    String t(String spanish, String english) {
        return Lang.t(spanish, english);
    }

    int dp(int v) {
        return Math.round(
            v * getResources().getDisplayMetrics().density
        );
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN |
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(20, 4, 6));

        int pad = dp(32);
        root.setPadding(pad, pad, pad, pad);

        // Titulo
        TextView title = new TextView(this);
        title.setText("Preparando Persona 4 Golden");
        title.setTextColor(Color.rgb(230, 0, 18));
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        // Descricao
        TextView note = new TextView(this);
        note.setText(
            "Extraindo e analisando os shaders GXP " +
            "do jogo. A traducao para Vulkan ainda " +
            "esta em desenvolvimento."
        );
        note.setTextColor(Color.rgb(200, 200, 200));
        note.setTextSize(15);
        note.setPadding(
            0, dp(8), 0, dp(20)
        );
        root.addView(note);

        // Barra de progresso
        bar = new ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal
        );
        bar.setMax(1000);

        root.addView(
            bar,
            new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(24)
            )
        );

        // Mensagem de status
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(16);
        status.setPadding(
            0, dp(12), 0, dp(12)
        );
        root.addView(status);

        // Botao de exportacao
        exportGxp = new Button(this);
        exportGxp.setAllCaps(false);
        exportGxp.setTextSize(17);
        exportGxp.setText("Exportar diagnostico GXP");
        exportGxp.setVisibility(View.GONE);

        exportGxp.setOnClickListener(
            new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openExportPicker();
                }
            }
        );

        root.addView(exportGxp);

        // Botao voltar
        back = new Button(this);
        back.setAllCaps(false);
        back.setTextSize(18);
        back.setText("Voltar");
        back.setVisibility(View.GONE);

        back.setOnClickListener(
            new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            }
        );

        root.addView(back);

        setContentView(root);

        // Caminho real usado pelo extrator C++:
        // work_dir/shaders/p4g_gxp/gxp_headers.tsv
        File work = new File(getCacheDir(), "import");

        gxpReport = new File(
            new File(
                new File(work, "shaders"),
                "p4g_gxp"
            ),
            "gxp_headers.tsv"
        );

        start();
    }

    @Override
    public void onBackPressed() {
        if (!running) {
            super.onBackPressed();
        }
    }

    // Verifica se o relatorio foi gerado.
    void updateExportButton() {
        boolean available =
            gxpReport != null &&
            gxpReport.isFile() &&
            gxpReport.length() > 0;

        exportGxp.setVisibility(
            available ? View.VISIBLE : View.GONE
        );
    }

    // Abre o seletor de destino do Android.
    void openExportPicker() {
        if (gxpReport == null ||
            !gxpReport.isFile() ||
            gxpReport.length() == 0) {

            Toast.makeText(
                this,
                "Relatorio GXP nao encontrado.",
                Toast.LENGTH_LONG
            ).show();

            return;
        }

        Intent intent = new Intent(
            Intent.ACTION_CREATE_DOCUMENT
        );

        intent.addCategory(
            Intent.CATEGORY_OPENABLE
        );

        intent.setType("text/tab-separated-values");

        intent.putExtra(
            Intent.EXTRA_TITLE,
            "gxp_headers.tsv"
        );

        try {
            startActivityForResult(
                intent,
                REQUEST_EXPORT_GXP
            );
        } catch (Exception e) {
            Toast.makeText(
                this,
                "Nao foi possivel abrir o seletor: " +
                e.getMessage(),
                Toast.LENGTH_LONG
            ).show();
        }
    }

    // Copia o relatorio para o local escolhido.
    @Override
    protected void onActivityResult(
        int requestCode,
        int resultCode,
        Intent data
    ) {
        super.onActivityResult(
            requestCode,
            resultCode,
            data
        );

        if (requestCode != REQUEST_EXPORT_GXP ||
            resultCode != RESULT_OK ||
            data == null ||
            data.getData() == null) {
            return;
        }

        final Uri destination = data.getData();

        // Nao bloquear a interface ao copiar o arquivo.
        exportGxp.setEnabled(false);

        new Thread(new Runnable() {
            @Override
            public void run() {
                String error = null;

                try (
                    InputStream input =
                        new FileInputStream(gxpReport);

                    OutputStream output =
                        getContentResolver()
                            .openOutputStream(destination, "w")
                ) {
                    if (output == null) {
                        throw new IOException(
                            "Nao foi possivel abrir o destino"
                        );
                    }

                    byte[] buffer = new byte[8192];
                    int read;

                    while ((read = input.read(buffer)) != -1) {
                        output.write(buffer, 0, read);
                    }

                    output.flush();

                } catch (Exception e) {
                    error = e.getMessage();

                    if (error == null) {
                        error = e.toString();
                    }
                }

                final String resultError = error;

                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        exportGxp.setEnabled(true);

                        if (resultError == null) {
                            Toast.makeText(
                                ImportActivity.this,
                                "Diagnostico GXP exportado!",
                                Toast.LENGTH_LONG
                            ).show();
                        } else {
                            Toast.makeText(
                                ImportActivity.this,
                                "Falha ao exportar: " +
                                resultError,
                                Toast.LENGTH_LONG
                            ).show();
                        }
                    }
                });
            }
        }).start();
    }

    // Inicia a importacao do NSP/XCI.
    void start() {
        String uriString =
            getIntent().getStringExtra(EXTRA_URI);

        if (uriString == null || uriString.isEmpty()) {
            status.setText("Nenhum arquivo de jogo selecionado.");
            back.setVisibility(View.VISIBLE);
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

        exportGxp.setVisibility(View.GONE);

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

                        back.setVisibility(View.VISIBLE);

                        if (result == 0) {
                            bar.setProgress(1000);

                            status.setText(
                                "Preparacao concluida."
                            );

                            setResult(RESULT_OK);

                        } else {
                            status.setText(
                                "Preparacao incompleta:\n\n" +
                                message
                            );
                        }

                        // Mesmo quando o tradutor GXP
                        // ainda nao existe, a extracao
                        // pode ter gerado o relatorio.
                        updateExportButton();
                    }
                });
            }
        }).start();

        // Atualiza o progresso enquanto importa.
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
                    (double)Math.min(done, total) / total;

                int value;

                if (phase <= 1) {
                    value = 20;
                } else if (phase == 2) {
                    value = 50 + (int)(200 * share);
                } else if (phase == 3) {
                    value = 250 + (int)(730 * share);
                } else {
                    value = 990;
                }

                bar.setProgress(value);

                if (phase == 2) {
                    status.setText(
                        "Extraindo shaders GXP (" +
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
