package com.nobodiiiii.createbiotech.content.biocomputing.peripheral;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReaderRequestTest {
    @Test void requestUsesOnlyBoundedCapturedIndicesAndAFixedMenuVersion() {
        var nonce = UUID.randomUUID(); var indices = new ArrayList<>(List.of(2, 0));
        var request = new ReaderRequest(1, nonce, 3, ReaderRequest.Action.GRANT, 0, indices); indices.clear();
        assertEquals(List.of(2, 0), request.components()); assertTrue(request.matches(1, nonce, 3));
        assertFalse(request.matches(1, UUID.randomUUID(), 3)); assertFalse(request.matches(1, nonce, 4)); assertFalse(request.matches(2, nonce, 3));
    }
    @Test void malformedSelectionCannotGrantAllComponentsByAccident() {
        var nonce = UUID.randomUUID();
        for (var indices : List.of(List.<Integer>of(), List.of(-1), List.of(128), List.of(1, 1)))
            assertThrows(IllegalArgumentException.class, () -> new ReaderRequest(1, nonce, 0, ReaderRequest.Action.GRANT, 0, indices));
        assertThrows(IllegalArgumentException.class, () -> new ReaderRequest(1, nonce, 0, ReaderRequest.Action.GRANT, 128, List.of(0)));
        assertThrows(IllegalArgumentException.class, () -> new ReaderRequest(1, nonce, 0, ReaderRequest.Action.REVOKE, -1, List.of(0)));
        assertEquals(List.of(), new ReaderRequest(1, nonce, 0, ReaderRequest.Action.REFRESH, -1, List.of()).components());
    }
}
