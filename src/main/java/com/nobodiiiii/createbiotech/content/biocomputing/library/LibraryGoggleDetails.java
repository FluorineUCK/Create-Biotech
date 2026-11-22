package com.nobodiiiii.createbiotech.content.biocomputing.library;

import com.nobodiiiii.createbiotech.content.biocomputing.library.goggles.FactoryAdvancedGoggleProjection;
import com.nobodiiiii.createbiotech.content.biocomputing.library.goggles.FactoryGoggleTarget;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Read-only diagnostics for the pointed Library; no production registry or other device is consulted. */
public final class LibraryGoggleDetails {
    private LibraryGoggleDetails() {}

    public static int interval() { return configured(CBConfigs.SERVER.library.goggleDiagnosticsRequestIntervalTicks); }
    private static int configured(ModConfigSpec.IntValue value) {
        return CBConfigs.SERVER_SPEC.isLoaded() ? value.get() : value.getDefault();
    }

    public static FactoryAdvancedGoggleProjection capture(LibraryBlockEntity library, FactoryGoggleTarget target, int interval) {
        if (!(library.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread())
            throw new IllegalStateException("Library diagnostics require the Minecraft server thread");
        int limit = Math.min(configured(CBConfigs.SERVER.library.panelRowLimit), FactoryAdvancedGoggleProjection.MAX_ROWS);
        var rows = new Rows(limit);
        library.diagnosticIndexRows().forEach(row -> rows.add(rows.library, row));
        var summary = library.summary();
        rows.add(rows.formation, "formation=" + summary.reason());
        var structure = library.structureSnapshot();
        if (structure != null) rows.add(rows.formation, "bounds=" + structure.min().toShortString() + " .. " + structure.max().toShortString());
        rows.add(rows.library, "library=" + library.memberId() + " routingRevision=" + library.routingConfiguration().revision());
        rows.add(rows.library, "indexedPages=" + summary.indexedPages() + "/" + summary.pages()
            + " indexedPatterns=" + summary.indexedPatterns() + " queuedPages=" + summary.queuedPages() + " scanning=" + summary.scanning());
        summary.errors().stream().limit(rows.remaining).forEach(line -> rows.add(rows.errors, line));
        return new FactoryAdvancedGoggleProjection(target, level.getServer().overworld().getGameTime(), interval, limit,
            List.of(), List.of(), List.of(), List.of(), rows.library, List.of(), rows.formation, rows.errors);
    }

    private static final class Rows {
        private int remaining;
        private final List<String> library = new ArrayList<>(), formation = new ArrayList<>(), errors = new ArrayList<>();
        Rows(int limit) { remaining = limit; }
        void add(List<String> section, Object value) {
            if (remaining > 0) { section.add(FactoryAdvancedGoggleProjection.text(value)); remaining--; }
        }
    }
}
