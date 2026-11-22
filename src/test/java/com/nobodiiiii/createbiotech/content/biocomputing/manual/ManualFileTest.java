package com.nobodiiiii.createbiotech.content.biocomputing.manual;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ManualFileTest {
    @Test void everyEntryOpensThePackagedBookOffline(@TempDir Path game) throws Exception {
        byte[] book = Files.readAllBytes(Path.of("docs/biocomputing/manual/BC-01-活页工位版.pdf"));
        for (ManualFile topic : ManualFile.values()) {
            Path opened = topic.extract(game);
            assertTrue(opened.startsWith(game));
            byte[] bytes = Files.readAllBytes(opened);
            assertArrayEquals(book, Arrays.copyOf(bytes, book.length));
            assertTrue(bytes.length > book.length); // incremental default-view update; not a separate chapter
            assertEquals(opened, topic.extract(game));
        }
    }
}
