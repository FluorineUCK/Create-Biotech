package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser;

import java.util.Locale;
import java.util.BitSet;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternKind;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternIngredient;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;
import net.minecraft.core.registries.BuiltInRegistries;

public record PatternBrowserFilter(String search, Optional<StackKey> exactItem, Optional<PatternKind> kind, BitSet localizedItems) {
    public static final int MAX_SEARCH = 64;
    /** Existing bounded browser request representation, not a gameplay capacity. */
    public static final int MAX_REQUEST_BYTES = 16384;
    public static final PatternBrowserFilter EMPTY = new PatternBrowserFilter("", Optional.empty(), Optional.empty());
    public PatternBrowserFilter(String search, Optional<StackKey> exactItem, Optional<PatternKind> kind) {
        this(search, exactItem, kind, new BitSet());
    }
    public PatternBrowserFilter {
        Objects.requireNonNull(search); Objects.requireNonNull(exactItem); Objects.requireNonNull(kind);
        Objects.requireNonNull(localizedItems);
        if (localizedItems.length() > MAX_REQUEST_BYTES * 8)
            throw new IllegalArgumentException("localized filter exceeds request bound");
        localizedItems = (BitSet) localizedItems.clone();
        if (search.length() > MAX_SEARCH) throw new IllegalArgumentException("browser search too long");
        // Keep the bounded wire field intact: ROOT lowercase can expand Unicode characters.
        search = search.strip();
    }
    @Override public BitSet localizedItems() { return (BitSet) localizedItems.clone(); }
    /** Called on the client: compact synced item IDs carry language-specific matches, not recipe authority. */
    public static PatternBrowserFilter localized(String search, Optional<StackKey> exactItem, Optional<PatternKind> kind) {
        var items = new BitSet();
        String query = search.strip().toLowerCase(Locale.ROOT);
        if (!query.isEmpty() && !query.startsWith("@") && !query.startsWith("#"))
            for (var item : BuiltInRegistries.ITEM)
                if (item.getDefaultInstance().getHoverName().getString().toLowerCase(Locale.ROOT).contains(query))
                    items.set(BuiltInRegistries.ITEM.getId(item));
        return new PatternBrowserFilter(search, exactItem, kind, items);
    }
    public boolean matches(PatternBrowserEntry entry) {
        if (kind.isPresent() && kind.get() != entry.kind()) return false;
        // A selector is not an exact stack. Ghost matching deliberately compares real outputs only.
        if (exactItem.isPresent() && entry.outputs().stream().noneMatch(output -> output.stack().equals(exactItem.get()))) return false;
        if (search.isEmpty()) return true;
        String query = search.toLowerCase(Locale.ROOT);
        if (entry.outputs().stream().anyMatch(output -> matchesStack(output.stack().stack(), query))) return true;
        var allInputs = Stream.concat(entry.inputs().stream(), entry.stages().stream().flatMap(stage -> stage.inputs().stream())).toList();
        if (query.startsWith("#")) {
            String tag = query.substring(1);
            return allInputs.stream().anyMatch(input -> input.tagId() != null && input.tagId().toString().contains(tag)
                || input.itemId() != null && matchesStack(BuiltInRegistries.ITEM.get(input.itemId()).getDefaultInstance(), query));
        }
        if (query.startsWith("@")) return allInputs.stream().anyMatch(input -> input.itemId() != null
            && input.itemId().getNamespace().contains(query.substring(1)));
        if (allInputs.stream().anyMatch(input -> input.itemId() != null
            && matchesStack(BuiltInRegistries.ITEM.get(input.itemId()).getDefaultInstance(), query))) return true;
        Stream<String> selectors = Stream.concat(entry.inputs().stream(), entry.stages().stream().flatMap(stage -> stage.inputs().stream()))
            .map(PatternBrowserFilter::selectorId);
        return Stream.concat(Stream.concat(selectors, entry.outputs().stream().map(output ->
                BuiltInRegistries.ITEM.getKey(output.stack().stack().getItem()).toString())),
            Stream.concat(entry.addresses().stream(), Stream.of(entry.kind().serializedName())))
            .anyMatch(value -> value.toLowerCase(Locale.ROOT).contains(search.toLowerCase(Locale.ROOT)));
    }
    private static String selectorId(PatternIngredient ingredient) {
        return (ingredient.itemId() != null ? ingredient.itemId() : ingredient.tagId()).toString();
    }
    private boolean matchesStack(net.minecraft.world.item.ItemStack stack, String query) {
        var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (query.startsWith("@")) return id.getNamespace().contains(query.substring(1));
        if (query.startsWith("#")) return stack.getTags().anyMatch(tag -> tag.location().toString().contains(query.substring(1)));
        return stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains(query) || id.toString().contains(query)
            || !stack.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME)
                && !stack.getComponentsPatch().entrySet().stream().anyMatch(entry -> entry.getKey() == net.minecraft.core.component.DataComponents.ITEM_NAME)
                && localizedItems.get(BuiltInRegistries.ITEM.getId(stack.getItem()));
    }
}
