package io.github.p4grecomp;

import java.io.*;
import java.util.HashSet;
import java.util.Set;

/** File checks shared by launcher routing and host-side regression tests. */
final class PreparationFiles {
    static boolean spirv(File f) throws IOException {
        if (!f.isFile() || f.length() < 20 || f.length() % 4 != 0) return false;
        try (DataInputStream in = new DataInputStream(new FileInputStream(f))) {
            return in.readInt() == 0x03022307;
        }
    }
    static int converted(File dir) throws IOException {
        File[] files = dir.listFiles();
        if (files == null) return -1;
        int count = 0;
        for (File f : files) {
            if (f.isDirectory()) {
                int n = converted(f);
                if (n < 0) return -1;
                count += n;
            } else if (f.getName().endsWith(".spv")) {
                if (!spirv(f)) return -1;
                ++count;
            }
        }
        return count;
    }
    static boolean results(File file) throws IOException {
        if (!file.isFile()) return false;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"))) {
            String line = r.readLine();
            if (line == null) return false;
            String[] header = line.split("\t", -1);
            if (!"hash".equals(header[0])) return false;
            int resources = -1;
            for (int i = 0; i < header.length; ++i) if ("resources".equals(header[i])) resources = i;
            if (resources < 0) return false;
            while ((line = r.readLine()) != null) {
                String[] row = line.split("\t", -1);
                if (row.length > resources && row[0].matches("[0-9a-f]{16}") && !row[resources].isEmpty()) return true;
            }
            return false;
        }
    }
    static boolean ready(File converted, File runtime, InputStream manifest) throws IOException {
        try (BufferedReader pack = new BufferedReader(new InputStreamReader(manifest, "UTF-8"))) {
            int total = -1, success = -1, failed = -1;
            File summary = new File(converted, "summary.tsv");
            if (!summary.isFile()) return false;
            try (BufferedReader r = new BufferedReader(new FileReader(summary))) {
                for (String line; (line = r.readLine()) != null;) {
                    String[] fields = line.split("\t", -1);
                    if (fields.length != 2) continue;
                    try {
                        int n = Integer.parseInt(fields[1]);
                        if ("total".equals(fields[0])) total = n;
                        if ("sucesso".equals(fields[0])) success = n;
                        if ("falhas".equals(fields[0])) failed = n;
                    } catch (NumberFormatException e) { return false; }
                }
            }
            if (total <= 0 || success != total || failed != 0 || converted(converted) != total) return false;
            Set<String> names = new HashSet<>();
            int shaders = 0;
            boolean metadata = false;
            for (String line; (line = pack.readLine()) != null;) {
                if (line.trim().isEmpty()) continue;
                String[] row = line.split("\t", -1);
                if (row.length != 3 || !names.add(row[0])) return false;
                if (row[0].matches("[0-9a-f]{16}\\.spv")) {
                    if (!spirv(new File(runtime, row[0]))) return false;
                    ++shaders;
                } else if ("results.tsv".equals(row[0])) {
                    if (!results(new File(runtime, row[0]))) return false;
                    metadata = true;
                } else return false;
            }
            return shaders > 0 && metadata;
        }
    }
}
