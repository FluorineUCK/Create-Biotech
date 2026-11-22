package com.nobodiiiii.createbiotech.content.biocomputing.library;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Only settings consumed by the physical Library. Existing installations keep their configured paths. */
public final class LibrarySettings {
    public final ModConfigSpec.IntValue libraryMaxMembers, libraryMaxSpan, libraryScanBatch;
    public final ModConfigSpec.IntValue goggleDiagnosticsRequestIntervalTicks, panelRowLimit;

    public LibrarySettings(ModConfigSpec.Builder builder) {
        builder.push("factoryCluster"); // Existing TOML section, not a runtime or device capability.
        libraryMaxMembers = builder.defineInRange("libraryMaxMembers", 64, 1, 1024);
        libraryMaxSpan = builder.defineInRange("libraryMaxSpan", 16, 1, 128);
        libraryScanBatch = builder.defineInRange("libraryScanBatch", 600, 1, 10000);
        goggleDiagnosticsRequestIntervalTicks = builder.defineInRange("goggleDiagnosticsRequestIntervalTicks", 5, 1, 100);
        panelRowLimit = builder.defineInRange("panelRowLimit", 512, 1, 4096);
        builder.pop();
    }
}
