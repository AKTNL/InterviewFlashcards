package com.interviewflashcards.backup;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** The ZIP boundary is deliberately independent from database replacement. */
public final class BackupArchive {
    public static final int VERSION = 1;
    public static final long MAX_ZIP = 50L * 1024 * 1024, MAX_EXPANDED = 512L * 1024 * 1024;
    public static final int MAX_ENTRIES = 10000, MAX_JSON = 32 * 1024 * 1024;
    private BackupArchive() {}
    public static class UnsupportedVersion extends IOException { public UnsupportedVersion() { super("Unsupported formatVersion"); } }
    public static String hash(Path path) throws IOException {
        try (var in = Files.newInputStream(path)) {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = new byte[65536]; int n;
            while ((n = in.read(bytes)) != -1) digest.update(bytes, 0, n);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static void write(Path zip, ObjectNode manifest, Map<String, Path> files, ObjectMapper mapper) throws IOException {
        if (files.size() + 1 > MAX_ENTRIES) throw new IOException("Too many files");
        var list = manifest.putArray("files"); long total = 0;
        for (var entry : files.entrySet()) {
            long size = Files.size(entry.getValue()); total += size;
            if (total > MAX_EXPANDED) throw new IOException("Archive too large");
            list.addObject().put("path", entry.getKey()).put("byteSize", size).put("sha256", hash(entry.getValue()));
        }
        try (var out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("manifest.json")); out.write(mapper.writeValueAsBytes(manifest)); out.closeEntry();
            for (var entry : files.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey())); Files.copy(entry.getValue(), out); out.closeEntry();
            }
        }
        if (Files.size(zip) > MAX_ZIP) throw new IOException("Compressed archive too large");
    }
    public record Opened(ObjectNode manifest, ObjectNode data, Map<String, Path> files) {}
    public static Opened read(Path zip, Path directory, ObjectMapper mapper) throws IOException {
        if (Files.size(zip) > MAX_ZIP) throw new IOException("Compressed archive too large");
        // Require a real central directory as well as checked local entries/CRC.
        try (var central = new ZipFile(zip.toFile())) {
            if (central.size() < 2 || central.size() > MAX_ENTRIES) throw new IOException("Entry limit");
        }
        var files = new LinkedHashMap<String, Path>(); long total = 0;
        try (var in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                String name = entry.getName();
                if (!name.matches("manifest\\.json|data\\.json|sources/[0-9a-f-]{36}\\.pdf|assets/[0-9a-f-]{36}\\.(png|jpg)")
                        || files.containsKey(name) || files.size() >= MAX_ENTRIES || entry.isDirectory()) throw new IOException("Unsafe ZIP entry");
                Path target = directory.resolve(name).normalize();
                if (!target.startsWith(directory)) throw new IOException("Unsafe ZIP path");
                Files.createDirectories(target.getParent());
                long size = 0;
                try (var out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                    byte[] bytes = new byte[65536]; int n;
                    while ((n = in.read(bytes)) != -1) {
                        size += n; total += n;
                        if (total > MAX_EXPANDED || (name.endsWith(".json") && size > MAX_JSON)) throw new IOException("Expanded limit");
                        out.write(bytes, 0, n);
                    }
                }
                in.closeEntry(); files.put(name, target);
            }
        }
        if (!files.containsKey("manifest.json") || !files.containsKey("data.json")) throw new IOException("Missing metadata");
        JsonNode raw = mapper.readTree(files.get("manifest.json").toFile());
        if (!(raw instanceof ObjectNode manifest) || !manifest.path("formatVersion").isInt() || manifest.path("formatVersion").asInt() != VERSION)
            throw new UnsupportedVersion();
        java.time.Instant.parse(manifest.path("createdAt").asText());
        if (!manifest.path("files").isArray()) throw new IOException("Invalid manifest");
        var expected = new HashSet<String>(); expected.add("manifest.json");
        for (var item : manifest.path("files")) {
            String name = item.path("path").asText(); Path file = files.get(name);
            if (!expected.add(name) || file == null || !item.path("byteSize").isIntegralNumber()
                    || item.path("byteSize").asLong(-1) != Files.size(file) || !hash(file).equals(item.path("sha256").asText()))
                throw new IOException("Checksum or size mismatch");
        }
        if (!expected.equals(files.keySet())) throw new IOException("File list mismatch");
        var data = mapper.readTree(files.get("data.json").toFile());
        if (!(data instanceof ObjectNode object)) throw new IOException("Invalid data");
        return new Opened(manifest, object, files);
    }
    public static void cleanup(Path directory) {
        if (directory == null) return;
        try (var paths = Files.walk(directory)) {
            for (var p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        } catch (IOException e) { org.slf4j.LoggerFactory.getLogger(BackupArchive.class).warn("Backup staging cleanup failed"); }
    }
}
