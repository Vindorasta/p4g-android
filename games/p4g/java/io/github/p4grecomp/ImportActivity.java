
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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;

/**
 * Persona 4 Golden Android - Import Activity.
 *
 * Extrai os shaders GXP e permite exportar:
 *
 * 1. gxp_headers.tsv
 * 2. Um programa GXP completo para diagnostico
 *
 * A traducao GXP para SPIR-V ainda nao
 * esta implementada.
 */
public class ImportActivity extends Activity {

    static final String EXTRA_URI = "uri";
    static final String EXTRA_NAME = "name";

    private static final int REQUEST_EXPORT_REPORT = 701;
    private static final int REQUEST_EXPORT_SHADER = 702;

    // Limite preventivo para a amostra exportada.
    private static final long MAX_GXP_BYTES = 16L * 1024L * 1024L;

    TextView status;
    ProgressBar bar;
    Button back;
    Button exportReport;
    Button exportShader;

    final Handler ui = new Handler(Looper.getMainLooper());

    volatile boolean running;

    File gxpReport;
    File shaderPack;
    File shaderIndex;

    File pendingExport;
    String pendingName;

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
        P4gTheme.apply(this);

        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN |
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(P4gTheme.BACKGROUND);

        int pad = dp(32);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Preparando P4G");
        title.setTextColor(P4gTheme.YELLOW);
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView note = new TextView(this);
        note.setText(
            "Extraindo e analisando os shaders GXP. " +
            "A traducao para Vulkan ainda esta em desenvolvimento."
        );
        note.setTextColor(P4gTheme.MUTED);
        note.setTextSize(15);
        note.setPadding(0, dp(8), 0, dp(20));
        root.addView(note);

