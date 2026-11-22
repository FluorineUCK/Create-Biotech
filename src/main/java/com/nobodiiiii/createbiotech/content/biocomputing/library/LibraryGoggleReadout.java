package com.nobodiiiii.createbiotech.content.biocomputing.library;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeLibrarySummary;
import com.nobodiiiii.createbiotech.content.biocomputing.library.goggles.FactoryGoggleState;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

/** The Library's normal tooltip only describes its own structure and page index. */
public final class LibraryGoggleReadout {
    private static final String PREFIX = "create_biotech.factory_cluster.goggles.";
    private LibraryGoggleReadout() {}

    public static void requireServerThread(BlockEntity owner) {
        if (owner.getLevel() != null && (!(owner.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread()))
            throw new IllegalStateException("Library diagnostics require the server thread");
    }

    static FactoryGoggleState capture(LibraryStructureScanner.State structure, String frequency,
                                      int priority, RecipeLibrarySummary index) {
        return new FactoryGoggleState(List.of(
            row("formation", "@" + structure.name()), row("frequency", "=" + frequency),
            row("priority", priority), row("cache", index.indexedPatterns()),
            row("index", index.indexedPages() + "/" + index.pages()), row("queued_pages", index.queuedPages())));
    }

    private static FactoryGoggleState.Row row(String key, Object value) {
        return new FactoryGoggleState.Row(key, List.of(FactoryGoggleState.text(value.toString())));
    }

    public static List<Component> lines(FactoryGoggleState state) {
        return state.rows().stream().map(row -> (Component) Component.literal("  ")
            .append(Component.translatable(PREFIX + row.key(), row.values().stream()
                .map(LibraryGoggleReadout::value).toArray())).withStyle(ChatFormatting.GRAY)).toList();
    }

    private static Component value(String text) {
        if (text.startsWith("@")) return Component.translatable(PREFIX + "value." + text.substring(1));
        if (text.startsWith("=")) text = text.substring(1);
        return Component.literal(text.isEmpty() ? "—" : text);
    }
}
