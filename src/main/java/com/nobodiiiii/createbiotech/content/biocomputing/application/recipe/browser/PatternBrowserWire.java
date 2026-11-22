package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserFilter;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.BrowserFence;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserEntry;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserSummary;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserPage;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternPageKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeSourceLocation;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternRecord;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternBody;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternKind;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StandardPatternBody;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.SequencedPatternBody;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternStage;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternIngredient;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternOutput;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternItemCodecs;

import java.util.ArrayList;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.*;
import io.netty.buffer.Unpooled;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.nbt.NbtAccounter;

/** Byte envelopes bound allocation before decoding nested stacks/NBT. No unrestricted readNbt. */
public final class PatternBrowserWire {
    public static final int VERSION = 2;
    public static final int MAX_REQUEST_BYTES = PatternBrowserFilter.MAX_REQUEST_BYTES;
    public static final int MAX_RESPONSE_BYTES = 131072;
    private PatternBrowserWire() {}
    public static void writeEnvelope(RegistryFriendlyByteBuf destination, int limit, Consumer<RegistryFriendlyByteBuf> write) {
        var inner = new RegistryFriendlyByteBuf(Unpooled.buffer(256, limit - 8), destination.registryAccess(), destination.getConnectionType());
        try {
            write.accept(inner);
            destination.writeVarInt(VERSION); destination.writeVarInt(inner.readableBytes()); destination.writeBytes(inner);
        } finally { inner.release(); }
    }
    public static <T> T readEnvelope(RegistryFriendlyByteBuf source, int limit, Function<RegistryFriendlyByteBuf, T> read) {
        if (source.readVarInt() != VERSION) throw new IllegalArgumentException("unsupported browser packet version");
        int size = source.readVarInt();
        if (size < 0 || size > limit - 8 || size > source.readableBytes()) throw new IllegalArgumentException("invalid browser packet size");
        var inner = new RegistryFriendlyByteBuf(source.readRetainedSlice(size), source.registryAccess(), source.getConnectionType());
        try {
            T result = read.apply(inner);
            if (inner.isReadable()) throw new IllegalArgumentException("trailing browser bytes");
            return result;
        } finally { inner.release(); }
    }
    public static void writeAddress(RegistryFriendlyByteBuf b, RecipeSourceLocation address) {
        b.writeResourceLocation(address.dimension().location()); b.writeBoolean(address.subLevelId() != null);
        if (address.subLevelId() != null) b.writeUUID(address.subLevelId()); b.writeBlockPos(address.localPos());
    }
    public static RecipeSourceLocation readAddress(RegistryFriendlyByteBuf b) {
        var dimension = ResourceKey.create(Registries.DIMENSION, b.readResourceLocation());
        UUID sub = b.readBoolean() ? b.readUUID() : null; return new RecipeSourceLocation(dimension, sub, b.readBlockPos());
    }
    private static void writeSourceLocation(RegistryFriendlyByteBuf b, RecipeSourceLocation source) {
        writeAddress(b, source);
    }
    private static RecipeSourceLocation readSourceLocation(RegistryFriendlyByteBuf b) {
        return readAddress(b);
    }
    public static void writeFence(RegistryFriendlyByteBuf b, BrowserFence f) {
        b.writeUUID(f.sessionId()); writeSourceLocation(b, f.coreAddress()); b.writeUUID(f.libraryId()); b.writeVarLong(f.generation());
    }
    public static BrowserFence readFence(RegistryFriendlyByteBuf b) { return new BrowserFence(b.readUUID(), readSourceLocation(b), b.readUUID(), b.readVarLong()); }
    static void writePage(RegistryFriendlyByteBuf b, PatternBrowserPage p) {
        b.writeEnum(p.status()); writeFence(b, p.fence()); b.writeVarInt(p.pageIndex()); b.writeVarInt(p.totalMatches());
        b.writeVarInt(p.totalPatterns()); b.writeBoolean(p.reindexing()); b.writeVarInt(p.invalidPages()); b.writeVarInt(p.unsupportedPages());
        b.writeVarInt(p.entries().size());
        for (var row : p.entries()) {
            b.writeUUID(row.patternId()); b.writeEnum(row.kind()); writeSourceLocation(b, row.source().shelf());
            b.writeVarInt(row.source().slot()); b.writeVarInt(row.source().page());
            b.writeNbt(PatternItemCodecs.saveStackKey(row.mainOutput(), b.registryAccess())); b.writeVarInt(row.outputCount());
            b.writeBoolean(row.storedPriorityOverride().isPresent()); if (row.storedPriorityOverride().isPresent()) b.writeInt(row.storedPriorityOverride().getAsInt());
            b.writeBoolean(row.overrideActive()); b.writeInt(row.effectivePriority());
        }
        b.writeBoolean(p.detail().isPresent());
        p.detail().ifPresent(detail -> writeDetail(b, detail));
    }
    static PatternBrowserPage readPage(RegistryFriendlyByteBuf b) {
        var status = b.readEnum(PatternBrowserPage.Status.class); var fence = readFence(b);
        int page = b.readVarInt(), total = b.readVarInt(), patterns = b.readVarInt(); boolean reindexing = b.readBoolean();
        int invalid = b.readVarInt(), unsupported = b.readVarInt(), count = b.readVarInt();
        if (count < 0 || count > PatternBrowserPage.PAGE_SIZE) throw new IllegalArgumentException("too many browser rows");
        var rows = new ArrayList<PatternBrowserSummary>(count);
        for (int i = 0; i < count; i++) {
            var id = b.readUUID(); var kind = b.readEnum(PatternKind.class);
            var source = new PatternPageKey(readSourceLocation(b), b.readVarInt(), b.readVarInt());
            var outputTag = b.readNbt(NbtAccounter.create(MAX_RESPONSE_BYTES));
            if (!(outputTag instanceof net.minecraft.nbt.CompoundTag compound)) throw new IllegalArgumentException("missing display stack");
            var output = PatternItemCodecs.loadStackKey(compound, b.registryAccess()).orElseThrow(() -> new IllegalArgumentException("invalid display stack"));
            int amount = b.readVarInt();
            var stored = b.readBoolean() ? OptionalInt.of(b.readInt()) : OptionalInt.empty();
            rows.add(new PatternBrowserSummary(id, kind, source, output, amount, stored, b.readBoolean(), b.readInt()));
        }
        Optional<PatternBrowserEntry> detail = Optional.empty();
        if (b.readBoolean()) detail = Optional.of(readDetail(b));
        return new PatternBrowserPage(status, fence, page, total, patterns, reindexing, invalid, unsupported, rows, detail);
    }

