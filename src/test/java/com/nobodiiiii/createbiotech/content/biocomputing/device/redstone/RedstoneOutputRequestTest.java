package com.nobodiiiii.createbiotech.content.biocomputing.device.redstone;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RedstoneOutputRequestTest {
    @Test void menuSelectionIsFencedBySessionAndRevisionNotClientSuppliedWorldCoordinates() {
        var nonce = UUID.randomUUID();
        var request = new RedstoneOutputRequest(3, nonce, 7, RedstoneOutputRequest.Action.GRANT, 0);
        assertTrue(request.matches(3, nonce, 7));
        assertFalse(request.matches(4, nonce, 7));
        assertFalse(request.matches(3, UUID.randomUUID(), 7));
        assertFalse(request.matches(3, nonce, 8));
        assertThrows(IllegalArgumentException.class,
            () -> new RedstoneOutputRequest(3, nonce, 7, RedstoneOutputRequest.Action.GRANT, -1));
        assertThrows(IllegalArgumentException.class,
            () -> new RedstoneOutputRequest(3, nonce, 7, RedstoneOutputRequest.Action.REVOKE, 0));
    }
}
