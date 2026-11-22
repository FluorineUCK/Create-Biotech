package com.nobodiiiii.createbiotech.content.biocomputing.manual;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/** An entry opens the complete offline book. Only its PDF initial view differs. */
public enum ManualFile {
    QUICK("PIN-01", 4), ASSEMBLY("INS-01", 46), HARDWARE("HW-01", 6), DEBUG("ASM-01", 8),
    WIRING("XB-01", 32), SERIAL("DEV-01", 37), RADIO("RF-01", 35), LIBRARY("DEV-09", 45),
    READER("DEV-02", 38), DISPLAY("DEV-03", 39), MUSIC("DEV-04", 40), TERMINAL("DEV-05", 41), ORDER("DEV-08", 44);

    public final String entry;
    public final int page;
    ManualFile(String entry, int page) { this.entry = entry; this.page = page; }

    private static byte[] resource(String filename) throws IOException {
        try (var in = ManualFile.class.getResourceAsStream("/biocomputing/manual/" + filename)) {
            if (in == null) throw new FileNotFoundException(filename);
            return in.readAllBytes();
        }
    }
    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    public synchronized Path extract(Path game) throws IOException {
        byte[] book = resource("bc-01.pdf");
        String edition = sha256(book);
        // PDF updates contain byte offsets. Never append one to a different edition.
        if (!edition.equals(new String(resource("edition.sha256"), StandardCharsets.US_ASCII).trim()))
            throw new IOException("BC-01: PDF does not match the entry index");
        var dir = Files.createDirectories(game.resolve("create_biotech/manuals").resolve(edition.substring(0, 16)));
        var file = dir.resolve("BC-01-" + entry + ".pdf");
        if (Files.isRegularFile(file)) return file; // Also preserve a reader's local annotations.
        var update = resource(name().toLowerCase(Locale.ROOT) + ".view");
        var temp = Files.createTempFile(dir, ".bc01-", ".tmp");
        try {
            try (var out = Files.newOutputStream(temp)) { out.write(book); out.write(update); }
            try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException unavailable) { Files.move(temp, file); }
        } finally { Files.deleteIfExists(temp); }
        return file;
    }
}
