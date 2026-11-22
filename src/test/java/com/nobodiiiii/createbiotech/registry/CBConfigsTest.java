package com.nobodiiiii.createbiotech.registry;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Set;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import org.junit.jupiter.api.Test;

class CBConfigsTest {
    @Test void existingLibraryConfigKeysSurviveWithoutAdvertisingRetiredHardware() {
        UnmodifiableConfig section = CBConfigs.SERVER_SPEC.getValues().get("factoryCluster");
        assertEquals(Set.of("libraryMaxMembers", "libraryMaxSpan", "libraryScanBatch",
            "panelRowLimit", "goggleDiagnosticsRequestIntervalTicks"), section.valueMap().keySet());
        var library = CBConfigs.SERVER.library;
        assertEquals(64, library.libraryMaxMembers.getDefault());
        assertEquals(16, library.libraryMaxSpan.getDefault());
        assertEquals(600, library.libraryScanBatch.getDefault());
        assertEquals(512, library.panelRowLimit.getDefault());
        assertEquals(5, library.goggleDiagnosticsRequestIntervalTicks.getDefault());
        assertTrue(library.panelRowLimit.getSpec().test(4096));
        assertFalse(library.panelRowLimit.getSpec().test(4097));
    }
}
