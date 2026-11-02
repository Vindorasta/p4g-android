package io.github.p4grecomp;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public class AppFlowTest {
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    static void bytes(File file, byte[] data) throws IOException {
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), data);
    }
    static void text(File file, String text) throws IOException { bytes(file, text.getBytes("UTF-8")); }
    static byte[] spv() {
        byte[] b = new byte[20]; b[0] = 3; b[1] = 2; b[2] = 35; b[3] = 7; return b;
    }
    static File zip(File dir, String name, String... paths) throws IOException {
        File out = new File(dir, name);
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(out))) {
            for (String path : paths) {
                z.putNextEntry(new ZipEntry(path));
                z.write(("imported:" + path).getBytes("UTF-8"));
                z.closeEntry();
            }
        }
        return out;
    }
    static boolean ready(File converted, File runtime) throws IOException {
        return PreparationFiles.ready(converted, runtime, new ByteArrayInputStream(
            ("0123456789abcdef.spv\tunused\t20\nresults.tsv\tunused\t50\n").getBytes("UTF-8")));
    }
    public static void main(String[] args) throws Exception {
        File temp = Files.createTempDirectory("p4g-flow-test").toFile();
        System.setProperty("p4g.test.root", temp.getPath());
        check("backup/save/".equals(SavePaths.prefix(Arrays.asList("backup/save/data0001.bin", "backup/save/data0001.binslot"))));
        check("".equals(SavePaths.prefix(Arrays.asList("system.bin", "data0099.bin"))));
        check("data0001.bin".equals(SavePaths.target("save/DATA0001.BIN", "save/")));
        check(SavePaths.target("save/../data0001.bin", "save/") == null);
        check(SavePaths.target("/data0001.bin", "") == null);
        check(SavePaths.target("save/notes.txt", "save/") == null);
        check(SavePaths.prefix(Arrays.asList("DATA01/DATA.DAT")) == null); // P5R is not P4G.
        try {
            SavePaths.prefix(Arrays.asList("first/data0001.bin", "second/data0002.bin"));
            throw new AssertionError("Ambiguous saves accepted");
        } catch (IOException expected) { }

        android.content.ContentResolver resolver = new android.content.ContentResolver();
        File existing = new File(SaveFiles.saveDir(), "data0001.bin");
        text(existing, "original");
        File archive = zip(temp, "save.zip", "backup/user/save/data0001.bin", "backup/user/save/data0001.binslot", "notes.txt");
        SaveFiles.Result result = SaveFiles.importZip(resolver, android.net.Uri.parse(archive.getPath()));
        check(result.files == 2 && result.slots.contains("0001") && result.backup != null);
        check(new String(Files.readAllBytes(existing.toPath()), "UTF-8").startsWith("imported:"));
        check(new String(Files.readAllBytes(new File(temp, "user/" + result.backup + "/data0001.bin").toPath()), "UTF-8").equals("original"));
        check(!new File(SaveFiles.saveDir(), "notes.txt").exists());
        File exported = SaveFiles.export();
        check(exported.getName().startsWith("P4G-saves-"));
        check(SaveFiles.importZip(resolver, android.net.Uri.parse(exported.getPath())).files == 2);
        byte[] before = Files.readAllBytes(existing.toPath());
        try {
            SaveFiles.importZip(resolver, android.net.Uri.parse(zip(temp, "missing-pair.zip", "data0001.bin").getPath()));
            throw new AssertionError("Missing binslot accepted");
        } catch (IOException expected) { }
        check(Arrays.equals(before, Files.readAllBytes(existing.toPath())));
        check(SaveFiles.importZip(resolver, android.net.Uri.parse(zip(temp, "unsafe.zip", "../data0002.bin", "../data0002.binslot").getPath())) == null);

        File folder = new File(temp, "folder-backup/save");
        text(new File(folder, "data0002.bin"), "folder save");
        text(new File(folder, "data0002.binslot"), "folder metadata");
        text(new File(folder, "notes.txt"), "not a save");
        result = SaveFiles.importTree(resolver, android.net.Uri.parse(folder.getParentFile().getPath()));
        check(result.files == 2 && result.slots.contains("0002"));
        check(new String(Files.readAllBytes(new File(SaveFiles.saveDir(), "data0002.bin").toPath()), "UTF-8").equals("folder save"));
        check(Arrays.equals(before, Files.readAllBytes(existing.toPath()))); // Slots not imported stay intact.

        System.setProperty("p4g.test.fail-file", "data0001.binslot");
        try {
            SaveFiles.importZip(resolver, android.net.Uri.parse(archive.getPath()));
            throw new AssertionError("Simulated commit failure was ignored");
        } catch (IOException expected) {
            check(Arrays.equals(before, Files.readAllBytes(existing.toPath())));
        } finally { System.clearProperty("p4g.test.fail-file"); }

        File converted = new File(temp, "converted"), runtime = new File(temp, "spirv");
        File summary = new File(converted, "summary.tsv"), module = new File(converted, "pack/000000.spv");
        File packed = new File(runtime, "0123456789abcdef.spv"), results = new File(runtime, "results.tsv");
        text(summary, "total\t1\nsucesso\t1\nfalhas\t0\n");
        bytes(module, spv()); bytes(packed, spv());
        text(results, "hash\tresources\n0123456789abcdef\tubo:0\n");
        check(ready(converted, runtime));
        Files.delete(packed.toPath()); check(!ready(converted, runtime));
        bytes(packed, spv()); text(results, "broken"); check(!ready(converted, runtime));
        text(results, "hash\tresources\n0123456789abcdef\tubo:0\n");
        bytes(module, new byte[20]); check(!ready(converted, runtime));
        bytes(module, spv()); text(summary, "total\t2\nsucesso\t2\nfalhas\t0\n"); check(!ready(converted, runtime));
        text(summary, "total\t1\nsucesso\t0\nfalhas\t1\n"); check(!ready(converted, runtime));
        System.out.println("PASS: actual ZIP/folder import, backup, round trip, rollback, save paths and preparation readiness");
    }
}
