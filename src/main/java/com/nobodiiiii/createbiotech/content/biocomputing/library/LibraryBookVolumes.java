package com.nobodiiiii.createbiotech.content.biocomputing.library;

import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.item.ItemStack;

/** A physical book's exact name, separate from its existing entry schema and original pages. */
public final class LibraryBookVolumes {
    public static final int MAX_NAME = 128; // Named-volume protocol bound, not entry capacity.
    private final DataComponentType<String> component;
    public LibraryBookVolumes(DataComponentType<String> component) { this.component = Objects.requireNonNull(component); }
    public Optional<String> name(ItemStack book) {
        requireBook(book);
        String name = book.get(component);
        if (name != null) requireName(name);
        return Optional.ofNullable(name);
    }
    public ItemStack rename(ItemStack book, String name) {
        requireBook(book); requireName(name);
        var copy = book.copy(); copy.set(component, name); return copy;
    }
    public static void requireName(String name) {
        if (name == null || name.isBlank() || name.length() > MAX_NAME) throw new LibraryBookAccess.Failure("invalid_volume");
    }
    private static void requireBook(ItemStack book) {
        if (!LibraryBookMedia.supports(Objects.requireNonNull(book))) throw new LibraryBookAccess.Failure("unsupported_medium");
    }
}
