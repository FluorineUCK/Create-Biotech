package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;
import java.util.HashSet;
import javax.annotation.Nullable;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.ProductionLibraryRouting;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryEditRestriction;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryResident;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Historical recipe/production application envelope, not the generic physical-book storage format. */
public final class RecipeLibraryArchive {
    public static final String ROOT = "PatternLibrary", ID = "LibraryId", SERVER = "ServerState",
        ROUTING = "RoutingConfiguration", MOVEMENT = "MovementClaimToken";
    private RecipeLibraryArchive() {}

    public static CompoundTag save(UUID libraryId, UUID movementToken, LibraryEditRestriction restriction,
        LibraryResident resident, RecipeLibraryMaintenance recipes, CompoundTag indexArchive,
        ProductionLibraryRouting routing, HolderLookup.Provider registries) {
        var pattern = new CompoundTag(); pattern.putUUID(ID, libraryId); restriction.write(pattern);
        var server = new CompoundTag(); resident.write(server, registries);
        RecipeMaintenanceArchive.writeMetadata(recipes, server);
        server.put(RecipeMaintenanceArchive.INDEX, indexArchive.copy());
        server.put(ROUTING, routing.save()); server.putUUID(MOVEMENT, movementToken);
        pattern.put(SERVER, server); var root = new CompoundTag(); root.put(ROOT, pattern); return root;
    }

    public static <T> Optional<Prepared<T>> prepare(CompoundTag root, UUID fallbackMovement,
        LibraryResident resident, HolderLookup.Provider registries, int memberLimit,
        @Nullable RecipeSourceLocation space, IndexCodec<T> codec) {
        if (!hasType(root, ROOT, Tag.TAG_COMPOUND)) return Optional.empty();
        var pattern = root.getCompound(ROOT);
        var expected = pattern.contains(LibraryEditRestriction.BINDING)
            ? Set.of(ID, LibraryEditRestriction.BINDING, LibraryEditRestriction.INVALID, SERVER)
            : Set.of(ID, LibraryEditRestriction.INVALID, SERVER);
        if (!pattern.getAllKeys().equals(expected) || !pattern.hasUUID(ID)
            || !hasType(pattern, SERVER, Tag.TAG_COMPOUND)) return Optional.empty();
        var server = pattern.getCompound(SERVER);
        boolean prior = !server.contains(RecipeMaintenanceArchive.TOPOLOGIES);
        if (!validServerFields(server, memberLimit, prior)
            || !codec.accepts(server.getCompound(RecipeMaintenanceArchive.INDEX))
            || prior && !codec.hasPriorShape(server.getCompound(RecipeMaintenanceArchive.INDEX))) return Optional.empty();
        var routing = ProductionLibraryRouting.load(server.getCompound(ROUTING));
        if (routing.isEmpty()) return Optional.empty();
        var binding = LibraryEditRestriction.read(pattern);
        if (binding.isEmpty()) return Optional.empty();
        UUID movement = server.hasUUID(MOVEMENT) ? server.getUUID(MOVEMENT) : Objects.requireNonNull(fallbackMovement);
        var nextResident = resident.prepareLoad(server, registries);
        var index = codec.load(server.getCompound(RecipeMaintenanceArchive.INDEX), registries);
        var recipe = RecipeMaintenanceArchive.prepare(server, index.pageData(), index.hadCorruption(), prior, memberLimit, space);
        if (recipe.isEmpty()) return Optional.empty();
        var nextRecipe = recipe.orElseThrow(); var nextBinding = binding.orElseThrow();
        boolean needsSave = nextResident.hadCorruption()
            || nextRecipe.hadCorruption() || nextRecipe.refreshPending();
        return Optional.of(new Prepared<>(pattern.getUUID(ID), movement, nextBinding, nextResident,
            nextRecipe, index.state(), routing.orElseThrow(), needsSave));
    }

    private static boolean validServerFields(CompoundTag server, int memberLimit, boolean prior) {
        var expected = new HashSet<>(Set.of(LibraryResident.PENDING_RELEASE, RecipeMaintenanceArchive.STATE,
            RecipeMaintenanceArchive.INDEX, ROUTING));
        if (!prior) expected.add(RecipeMaintenanceArchive.TOPOLOGIES);
        for (String optional : Set.of(LibraryResident.SNAPSHOT, RecipeMaintenanceArchive.LAYOUT, MOVEMENT))
            if (server.contains(optional)) expected.add(optional);
        return server.getAllKeys().equals(expected)
            && hasType(server, LibraryResident.PENDING_RELEASE, Tag.TAG_BYTE)
            && (server.getByte(LibraryResident.PENDING_RELEASE) == 0 || server.getByte(LibraryResident.PENDING_RELEASE) == 1)
            && RecipeMaintenanceArchive.validMetadata(server, memberLimit, prior)
            && hasType(server, RecipeMaintenanceArchive.INDEX, Tag.TAG_COMPOUND)
            && (!server.contains(MOVEMENT) || server.hasUUID(MOVEMENT))
            && hasType(server, ROUTING, Tag.TAG_COMPOUND)
            && (!server.contains(LibraryResident.SNAPSHOT) || hasType(server, LibraryResident.SNAPSHOT, Tag.TAG_COMPOUND));
    }

    private static boolean hasType(CompoundTag tag, String key, int type) {
        var value = tag.get(key); return value != null && value.getId() == type;
    }

    /** A historical application codec supplies the exact data index referenced by its restored state. */
    public interface IndexCodec<T> {
        default boolean accepts(CompoundTag tag) { return true; }
        boolean hasPriorShape(CompoundTag tag);
        LoadedIndex<T> load(CompoundTag tag, HolderLookup.Provider registries);
    }

    public record LoadedIndex<T>(T state, RecipePageIndex pageData, boolean hadCorruption) {
        public LoadedIndex { Objects.requireNonNull(state); Objects.requireNonNull(pageData); }
    }

    public record Prepared<T>(UUID libraryId, UUID movementToken, LibraryEditRestriction restriction,
        LibraryResident.Loaded resident, RecipeMaintenanceArchive.Prepared recipe, T indexState,
        ProductionLibraryRouting routing, boolean needsSave) {}
}
