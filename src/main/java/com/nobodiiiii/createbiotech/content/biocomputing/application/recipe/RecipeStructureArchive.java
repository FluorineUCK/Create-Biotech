package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe;

import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryStructureScanner;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.world.level.ChunkPos;

/** Historical recipe geometry representation; not a live physical-access grant. */
public final class RecipeStructureArchive {
    private static final Set<String> KEYS = Set.of("State", "Members", "Ordinary", "Chiseled", "Min", "Max", "QueueCount", "Chunks");
    private RecipeStructureArchive() {}

    public static CompoundTag save(LibraryStructureScanner.ScanResult scan) {
        var s = Objects.requireNonNull(scan.snapshot());
        validate(s);
        var tag = new CompoundTag();
        tag.putString("State", scan.state().name());
        tag.put("Members", positions(s.members())); tag.put("Ordinary", positions(s.ordinaryShelves()));
        tag.put("Chiseled", positions(s.mediaShelves()));
        tag.putLong("Min", s.min().asLong()); tag.putLong("Max", s.max().asLong());
        // Historical recipe queue projection, never generic Library capacity.
        tag.putInt("QueueCount", 1 + s.ordinaryShelves().size());
        var chunks = new ListTag();
        chunks(s).stream().sorted(Comparator.comparingLong(ChunkPos::toLong)).forEach(pos -> {
            var child = new CompoundTag(); child.putInt("X", pos.x); child.putInt("Z", pos.z); chunks.add(child);
        });
        tag.put("Chunks", chunks); return tag;
    }

    public static Optional<LibraryStructureScanner.ScanResult> load(CompoundTag tag) {
        if (!tag.getAllKeys().equals(KEYS) || !hasType(tag, "State", Tag.TAG_STRING)
            || !hasType(tag, "Members", Tag.TAG_LIST) || !hasType(tag, "Ordinary", Tag.TAG_LIST)
            || !hasType(tag, "Chiseled", Tag.TAG_LIST) || !hasType(tag, "Chunks", Tag.TAG_LIST)
            || !hasType(tag, "Min", Tag.TAG_LONG) || !hasType(tag, "Max", Tag.TAG_LONG)
            || !hasType(tag, "QueueCount", Tag.TAG_INT)) return Optional.empty();
        try {
            var state = LibraryStructureScanner.State.valueOf(tag.getString("State"));
            var snapshot = new LibraryStructureScanner.Snapshot(readPositions((ListTag)tag.get("Members")),
                readPositions((ListTag)tag.get("Ordinary")), readPositions((ListTag)tag.get("Chiseled")),
                BlockPos.of(tag.getLong("Min")), BlockPos.of(tag.getLong("Max")));
            validate(snapshot);
            if (tag.getInt("QueueCount") != 1 + snapshot.ordinaryShelves().size()) return Optional.empty();
            var actualChunks = new HashSet<ChunkPos>();
            for (var value : (ListTag)tag.get("Chunks")) {
                if (!(value instanceof CompoundTag child) || !child.getAllKeys().equals(Set.of("X", "Z"))
                    || !hasType(child, "X", Tag.TAG_INT) || !hasType(child, "Z", Tag.TAG_INT)
                    || !actualChunks.add(new ChunkPos(child.getInt("X"), child.getInt("Z")))) return Optional.empty();
            }
            if (!actualChunks.equals(chunks(snapshot))) return Optional.empty();
            return Optional.of(new LibraryStructureScanner.ScanResult(state, snapshot));
        } catch (IllegalArgumentException invalid) { return Optional.empty(); }
    }

    /** Shared geometry invariant for historical projections and independent archive decoding. */
    public static void validate(LibraryStructureScanner.Snapshot s) {
        var members = new HashSet<>(s.members());
        var ordinary = new HashSet<>(s.ordinaryShelves()); var media = new HashSet<>(s.mediaShelves());
        if (members.isEmpty() || members.size() != s.members().size()
            || ordinary.size() != s.ordinaryShelves().size() || media.size() != s.mediaShelves().size()
            || !Collections.disjoint(ordinary, media)) throw new IllegalArgumentException("Invalid recipe structure membership");
        var classified = new HashSet<>(ordinary); classified.addAll(media);
        members.remove(s.members().getFirst());
        if (!members.equals(classified) || !s.ordinaryShelves().equals(s.members().stream().filter(ordinary::contains).toList())
            || !s.mediaShelves().equals(s.members().stream().filter(media::contains).toList()))
            throw new IllegalArgumentException("Inconsistent recipe structure classifications");
        var min = new BlockPos(s.members().stream().mapToInt(BlockPos::getX).min().orElseThrow(),
            s.members().stream().mapToInt(BlockPos::getY).min().orElseThrow(), s.members().stream().mapToInt(BlockPos::getZ).min().orElseThrow());
        var max = new BlockPos(s.members().stream().mapToInt(BlockPos::getX).max().orElseThrow(),
            s.members().stream().mapToInt(BlockPos::getY).max().orElseThrow(), s.members().stream().mapToInt(BlockPos::getZ).max().orElseThrow());
        if (!s.min().equals(min) || !s.max().equals(max)) throw new IllegalArgumentException("Inconsistent recipe structure bounds");
    }

    private static Set<ChunkPos> chunks(LibraryStructureScanner.Snapshot s) {
        var result = new HashSet<ChunkPos>(); s.members().forEach(pos -> result.add(new ChunkPos(pos))); return result;
    }
    private static ListTag positions(List<BlockPos> positions) {
        var result = new ListTag(); positions.forEach(pos -> result.add(LongTag.valueOf(pos.asLong()))); return result;
    }
    private static List<BlockPos> readPositions(ListTag tag) {
        var result = new ArrayList<BlockPos>();
        for (var value : tag) {
            if (!(value instanceof LongTag pos)) throw new IllegalArgumentException("Invalid position encoding");
            result.add(BlockPos.of(pos.getAsLong()));
        }
        return List.copyOf(result);
    }
    static boolean hasType(CompoundTag tag, String key, int type) {
        var value = tag.get(key); return value != null && value.getId() == type;
    }
}
