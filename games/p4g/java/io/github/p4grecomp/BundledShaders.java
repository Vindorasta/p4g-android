package io.github.p4grecomp;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.AtomicFile;
import android.util.Log;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.MessageDigest;

/** Installs the runtime hash-named SPIR-V pack, independently of the GXP cache. */
final class BundledShaders {
    private BundledShaders() {}

    private static String digest(InputStream in) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        byte[] bytes = new byte[16384];
        for (int n; (n = in.read(bytes)) != -1;) sha.update(bytes, 0, n);
        StringBuilder result = new StringBuilder();
        for (byte b : sha.digest()) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }

    private static String fileDigest(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) { return digest(in); }
        catch (Exception e) { throw new IOException("Nao foi possivel verificar " + file.getName(), e); }
    }

    private static void backup(File file, String hash) throws IOException {
        File saved = new File(file.getParentFile(), file.getName() + ".before-pack-" + hash.substring(0, 12));
        if (saved.exists()) {
            if (!hash.equals(fileDigest(saved))) throw new IOException("Backup de shader divergente: " + saved.getName());
            return;
        }
        AtomicFile atomic = new AtomicFile(saved);
        FileOutputStream out = null;
        try (InputStream in = new FileInputStream(file)) {
            out = atomic.startWrite();
            byte[] bytes = new byte[16384];
            for (int n; (n = in.read(bytes)) != -1;) out.write(bytes, 0, n);
            atomic.finishWrite(out);
            out = null;
        } catch (IOException e) {
            if (out != null) atomic.failWrite(out);
            throw e;
        }
    }

    private static boolean validHeader(File file) {
        if (!file.isFile() || file.length() < 20 || file.length() % 4 != 0) return false;
        try (DataInputStream in = new DataInputStream(new FileInputStream(file))) {
            return in.readUnsignedByte() == 3 && in.readUnsignedByte() == 2 &&
                    in.readUnsignedByte() == 35 && in.readUnsignedByte() == 7;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean validResults(File file) {
        if (!file.isFile() || file.length() == 0) return false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"))) {
            String header = reader.readLine();
            if (header == null) return false;
            String[] columns = header.split("\t", -1);
            int resources = -1;
            for (int i = 0; i < columns.length; ++i)
                if ("resources".equals(columns[i])) resources = i;
            if (columns.length == 0 || !"hash".equals(columns[0]) || resources < 0) return false;
            for (String line; (line = reader.readLine()) != null;) {
                String[] row = line.split("\t", -1);
                if (row.length > resources && row[0].matches("[0-9a-f]{16}") && !row[resources].isEmpty()) return true;
            }
        } catch (IOException ignored) { }
        return false;
    }

    static synchronized void install(Context context) throws IOException {
        AssetManager assets = context.getAssets();
        final String packId;
        try (InputStream in = assets.open("spirv/manifest.tsv")) { packId = digest(in); }
        catch (Exception e) { throw new IOException("Nao foi possivel verificar o pacote de shaders.", e); }
        android.content.SharedPreferences preferences = context.getSharedPreferences("shader-pack", Context.MODE_PRIVATE);
        final boolean updatingPack = !packId.equals(preferences.getString("installed-manifest", ""));
        File directory = new File(Launcher.dataDir(), "spirv");
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new IOException("Nao foi possivel criar a pasta spirv.");
        int installed = 0, kept = 0, total = 0;
        boolean resultsListed = false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                assets.open("spirv/manifest.tsv"), "UTF-8"))) {
            for (String line; (line = reader.readLine()) != null;) {
                if (line.trim().isEmpty()) continue;
                String[] entry = line.split("\t", -1);
                if (entry.length != 3 || !entry[0].matches("(?:[0-9a-f]{16}\\.spv|results\\.tsv)") ||
                        !entry[1].matches("[0-9a-f]{64}"))
                    throw new IOException("Indice de shaders do APK invalido.");
                long expectedSize;
                try { expectedSize = Long.parseLong(entry[2]); }
                catch (NumberFormatException e) { throw new IOException("Tamanho de shader invalido.", e); }
                final boolean metadata = "results.tsv".equals(entry[0]);
                resultsListed |= metadata;
                if (expectedSize <= 0 || expectedSize > 16 * 1024 * 1024 ||
                        (!metadata && (expectedSize < 20 || expectedSize % 4 != 0)))
                    throw new IOException("Tamanho de shader fora do limite.");
                ++total;
                File target = new File(directory, entry[0]);
                AtomicFile atomic = new AtomicFile(target);
                // Recover an interrupted AtomicFile write before checking the file.
                if (target.exists() || new File(target.getPath() + ".bak").exists()) {
                    try (FileInputStream recovery = atomic.openRead()) { /* recovery only */ }
                    catch (IOException ignored) { /* retry installation below */ }
                }
                // A new pack must migrate valid-but-patched shaders as well.
                // Back up differing existing files before replacing them. Once
                // this exact pack is installed, later manual changes are kept.
                final boolean valid = metadata ? validResults(target) : validHeader(target);
                if (target.isFile() && updatingPack) {
                    String existing = fileDigest(target);
                    if (valid && entry[1].equals(existing)) { ++kept; continue; }
                    backup(target, existing);
                } else if (valid) { ++kept; continue; }
                FileOutputStream out = null;
                try (InputStream in = assets.open("spirv/" + entry[0])) {
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    out = atomic.startWrite();
                    byte[] buffer = new byte[16384];
                    long written = 0;
                    for (int n; (n = in.read(buffer)) != -1;) {
                        written += n;
                        if (written > expectedSize) throw new IOException("Shader maior que o indice.");
                        digest.update(buffer, 0, n);
                        out.write(buffer, 0, n);
                    }
                    StringBuilder hash = new StringBuilder();
                    for (byte b : digest.digest()) hash.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
                    if (written != expectedSize || !entry[1].equals(hash.toString()))
                        throw new IOException("Shader do APK incompleto: " + entry[0]);
                    atomic.finishWrite(out);
                    out = null;
                    if (!(metadata ? validResults(target) : validHeader(target)))
                        throw new IOException("Arquivo de shader invalido: " + entry[0]);
                    ++installed;
                } catch (Exception e) {
                    if (out != null) atomic.failWrite(out);
                    if (e instanceof IOException) throw (IOException)e;
                    throw new IOException("Falha ao instalar shader: " + entry[0], e);
                }
            }
        }
        if (total == 0 || !resultsListed) throw new IOException("O pacote de shaders esta sem results.tsv.");
        if (!preferences.edit().putString("installed-manifest", packId).commit())
            throw new IOException("Nao foi possivel registrar a instalacao dos shaders.");
        Log.i("P4G-Shaders", "Pack: " + total + "; instalados: " + installed + "; preservados: " + kept);
    }
}
