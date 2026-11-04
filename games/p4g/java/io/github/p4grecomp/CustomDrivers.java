package io.github.p4grecomp;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Based on the driver import flow from P5R Android v0.5.3. */
final class CustomDrivers {
    private static final long MAX_BYTES = 256L * 1024 * 1024;

    private CustomDrivers() {}

    private static File directory(Context context) {
        return new File(context.getFilesDir(), "gpu_driver");
    }

    static String active(Context context) {
        File marker = new File(directory(context), "active.txt");
        if (!marker.isFile() || marker.length() > 256) return "";
        try {
            return new String(Files.readAllBytes(marker.toPath()), StandardCharsets.UTF_8).trim();
        } catch (IOException e) { return ""; }
    }

    static synchronized void useSystem(Context context) throws IOException {
        Files.deleteIfExists(new File(directory(context), "active.txt").toPath());
    }

    static synchronized String install(Context context, Uri uri, String name, boolean panvk) throws Exception {
        if (panvk && Build.VERSION.SDK_INT < 31)
            throw new IOException("PanVK requires Android 12 or newer.");
        if (name == null) throw new IOException("Could not read the driver file name.");
        File root = directory(context);
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Could not create driver directory.");
        File installed = new File(root, (panvk ? "panvk-" : "turnip-") + UUID.randomUUID());
        if (!installed.mkdir()) throw new IOException("Could not stage the driver.");
        File marker = null;
        boolean published = false;
        try {
            String library;
            InputStream input = context.getContentResolver().openInputStream(uri);
            if (input == null) throw new IOException("Could not open the driver file.");
            try (InputStream in = input) {
                if (name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                    File archive = new File(installed, "package.zip");
                    try (OutputStream out = new FileOutputStream(archive)) { copy(in, out, MAX_BYTES); }
                    library = extract(archive, installed);
                    Files.delete(archive.toPath());
                } else {
                    library = name;
                    if (!libraryName(library)) throw new IOException("Select a driver .zip or .so.");
                    File target = new File(installed, library);
                    try (OutputStream out = new FileOutputStream(target)) { copy(in, out, MAX_BYTES); }
                    requireArm64(target);
                }
            }
            marker = File.createTempFile("selection-", ".tmp", root);
            Files.write(marker.toPath(), (installed.getName() + "/" + library + "\n").getBytes(StandardCharsets.UTF_8));
            Files.move(marker.toPath(), new File(root, "active.txt").toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            published = true;
            File[] entries = root.listFiles();
            if (entries != null) for (File entry : entries) {
                if (!entry.equals(installed) && entry.getName().matches("(turnip|panvk)-[0-9a-f-]{36}"))
                    removePackage(entry);
            }
            return library;
        } finally {
            if (marker != null) marker.delete();
            if (!published) removePackage(installed);
        }
    }

    private static String extract(File archive, File destination) throws Exception {
        try (ZipFile zip = new ZipFile(archive)) {
            if (zip.size() > 256) throw new IOException("Driver ZIP has too many entries.");
            Map<String, ZipEntry> libraries = new HashMap<>();
            JSONObject metadata = null;
            for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements();) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                name = name.substring(name.lastIndexOf('/') + 1);
                if ("meta.json".equals(name)) {
                    if (metadata != null) throw new IOException("Driver ZIP has duplicate metadata.");
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    try (InputStream in = zip.getInputStream(entry)) { copy(in, out, 16 * 1024); }
                    metadata = new JSONObject(out.toString("UTF-8"));
                } else if (libraryName(name) && libraries.put(name, entry) != null) {
                    throw new IOException("Driver ZIP has duplicate library names.");
                }
            }
            String main = metadata == null ? "" : metadata.optString("libraryName");
            if (main.isEmpty() && libraries.size() == 1) main = libraries.keySet().iterator().next();
            if (!libraries.containsKey(main))
                throw new IOException("Driver ZIP must specify its main .so in meta.json.");
            if (metadata != null && metadata.optInt("minApi", 0) > Build.VERSION.SDK_INT)
                throw new IOException("The driver requires a newer Android version.");
            long remaining = MAX_BYTES;
            for (Map.Entry<String, ZipEntry> library : libraries.entrySet()) {
                File target = new File(destination, library.getKey());
                try (InputStream in = zip.getInputStream(library.getValue()); OutputStream out = new FileOutputStream(target)) {
                    remaining -= copy(in, out, remaining);
                }
                requireArm64(target);
            }
            return main;
        }
    }

    private static boolean libraryName(String name) {
        return name.matches("[A-Za-z0-9_][A-Za-z0-9._-]{0,127}\\.so");
    }

    private static long copy(InputStream in, OutputStream out, long limit) throws IOException {
        long bytes = 0;
        byte[] buffer = new byte[65536];
        for (int n; (n = in.read(buffer)) != -1;) {
            bytes += n;
            if (bytes > limit) throw new IOException("Driver package is too large.");
            out.write(buffer, 0, n);
        }
        return bytes;
    }

    private static void requireArm64(File library) throws IOException {
        byte[] header = new byte[20];
        try (DataInputStream in = new DataInputStream(new FileInputStream(library))) { in.readFully(header); }
        if (header[0] != 0x7f || header[1] != 'E' || header[2] != 'L' || header[3] != 'F' ||
                header[4] != 2 || header[5] != 1 || header[16] != 3 || header[17] != 0 ||
                (header[18] & 255) != 183 || header[19] != 0)
            throw new IOException("Driver libraries must be Android ARM64 shared objects.");
    }

    private static void removePackage(File directory) {
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) file.delete();
        directory.delete();
    }
}