    private static void writeDetail(RegistryFriendlyByteBuf b, PatternBrowserEntry entry) {
        b.writeUUID(entry.patternId()); writeSourceLocation(b, entry.source().shelf());
        b.writeVarInt(entry.source().slot()); b.writeVarInt(entry.source().page()); b.writeEnum(entry.kind());
        b.writeBoolean(entry.storedPriorityOverride().isPresent()); entry.storedPriorityOverride().ifPresent(b::writeInt);
        writeIngredients(b, entry.inputs()); b.writeVarInt(entry.outputs().size());
        for (var output : entry.outputs()) b.writeNbt(PatternItemCodecs.saveOutput(output, b.registryAccess()));
        if (entry.pattern().body() instanceof StandardPatternBody standard) {
            // Empty is never a valid address. Old readers reject a shaped detail rather than flatten it.
            if (standard.grid().shaped()) {
                b.writeUtf(""); b.writeByte(1); b.writeByte(standard.grid().cells().size());
                for (int slot : standard.grid().cells()) b.writeByte(slot);
            }
            b.writeUtf(standard.address());
        }
        else {
            b.writeVarInt(entry.stages().size());
            for (var stage : entry.stages()) { b.writeVarInt(stage.index()); b.writeUtf(stage.address()); writeIngredients(b, stage.inputs()); }
        }
    }
    private static PatternBrowserEntry readDetail(RegistryFriendlyByteBuf b) {
        var id = b.readUUID(); var source = new PatternPageKey(readSourceLocation(b), b.readVarInt(), b.readVarInt());
        var kind = b.readEnum(PatternKind.class); var priority = b.readBoolean() ? OptionalInt.of(b.readInt()) : OptionalInt.empty();
        var inputs = readIngredients(b); int outputsCount = readCount(b);
        var outputs = new ArrayList<PatternOutput>(outputsCount);
        for (int i = 0; i < outputsCount; i++) {
            var tag = b.readNbt(NbtAccounter.create(MAX_RESPONSE_BYTES));
            if (!(tag instanceof net.minecraft.nbt.CompoundTag compound)) throw new IllegalArgumentException("missing output");
            outputs.add(PatternItemCodecs.loadOutput(compound, b.registryAccess()).orElseThrow(() -> new IllegalArgumentException("invalid output")));
        }
        PatternBody body;
        if (kind == PatternKind.STANDARD) {
            String address = b.readUtf(25);
            var grid = com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.CraftingSlots.NONE;
            if (address.isEmpty()) {
                if (b.readUnsignedByte()!=1) throw new IllegalArgumentException("Unknown crafting detail version");
                int n=b.readUnsignedByte();
                if (n<1 || n>9) throw new IllegalArgumentException("Invalid crafting detail size");
                var cells=new ArrayList<Integer>(n);
                for(int i=0;i<n;i++) cells.add((int)b.readUnsignedByte());
                grid=new com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.CraftingSlots(cells);
                address=b.readUtf(25);
            }
            body = new StandardPatternBody(address,grid);
        }
        else {
            int count = readCount(b); var stages = new ArrayList<PatternStage>(count);
            for (int i = 0; i < count; i++) stages.add(new PatternStage(b.readVarInt(), b.readUtf(), readIngredients(b)));
            body = new SequencedPatternBody(stages);
        }
        return new PatternBrowserEntry(new PatternRecord(id, source, inputs, outputs, body, priority));
    }
    private static void writeIngredients(RegistryFriendlyByteBuf b, java.util.List<PatternIngredient> ingredients) {
        b.writeVarInt(ingredients.size());
        for (var input : ingredients) {
            b.writeBoolean(input.itemId() != null); b.writeResourceLocation(input.itemId() != null ? input.itemId() : input.tagId());
            b.writeVarInt(input.count()); b.writeNbt(PatternItemCodecs.saveIngredient(input, b.registryAccess()).get("Components"));
        }
    }
    /** Decode snapshot facts, not a newly authored source: removed tags stay visible during reindex. */
    private static java.util.List<PatternIngredient> readIngredients(RegistryFriendlyByteBuf b) {
        int count = readCount(b); var result = new ArrayList<PatternIngredient>(count);
        for (int i = 0; i < count; i++) {
            boolean item = b.readBoolean(); var selector = b.readResourceLocation(); int amount = b.readVarInt();
            var tag = b.readNbt(NbtAccounter.create(MAX_RESPONSE_BYTES));
            if (!(tag instanceof net.minecraft.nbt.CompoundTag)) throw new IllegalArgumentException("missing component predicate");
            var predicate = net.minecraft.core.component.DataComponentPredicate.CODEC.parse(
                b.registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE), tag).getOrThrow();
            result.add(new PatternIngredient(item ? selector : null, item ? null : selector, amount, predicate));
        }
        return java.util.List.copyOf(result);
    }
    private static int readCount(RegistryFriendlyByteBuf b) {
        int count = b.readVarInt();
        if (count < 0 || count > net.minecraft.world.item.component.WritableBookContent.PAGE_EDIT_LENGTH)
            throw new IllegalArgumentException("browser detail collection exceeds source representation bound");
        return count;
    }
    static void writeDiagnostics(RegistryFriendlyByteBuf b, PatternBrowserDiagnostics d) {
        b.writeEnum(d.structure()); b.writeVarInt(d.ordinaryShelves()); b.writeVarInt(d.chiseledShelves());
        b.writeVarInt(d.searchBudget()); b.writeVarInt(d.replyQueues()); b.writeVarInt(d.activeQueries()); b.writeVarInt(d.readyReplies());
    }
    static PatternBrowserDiagnostics readDiagnostics(RegistryFriendlyByteBuf b) {
        return new PatternBrowserDiagnostics(b.readEnum(com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryStructureScanner.State.class), b.readVarInt(), b.readVarInt(),
            b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt());
    }
}
