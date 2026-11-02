package io.github.p4grecomp;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Imports the actual P4G files into the runtime's user/save directory. */
final class SaveFiles {
    static final long MAX_BYTES = 256L << 20;  // a save is a few MB at most: anything bigger is not one

    /** What an import did: files written, the slots among them, the backup folder (null: there was nothing to keep). */
    static final class Result {
        int files;
        final TreeSet<String> slots = new TreeSet<>();
        String backup;
    }

    private SaveFiles() {}

    static File saveDir() { return new File(Launcher.dataDir(), "user/save"); }

    static String stamp() { return new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(new Date()) + "-" + java.util.UUID.randomUUID().toString().substring(0, 8); }

    static String prefix(List<String> paths) throws IOException { return SavePaths.prefix(paths); }
    static String target(String path, String prefix) { return SavePaths.target(path, prefix); }

    static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[1 << 16];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
    }

    static void copyTree(File from, File to) throws IOException {
        if (from.isDirectory()) {
            if (!to.isDirectory() && !to.mkdirs()) throw new IOException("cannot make " + to);
            File[] kids = from.listFiles();
            if (kids != null) for (File k : kids) copyTree(k, new File(to, k.getName()));
        } else {
            try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
                copy(in, out);
            }
        }
    }

    static boolean hasFiles(File dir) {
        File[] kids = dir.listFiles();
        if (kids == null) return false;
        for (File k : kids) if (k.isFile() || hasFiles(k)) return true;
        return false;
    }

    /** The current saves, copied aside; the folder's name, or null when there are none. */
    static String backup() throws IOException {
        File src = saveDir();
        if (!hasFiles(src)) return null;
        String name = "save_backup_" + stamp();
        copyTree(src, new File(Launcher.dataDir(), "user/" + name));
        return name;
    }

    static void stageCopy(InputStream in, File out, long[] bytes) throws IOException {
        if (in == null) throw new IOException("Não foi possível ler o save.");
        try (OutputStream stream = new FileOutputStream(out)) {
            byte[] buffer = new byte[65536];
            for (int n; (n = in.read(buffer)) != -1;) {
                bytes[0] += n;
                if (bytes[0] > MAX_BYTES) throw new IOException("O pacote de saves é grande demais.");
                stream.write(buffer, 0, n);
            }
        }
    }
    static File stageDir() throws IOException {
        File dir = new File(Launcher.dataDir(), "user/.save-import-" + java.util.UUID.randomUUID());
        if (!dir.mkdirs()) throw new IOException("Não foi possível preparar a importação.");
        return dir;
    }
    static void removeStage(File dir) {
        File[] kids = dir.listFiles();
        if (kids != null) for (File f : kids) f.delete();
        dir.delete();
    }
    static Result commit(Map<String, File> files) throws IOException {
        if (files.isEmpty()) return null;
        for (String name : files.keySet()) {
            if (files.get(name).length() == 0) throw new IOException("Save vazio: " + name);
            if (name.startsWith("data")) {
                String stem = name.substring(0, name.lastIndexOf('.'));
                if (!files.containsKey(stem + ".bin") || !files.containsKey(stem + ".binslot"))
                    throw new IOException("Importe os dois arquivos do slot: " + stem + ".bin e " + stem + ".binslot.");
            }
        }
        Result result = new Result();
        result.backup = backup();
        if (!saveDir().isDirectory() && !saveDir().mkdirs()) throw new IOException("Não foi possível criar user/save.");
        List<String> written = new ArrayList<>();
        try {
            for (Map.Entry<String, File> entry : files.entrySet()) {
                String name = entry.getKey();
                written.add(name);
                android.util.AtomicFile atomic = new android.util.AtomicFile(new File(saveDir(), name));
                FileOutputStream stream = null;
                try (InputStream in = new FileInputStream(entry.getValue())) {
                    stream = atomic.startWrite();
                    copy(in, stream);
                    atomic.finishWrite(stream);
                    stream = null;
                } catch (IOException e) {
                    if (stream != null) atomic.failWrite(stream);
                    throw e;
                }
                result.files++;
                if (name.startsWith("data")) result.slots.add(name.substring(4, 8));
            }
        } catch (IOException e) {
            for (String name : written) {
                File previous = result.backup == null ? null : new File(Launcher.dataDir(), "user/" + result.backup + "/" + name);
                try {
                    if (previous != null && previous.isFile()) copyTree(previous, new File(saveDir(), name));
                    else new File(saveDir(), name).delete();
                } catch (IOException restore) { e.addSuppressed(restore); }
            }
            throw e;
        }
        return result;
    }
    static Result importZip(ContentResolver cr, Uri uri) throws IOException {
        File stage = stageDir();
        Map<String, File> staged = new LinkedHashMap<>();
        List<String> names = new ArrayList<>();
        long[] bytes = {0};
        try {
            InputStream input = cr.openInputStream(uri);
            if (input == null) throw new IOException("Não foi possível abrir o ZIP.");
            try (ZipInputStream z = new ZipInputStream(input)) {
                int count = 0;
                byte[] discard = new byte[65536];
                for (ZipEntry e; (e = z.getNextEntry()) != null;) {
                    if (++count > 4096) throw new IOException("O ZIP contém arquivos demais.");
                    if (e.isDirectory()) continue;
                    String path = e.getName().replace('\\', '/');
                    String name = path.substring(path.lastIndexOf('/') + 1);
                    if (SavePaths.safe(path) && SavePaths.file(name)) {
                        if (staged.containsKey(path)) throw new IOException("Save duplicado no ZIP.");
                        File dest = new File(stage, "entry-" + count);
                        stageCopy(z, dest, bytes);
                        names.add(path);
                        staged.put(path, dest);
                    } else {
                        for (int n; (n = z.read(discard)) != -1;) {
                            bytes[0] += n;
                            if (bytes[0] > MAX_BYTES) throw new IOException("O ZIP é grande demais.");
                        }
                    }
                }
            }
            String prefix = prefix(names);
            if (prefix == null) return null;
            Map<String, File> files = new LinkedHashMap<>();
            for (String path : names) {
                String rel = target(path, prefix);
                if (rel != null && files.put(rel, staged.get(path)) != null) throw new IOException("Save duplicado.");
            }
            return commit(files);
        } finally { removeStage(stage); }
    }

    static void listTree(ContentResolver cr, Uri tree, String docId, String path, Map<String, Uri> out)
            throws IOException {
        if (path.split("/").length > 16) throw new IOException("A pasta tem subpastas demais.");
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        String[] cols = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE};
        try (Cursor c = cr.query(children, cols, null, null, null)) {
            if (c == null) throw new IOException("cannot list the folder");
            while (c.moveToNext()) {
                String id = c.getString(0), name = c.getString(1), mime = c.getString(2);
                if (Document.MIME_TYPE_DIR.equals(mime)) listTree(cr, tree, id, path + name + "/", out);
                else out.put(path + name, DocumentsContract.buildDocumentUriUsingTree(tree, id));
                if (out.size() > 4096) throw new IOException("too many files: is this a save folder?");
            }
        }
    }

    /** Null when the selected tree holds no P4G save files. */
    static Result importTree(ContentResolver cr, Uri tree) throws IOException {
        String root = DocumentsContract.getTreeDocumentId(tree);
        String rootName = "";
        try (Cursor c = cr.query(DocumentsContract.buildDocumentUriUsingTree(tree, root),
                new String[] {Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) rootName = c.getString(0);
        }
        Map<String, Uri> files = new LinkedHashMap<>();
        listTree(cr, tree, root, rootName == null || rootName.isEmpty() ? "" : rootName + "/", files);
        String pre = prefix(new ArrayList<>(files.keySet()));
        if (pre == null) return null;
        File stage = stageDir();
        Map<String, File> staged = new LinkedHashMap<>();
        long[] bytes = {0};
        try {
            for (Map.Entry<String, Uri> f : files.entrySet()) {
                String rel = target(f.getKey(), pre);
                if (rel == null) continue;
                if (staged.containsKey(rel)) throw new IOException("Save duplicado na pasta.");
                File dest = new File(stage, "entry-" + staged.size());
                try (InputStream in = cr.openInputStream(f.getValue())) { stageCopy(in, dest, bytes); }
                staged.put(rel, dest);
            }
            return commit(staged);
        } finally { removeStage(stage); }
    }

    static void zipTree(ZipOutputStream z, File dir, String path) throws IOException {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory()) {
                zipTree(z, k, path + k.getName() + "/");
            } else {
                z.putNextEntry(new ZipEntry(path + k.getName()));
                try (InputStream in = new FileInputStream(k)) {
                    copy(in, z);
                }
                z.closeEntry();
            }
        }
    }

    /** The ZIP written, or null when there are no saves to export. */
    static File export() throws IOException {
        if (!hasFiles(saveDir())) return null;
        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot make " + dir);
        File out = new File(dir, "P4G-saves-" + stamp() + ".zip");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(out))) {
            zipTree(z, saveDir(), "save/");
        }
        return out;
    }
}
