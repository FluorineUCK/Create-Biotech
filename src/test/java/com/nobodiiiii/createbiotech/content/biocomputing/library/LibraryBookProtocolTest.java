package com.nobodiiiii.createbiotech.content.biocomputing.library;
import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.menu.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramEditorWire;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LibraryBookProtocolTest {
    @Test void requestOwnsPayloadAndRejectsOversizedEnvelope() {
        byte[] value = {1, 2}; UUID nonce = UUID.randomUUID();
        var packet = new LibraryBookRequest(2, nonce, 3, 4, LibraryBookRequest.Action.PUT, 0, "x", LibraryEntry.Kind.DATA, value);
        value[0] = 9; assertEquals(1, packet.value()[0]); packet.value()[0] = 8; assertEquals(1, packet.value()[0]);
        assertThrows(IllegalArgumentException.class, () -> new LibraryBookRequest(2, nonce, 3, 4, LibraryBookRequest.Action.PUT,
            0, "x", LibraryEntry.Kind.DATA, new byte[ProgramEditorWire.MAX_BYTES + 1]));
    }
    @Test void responseRequiresMatchingMenuSessionLatestIntentAndNondecreasingRevision() {
        UUID nonce = UUID.randomUUID();
        assertTrue(LibraryBookMenu.acceptsResponse(2, nonce, 3, 4, 2, nonce, 3, 4));
        assertFalse(LibraryBookMenu.acceptsResponse(2, nonce, 3, 4, 1, nonce, 3, 4));
        assertFalse(LibraryBookMenu.acceptsResponse(2, nonce, 3, 4, 2, UUID.randomUUID(), 3, 4));
        assertFalse(LibraryBookMenu.acceptsResponse(2, nonce, 3, 4, 2, nonce, 2, 4));
        assertFalse(LibraryBookMenu.acceptsResponse(2, nonce, 3, 4, 2, nonce, 3, 2));
    }
}
