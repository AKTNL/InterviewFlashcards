package com.interviewflashcards.backup;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class BackupArchiveTest {
    @TempDir Path directory;
    ObjectMapper mapper=new ObjectMapper();
    private Path archive() throws Exception {
        Path data=directory.resolve("data.json");Files.writeString(data,"{}");Path zip=directory.resolve("backup.zip");
        BackupArchive.write(zip,mapper.createObjectNode().put("formatVersion",1).put("createdAt","2026-10-31T00:00:00Z"),Map.of("data.json",data),mapper);return zip;
    }
    private Path rewrite(Path source, java.util.function.BiFunction<String,byte[],byte[]> change) throws Exception {
        Path target=directory.resolve(UUID.randomUUID()+".zip");
        try(var input=new ZipInputStream(Files.newInputStream(source));var output=new ZipOutputStream(Files.newOutputStream(target))) {
            ZipEntry entry;while((entry=input.getNextEntry())!=null) {
                byte[] bytes=change.apply(entry.getName(),input.readAllBytes());if(bytes==null)continue;
                output.putNextEntry(new ZipEntry(entry.getName()));output.write(bytes);output.closeEntry();
            }
        }return target;
    }
    private void rejected(Path zip) throws Exception {
        Path extract=Files.createTempDirectory(directory,"read-");assertThatThrownBy(()->BackupArchive.read(zip,extract,mapper)).isInstanceOf(Exception.class);
    }
    @Test void roundTripChecksAllFilesAndRejectsCorruptedDataMissingFilesAndNewVersions() throws Exception {
        Path zip=archive();assertThat(BackupArchive.read(zip,Files.createDirectory(directory.resolve("valid")),mapper).data().isObject()).isTrue();
        rejected(rewrite(zip,(name,bytes)->name.equals("data.json")?"{\"modified\":true}".getBytes():bytes));
        rejected(rewrite(zip,(name,bytes)->name.equals("data.json")?null:bytes));
        rejected(rewrite(zip,(name,bytes)->name.equals("manifest.json")?new String(bytes).replace("\"formatVersion\":1","\"formatVersion\":999").getBytes():bytes));
        Path truncated=directory.resolve("truncated.zip");Files.write(truncated,Arrays.copyOf(Files.readAllBytes(zip),20));rejected(truncated);
    }
    @Test void rejectsTraversalAbsolutePathsDirectoriesAndUnexpectedFiles() throws Exception {
        for(String name:List.of("../escape","/absolute","sources/../../escape.pdf","sources\\escape.pdf","sources/","passwords.json")) {
            Path zip=directory.resolve(UUID.randomUUID()+".zip");try(var out=new ZipOutputStream(Files.newOutputStream(zip))) {
                out.putNextEntry(new ZipEntry("manifest.json"));out.write("{}".getBytes());out.closeEntry();out.putNextEntry(new ZipEntry(name));out.write(1);out.closeEntry();
            }rejected(zip);
        }
        assertThat(Files.exists(directory.resolve("escape"))).isFalse();
    }
    @Test void rejectsHighlyCompressedOversizedJsonWithoutUnboundedAllocation() throws Exception {
        Path zip=directory.resolve("bomb.zip");try(var out=new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("manifest.json"));out.write("{}".getBytes());out.closeEntry();out.putNextEntry(new ZipEntry("data.json"));
            byte[] block=new byte[65536];for(int i=0;i<513;i++)out.write(block);out.closeEntry();
        }rejected(zip);
    }
}
