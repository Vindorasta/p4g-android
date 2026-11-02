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

    private static boolean validHeader(File file) {
        if (!file.isFile() || file.length() < 20 || file.length() % 4 != 0) return false;
        try (DataInputStream in = new DataInputStream(new FileInputStream(file))) {
            return in.readUnsignedByte() == 3 && in.readUnsignedByte() == 2 &&
                    in.readUnsignedByte() == 35 && in.readUnsignedByte() == 7;
        } catch (IOException e) {
            return false;
        }
    }

    static synchronized void install(Context context) throws IOException {
        AssetManager assets = context.getAssets();
        File directory = new File(Launcher.dataDir(), "spirv");
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new IOException("Nao foi possivel criar a pasta spirv.");
        int installed = 0, kept = 0, total = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                assets.open("spirv/manifest.tsv"), "UTF-8"))) {
            for (String line; (line = reader.readLine()) != null;) {
                if (line.trim().isEmpty()) continue;
                String[] entry = line.split("\t", -1);
                if (entry.length != 3 || !entry[0].matches("[0-9a-f]{16}\\.spv") ||
                        !entry[1].matches("[0-9a-f]{64}"))
                    throw new IOException("Indice de shaders do APK invalido.");
                long expectedSize;
                try { expectedSize = Long.parseLong(entry[2]); }
                catch (NumberFormatException e) { throw new IOException("Tamanho de shader invalido.", e); }
                if (expectedSize < 20 || expectedSize > 16 * 1024 * 1024 || expectedSize % 4 != 0)
                    throw new IOException("Tamanho de shader fora do limite.");
                ++total;
                File target = new File(directory, entry[0]);
                AtomicFile atomic = new AtomicFile(target);
                // Recover an interrupted AtomicFile write before checking the file.
                if (target.exists() || new File(target.getPath() + ".bak").exists()) {
                    try (FileInputStream recovery = atomic.openRead()) { /* recovery only */ }
                    catch (IOException ignored) { /* retry installation below */ }
                }
                // Keep user-installed fixes and shaders not in this bundled pack.
                if (validHeader(target)) { ++kept; continue; }
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
                    if (!validHeader(target)) throw new IOException("Cabecalho SPIR-V invalido: " + entry[0]);
                    ++installed;
                } catch (Exception e) {
                    if (out != null) atomic.failWrite(out);
                    if (e instanceof IOException) throw (IOException)e;
                    throw new IOException("Falha ao instalar shader: " + entry[0], e);
                }
            }
        }
        if (total == 0) throw new IOException("O APK nao contem shaders para instalar.");
        Log.i("P4G-Shaders", "Pack: " + total + "; instalados: " + installed + "; preservados: " + kept);
    }
}
