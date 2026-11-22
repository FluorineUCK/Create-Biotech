package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryBookAccess;
import com.nobodiiiii.createbiotech.content.biocomputing.library.drive.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.NativeNbtCodec;
import java.util.*;
import java.util.function.Function;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.WritableBookContent;

/** Read-only physical ROM projection of the sole index. No parsing, search, identity writes or secondary cache. */
public final class RecipePageRomAdapter implements LibraryPositionedReadSource.RecipeReader {
    private final RecipePageIndex index;
    private final Function<BlockPos, RecipeSourceLocation> location;
    private final HolderLookup.Provider registries;
    private final NativeNbtCodec.Limits limits;
    public RecipePageRomAdapter(RecipePageIndex index, Function<BlockPos, RecipeSourceLocation> location,
        HolderLookup.Provider registries, LibraryDriveLimits limits) {
        this.index = Objects.requireNonNull(index); this.location = Objects.requireNonNull(location);
        this.registries = Objects.requireNonNull(registries);
        this.limits = new NativeNbtCodec.Limits((int)Math.min(Integer.MAX_VALUE, limits.bufferBytes()), limits.bufferBytes(), limits.archive());
    }
    @Override public LibraryReadHead.Sample read(BlockPos shelf, int slot, int page, ItemStack book) {
        try { return capture(shelf, slot, page, book); }
        catch (NativeNbtCodec.Failure exhausted) { throw failure(exhausted.code().name().toLowerCase(Locale.ROOT)); }
    }
    private LibraryReadHead.Sample capture(BlockPos shelf, int slot, int page, ItemStack book) {
        if (page < 0 || page >= WritableBookContent.MAX_PAGES) throw failure("invalid_page");
        if (!book.is(Items.WRITABLE_BOOK)) throw failure("unsupported_medium");
        var content = book.getOrDefault(DataComponents.WRITABLE_BOOK_CONTENT, WritableBookContent.EMPTY);
        if (page >= content.pages().size()) throw failure("invalid_page");
        var key = new PatternPageKey(location.apply(shelf), slot, page);
        var fact = index.pageSnapshot(key);
        if (fact.status().equals("reindexing") || fact.status().equals("unindexed")) throw failure(fact.status());
        var slots = new ArrayList<ItemStack>(Collections.nCopies(6, ItemStack.EMPTY)); slots.set(slot, book);
        if (!PatternJsonParser.sha256LowerHex(RecipeBookPages.readPage(slots, key)).equals(fact.fingerprint())) throw failure("changed");
        if (!fact.status().equals("valid")) throw failure(switch (fact.status()) {
            case "blank" -> "blank_page"; case "unsupported" -> "unsupported_recipe"; default -> "invalid_recipe";
        });
        var record = fact.record().orElseThrow();
        var fields = new LinkedHashMap<String, DataValue>();
        fields.put("page", integer(page)); fields.put("pages", integer(content.pages().size()));
        fields.put("pattern_id", text(record.patternId().toString())); fields.put("type", text(record.kind().serializedName()));
        fields.put("inputs", ingredients(record.inputs()));
        fields.put("outputs", new DataValue.ListValue(record.outputs().stream().<DataValue>map(output -> {
            var stack = output.stack().stack(); var encoded = (CompoundTag)stack.save(registries);
            return object(Map.of("item", text(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()),
                "count", integer(output.count()), "components", nativeValue(encoded.getCompound("components"))));
        }).toList()));
        if (record.body() instanceof StandardPatternBody standard) {
            var body = new HashMap<String, DataValue>();
            body.put("address", text(standard.address()));
            if (standard.grid().shaped()) body.put("crafting_slots", new DataValue.ListValue(
                standard.grid().cells().stream().<DataValue>map(cell -> integer(cell)).toList()));
            fields.put("body", object(body));
        }
        else fields.put("body", object(Map.of("stages", new DataValue.ListValue(((SequencedPatternBody)record.body()).stages().stream()
            .<DataValue>map(stage -> object(Map.of("index", integer(stage.index()), "address", text(stage.address()), "inputs", ingredients(stage.inputs())))).toList()))));
        fields.put("priority", record.priorityOverride().isPresent() ? integer(record.priorityOverride().getAsInt()) : DataValue.NullValue.INSTANCE);
        fields.put("fingerprint", text(fact.fingerprint()));
        var source = nativeValue(PatternDataCodecs.savePageKey(key)); fields.put("source", source);
        // Conservative owned full-book witness. The read-head charges BOTH witness and typed result to its pending buffer.
        var fence = object(Map.of("source", source, "fingerprint", text(fact.fingerprint()), "book", nativeValue(saveBook(book))));
        return new LibraryReadHead.Sample(fence, object(fields));
    }
    private DataValue.ListValue ingredients(List<PatternIngredient> ingredients) {
        return new DataValue.ListValue(ingredients.stream().<DataValue>map(input -> {
            var encoded = PatternItemCodecs.saveIngredient(input, registries);
            return object(Map.of("item", input.itemId() == null ? DataValue.NullValue.INSTANCE : text(input.itemId().toString()),
                "tag", input.tagId() == null ? DataValue.NullValue.INSTANCE : text(input.tagId().toString()),
                "count", integer(input.count()), "components", nativeValue(encoded.getCompound("Components"))));
        }).toList());
    }
    private Tag saveBook(ItemStack book) {
        try { return book.save(registries); }
        catch (IllegalStateException invalid) { throw failure("invalid_contents"); }
    }
    private DataValue.NbtValue nativeValue(Tag tag) { return NativeNbtCodec.snapshot(tag, limits); }
    private static DataValue.RecordValue object(Map<String, DataValue> fields) { return new DataValue.RecordValue(fields); }
    private static DataValue.TextValue text(String value) { return new DataValue.TextValue(value); }
    private static DataValue.IntegerValue integer(long value) { return new DataValue.IntegerValue(value); }
    private static LibraryBookAccess.Failure failure(String code) { return new LibraryBookAccess.Failure(code); }
}