        bar = new ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal
        );
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(P4gTheme.YELLOW));
        bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(P4gTheme.PANEL));
        bar.setMax(1000);

        root.addView(
            bar,
            new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(24)
            )
        );

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(16);
        status.setPadding(0, dp(12), 0, dp(12));
        root.addView(status);

        // Botao 1: exportar relatorio de cabecalhos.
        exportReport = new Button(this);
        P4gTheme.button(exportReport, false);
        exportReport.setAllCaps(false);
        exportReport.setTextSize(17);
        exportReport.setText("Exportar diagnostico GXP");
        exportReport.setVisibility(View.GONE);

        exportReport.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!fileAvailable(gxpReport)) {
                    showMessage("Relatorio GXP nao encontrado.");
                    return;
                }

                openExportPicker(
                    gxpReport,
                    "gxp_headers.tsv",
                    REQUEST_EXPORT_REPORT
                );
            }
        });

        root.addView(exportReport);

        // Botao 2: exportar um programa GXP completo.
        exportShader = new Button(this);
        P4gTheme.button(exportShader, false);
        exportShader.setAllCaps(false);
        exportShader.setTextSize(17);
        exportShader.setText("Exportar GXP de teste");
        exportShader.setVisibility(View.GONE);

        exportShader.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prepareShaderExport();
            }
        });

        root.addView(exportShader);

        back = new Button(this);
        P4gTheme.button(back, false);
        back.setAllCaps(false);
        back.setTextSize(18);
        back.setText("Voltar");
        back.setVisibility(View.GONE);

        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        root.addView(back);

        setContentView(root);

        File work = new File(getCacheDir(), "import");

        File gxpDir = new File(
            new File(work, "shaders"),
            "p4g_gxp"
        );

        gxpReport = new File(
            gxpDir,
            "gxp_headers.tsv"
        );

        shaderPack = new File(
            gxpDir,
            "output_cnv_v_standard.bin"
        );

        shaderIndex = new File(
            gxpDir,
            "output_cnv_v_standard.bin.tsv"
        );

        start();
    }

    @Override
    public void onBackPressed() {
        if (!running) {
            super.onBackPressed();
        }
    }

    void showMessage(String message) {
        Toast.makeText(
            this,
            message,
            Toast.LENGTH_LONG
        ).show();
    }

    boolean fileAvailable(File file) {
        return file != null &&
               file.isFile() &&
               file.length() > 0;
    }

    void updateExportButtons() {
        exportReport.setVisibility(
            fileAvailable(gxpReport)
                ? View.VISIBLE
                : View.GONE
        );

        boolean shaderReady =
            fileAvailable(shaderPack) &&
            fileAvailable(shaderIndex);

        exportShader.setVisibility(
            shaderReady
                ? View.VISIBLE
                : View.GONE
        );
    }

    // Abre o seletor nativo do Android.
    void openExportPicker(
        File source,
        String filename,
        int requestCode
    ) {
        if (!fileAvailable(source)) {
            showMessage("Arquivo nao encontrado.");
            return;
        }

        pendingExport = source;
        pendingName = filename;

        Intent intent = new Intent(
            Intent.ACTION_CREATE_DOCUMENT
        );

        intent.addCategory(Intent.CATEGORY_OPENABLE);

        intent.setType(
            requestCode == REQUEST_EXPORT_SHADER
                ? "application/octet-stream"
                : "text/tab-separated-values"
        );

        intent.putExtra(
            Intent.EXTRA_TITLE,
            filename
        );

        try {
            startActivityForResult(
                intent,
                requestCode
            );
        } catch (Exception e) {
            pendingExport = null;
            pendingName = null;
            showMessage(
                "Nao foi possivel abrir o seletor: " +
                e.getMessage()
            );
        }
    }

    // Le o primeiro programa do indice TSV.
    // O indice possui:
    //
    // nome    offset    tamanho
    //
    // O offset e relativo ao pacote BIN descomprimido.
    void prepareShaderExport() {
        if (!fileAvailable(shaderPack) ||
            !fileAvailable(shaderIndex)) {
            showMessage(
                "Pacote ou indice GXP nao encontrado."
            );
            return;
        }

        exportShader.setEnabled(false);

        new Thread(new Runnable() {
            @Override
            public void run() {
                File extracted = null;
                String name = null;
                String error = null;

                try {
                    String[] first = readFirstShaderRecord();

                    name = first[0];
                    long offset = Long.parseLong(first[1]);
                    long length = Long.parseLong(first[2]);

                    if (!name.matches(
                            "[VF][0-9a-fA-F]{8}_" +
                            "[0-9a-fA-F]{8}\\.gxp"
                        )) {
                        throw new IOException(
                            "Nome GXP invalido no indice"
                        );
                    }

                    if (offset < 0 ||
                        length < 12 ||
                        length > MAX_GXP_BYTES ||
                        offset > shaderPack.length() ||
                        length > shaderPack.length() - offset) {
                        throw new IOException(
                            "Offset ou tamanho GXP invalido"
                        );
                    }

                    File exportDir = new File(
                        getCacheDir(),
                        "gxp_export"
                    );

                    if (!exportDir.exists() &&
                        !exportDir.mkdirs()) {
                        throw new IOException(
                            "Nao foi possivel criar pasta temporaria"
                        );
                    }

                    extracted = new File(
                        exportDir,
                        name
                    );

                    // Copiar exatamente os bytes do
                    // programa, sem alinhamento extra.
                    try (
                        RandomAccessFile input =
                            new RandomAccessFile(shaderPack, "r");

                        FileOutputStream output =
                            new FileOutputStream(extracted)
                    ) {
                        input.seek(offset);

                        byte[] buffer = new byte[8192];
                        long remaining = length;

                        while (remaining > 0) {
                            int amount = (int) Math.min(
                                buffer.length,
                                remaining
                            );

                            int read = input.read(
                                buffer,
                                0,
                                amount
                            );

                            if (read <= 0) {
                                throw new IOException(
                                    "GXP truncado durante leitura"
                                );
                            }

                            output.write(
                                buffer,
                                0,
                                read
                            );

                            remaining -= read;
                        }

                        output.flush();
                    }

                    // Conferir o tamanho e a assinatura.
                    if (extracted.length() != length) {
                        throw new IOException(
                            "Tamanho exportado incorreto"
                        );
                    }

                    try (
                        FileInputStream input =
                            new FileInputStream(extracted)
                    ) {
                        byte[] magic = new byte[4];

                        if (input.read(magic) != 4 ||
                            magic[0] != 'G' ||
                            magic[1] != 'X' ||
                            magic[2] != 'P' ||
                            magic[3] != 0) {
                            throw new IOException(
                                "Assinatura GXP invalida"
                            );
                        }
                    }

                } catch (Exception e) {
                    error = e.getMessage();

                    if (error == null) {
                        error = e.toString();
                    }

                    if (extracted != null) {
                        extracted.delete();
                    }
                }

                final File resultFile = extracted;
                final String resultName = name;
                final String resultError = error;

                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        exportShader.setEnabled(true);

                        if (resultError != null) {
                            showMessage(
                                "Falha ao preparar GXP: " +
                                resultError
                            );
                            return;
                        }

                        openExportPicker(
                            resultFile,
                            resultName,
                            REQUEST_EXPORT_SHADER
                        );
                    }
                });
            }
        }).start();
    }

    // Retorna nome, offset e tamanho do primeiro
    // programa presente no TSV.
    String[] readFirstShaderRecord() throws IOException {
        try (
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                    new FileInputStream(shaderIndex),
                    "UTF-8"
                )
            )
        ) {
            String header = reader.readLine();

            if (header == null ||
                !header.startsWith("nome\t")) {
                throw new IOException(
                    "Cabecalho TSV invalido"
                );
            }

            String line;

            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }

                String[] fields = line.split("\\t");

                if (fields.length < 3) {
                    throw new IOException(
                        "Registro TSV incompleto"
                    );
                }

                return new String[] {
                    fields[0],
                    fields[1],
                    fields[2]
                };
            }
        }

        throw new IOException(
            "Nenhum programa encontrado no indice"
        );
    }

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

        if (requestCode != REQUEST_EXPORT_REPORT &&
            requestCode != REQUEST_EXPORT_SHADER) {
            return;
        }

        final File source = pendingExport;
        pendingExport = null;
        pendingName = null;

        if (resultCode != RESULT_OK ||
            data == null ||
            data.getData() == null) {
            return;
        }

        if (!fileAvailable(source)) {
            showMessage(
                "Arquivo de origem indisponivel."
            );
            return;
        }

        final Uri destination = data.getData();

        exportReport.setEnabled(false);
        exportShader.setEnabled(false);

        new Thread(new Runnable() {
            @Override
            public void run() {
                String error = null;

                try (
                    InputStream input =
                        new FileInputStream(source);

                    OutputStream output =
                        getContentResolver()
                            .openOutputStream(destination, "w")
                ) {
                    if (output == null) {
                        throw new IOException(
                            "Destino nao disponivel"
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
                        exportReport.setEnabled(true);
                        exportShader.setEnabled(true);

                        if (resultError == null) {
                            showMessage(
                                "Arquivo exportado com sucesso!"
                            );
                        } else {
                            showMessage(
                                "Falha ao exportar: " +
                                resultError
                            );
                        }
                    }
                });
            }
        }).start();
    }

    void start() {
        String uriString =
            getIntent().getStringExtra(EXTRA_URI);

        if (uriString == null || uriString.isEmpty()) {
            status.setText(
                "Nenhum arquivo de jogo selecionado."
            );
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

        exportReport.setVisibility(View.GONE);
        exportShader.setVisibility(View.GONE);

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

                        updateExportButtons();
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
