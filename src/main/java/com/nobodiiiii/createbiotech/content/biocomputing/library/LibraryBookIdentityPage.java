package com.nobodiiiii.createbiotech.content.biocomputing.library;

import java.util.ArrayList;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.item.component.WrittenBookContent;

/** Human-readable metadata only. The text never grants access or supplies the medium's identity. */
public final class LibraryBookIdentityPage {
    private static final String PREFIX = "Library\n\nUUID:\n";
    private LibraryBookIdentityPage() {}

    public static String text(UUID medium) { return PREFIX + java.util.Objects.requireNonNull(medium); }

    /** Exact metadata recognition, not a source of authority. Other text still gets normal recipe diagnostics. */
    public static boolean isIdentityPage(String raw) {
        if (!raw.startsWith(PREFIX) || raw.length() != PREFIX.length() + 36) return false;
        try { return text(UUID.fromString(raw.substring(PREFIX.length()))).equals(raw); }
        catch (IllegalArgumentException invalid) { return false; }
    }

    public static ItemStack describe(ItemStack book, UUID medium) {
        var copy = book.copy(); var text = text(medium);
        if (book.is(Items.WRITTEN_BOOK)) {
            var content = book.getOrDefault(DataComponents.WRITTEN_BOOK_CONTENT, WrittenBookContent.EMPTY);
            var label = Component.literal(text);
            // Do not flatten rich text or erase click/hover/style data, even on visually empty signed pages.
            if (content.pages().stream().anyMatch(page -> label.equals(page.raw()) && page.filtered().map(label::equals).orElse(true))) return copy;
            var pages = new ArrayList<>(content.pages()); pages.add(Filterable.passThrough((Component) label));
            copy.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(content.title(), content.author(),
                content.generation(), pages, content.resolved()));
            return copy;
        }
        if (!book.is(Items.WRITABLE_BOOK)) throw new LibraryBookAccess.Failure("unsupported_medium");
        var content = book.getOrDefault(DataComponents.WRITABLE_BOOK_CONTENT, WritableBookContent.EMPTY);
        var pages = new ArrayList<>(content.pages());
        if (pages.stream().anyMatch(page -> text.equals(page.raw()) && page.filtered().map(text::equals).orElse(true))) return copy;
        var label = Filterable.passThrough(text);
        for (int i = 0; i < pages.size(); i++) {
            var page = pages.get(i);
            if (page.raw().isBlank() && page.filtered().map(String::isBlank).orElse(true)) {
                pages.set(i, label); copy.set(DataComponents.WRITABLE_BOOK_CONTENT, new WritableBookContent(pages)); return copy;
            }
        }
        if (pages.size() >= WritableBookContent.MAX_PAGES) throw new LibraryBookAccess.Failure("identity_page_full");
        pages.add(label); copy.set(DataComponents.WRITABLE_BOOK_CONTENT, new WritableBookContent(pages)); return copy;
    }
}
