package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProgramDocumentCompressionTest {
    @Test void compressedDocumentsRejectForgedSizesVersionsCorruptionAndOversizedEnvelopes() {
        var bytes = ProgramDocumentWire.encode(LibraryReadDriverDocument.document(), LibraryReadDriverTest.ARCHIVE);
        assertEquals(0x42435001, java.nio.ByteBuffer.wrap(bytes).getInt());
        int actual = java.nio.ByteBuffer.wrap(bytes).getInt(4);
        for (int length : new int[]{-1, 0, 1, actual - 1, actual + 1, ProgramDocumentWire.MAX_EXPANDED_BYTES + 1, Integer.MAX_VALUE}) {
            byte[] invalid = bytes.clone(); java.nio.ByteBuffer.wrap(invalid).putInt(4, length);
            assertThrows(IllegalArgumentException.class, () -> ProgramDocumentWire.decode(invalid, LibraryReadDriverTest.ARCHIVE));
        }
        byte[] version = bytes.clone(); version[3]++;
        assertThrows(IllegalArgumentException.class, () -> ProgramDocumentWire.decode(version, LibraryReadDriverTest.ARCHIVE));
        byte[] corrupt = bytes.clone(); corrupt[8] = 0;
        assertThrows(IllegalArgumentException.class, () -> ProgramDocumentWire.decode(corrupt, LibraryReadDriverTest.ARCHIVE));
        assertThrows(IllegalArgumentException.class, () -> ProgramDocumentWire.decode(
            Arrays.copyOf(bytes, ProgramEditorWire.MAX_BYTES + 1), LibraryReadDriverTest.ARCHIVE));
    }
    @Test void smallHistoricalDocumentsKeepTheExactUncompressedEnvelopeAndDoNotWidenOtherEditors() {
        var small = new ProgramWorkspace(); small.setFlow(Optional.of(FlowDocumentBody.empty()));
        var document = small.snapshot();
        byte[] legacy = ProgramEditorWire.encodeValue(ProgramDocumentCodec.encode(document), LibraryReadDriverTest.ARCHIVE);
        assertArrayEquals(legacy, ProgramDocumentWire.encode(document, LibraryReadDriverTest.ARCHIVE));
        assertEquals(document, ProgramDocumentWire.decode(legacy, LibraryReadDriverTest.ARCHIVE));
        assertThrows(IllegalArgumentException.class, () -> ProgramEditorWire.encodeValue(
            ProgramDocumentCodec.encode(LibraryReadDriverDocument.document()), LibraryReadDriverTest.ARCHIVE));
        var large = new ProgramWorkspace();
        String id = large.root().add(new com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationGraph.Constant(
            new com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue.TextValue("x".repeat(600000))), 0, 0);
        large.root().output("large", id);
        assertThrows(IllegalArgumentException.class, () -> ProgramDocumentWire.encode(large.snapshot(), LibraryReadDriverTest.ARCHIVE),
            "Compressibility never overrides the expanded safety bound");
    }
    @Test void completePublicDriverFitsExistingWireBoundWithoutDroppingGraphsOrLayouts() {
        var document = LibraryReadDriverDocument.document();
        var bytes = ProgramDocumentWire.encode(document, LibraryReadDriverTest.ARCHIVE);
        assertTrue(bytes.length <= ProgramEditorWire.MAX_BYTES);
        assertEquals(document, ProgramDocumentWire.decode(bytes, LibraryReadDriverTest.ARCHIVE));
        for (int length : new int[]{0, 1, 5, bytes.length - 1})
            assertThrows(IllegalArgumentException.class, () -> ProgramDocumentWire.decode(Arrays.copyOf(bytes, length), LibraryReadDriverTest.ARCHIVE));
        assertThrows(IllegalArgumentException.class, () -> ProgramDocumentWire.decode(Arrays.copyOf(bytes, bytes.length + 1), LibraryReadDriverTest.ARCHIVE));
        assertThrows(RuntimeException.class, () -> ProgramDocumentWire.decode(bytes, new ArchiveLimits(1, 1, 1)));
    }
}
