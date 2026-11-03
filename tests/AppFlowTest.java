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
        byte[] b = new byte[20]; b[0] = 3; b[1] = 2; b[2] = 35; b[3] = 7; b[12] = 1; return b;
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

        File runtime = new File(temp, "spirv");
        File marker = new File(runtime, "prepared.tsv");
        File packed = new File(runtime, "0123456789abcdef.spv");
        File metadata = new File(runtime, "0123456789abcdef.resources");
        // Old copied packs are never considered locally prepared.
        bytes(packed, spv()); text(metadata, "cb0=s0b1");
        check(!PreparationFiles.ready(runtime));
        text(marker, PreparationFiles.FORMAT + "\nseed\t0123456789abcdef\n");
        check(PreparationFiles.ready(runtime));
        Files.delete(packed.toPath()); check(!PreparationFiles.ready(runtime));
        bytes(packed, spv()); Files.delete(metadata.toPath()); check(!PreparationFiles.ready(runtime));
        text(metadata, ""); check(PreparationFiles.ready(runtime)); // no resources is legitimate
        bytes(packed, new byte[20]); check(!PreparationFiles.ready(runtime));
        bytes(packed, spv()); text(new File(runtime, "failed.txt"), "translation error");
        check("translation error".equals(PreparationFiles.failure(runtime)));
        check(!PreparationFiles.ready(runtime)); Files.delete(new File(runtime, "failed.txt").toPath());
        text(marker, "format\told-cache\n"); check(!PreparationFiles.ready(runtime));
        text(marker, PreparationFiles.FORMAT + "\n"); check(PreparationFiles.ready(runtime));
        // Invalid newly encountered modules are detected even without a seed entry.
        bytes(new File(runtime, "fedcba9876543210.spv"), new byte[20]);
        check(!PreparationFiles.ready(runtime));
        System.out.println("PASS: actual ZIP/folder import, backup, round trip, rollback, save paths and preparation readiness");
    }
}
