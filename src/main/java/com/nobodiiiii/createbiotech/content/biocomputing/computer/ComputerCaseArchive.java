package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.resident.ComputerResident;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Owns the case identity and one physical resident, not a production engine or program RAM.
 * V5 children stay in their original envelope. Only an actual change of occupant replaces the
 * resident field; even malformed/future envelopes can be carried through an ordinary save. */
final class ComputerCaseArchive {
    static final String ROOT = "ComputerData";
    private static final String ID = "ComputerId", VERSION = "Version";
    private static final int PHYSICAL = 6, HISTORICAL = 5;
    private static final Set<String> PHYSICAL_FIELDS = Set.of(VERSION, ID, ComputerResident.SNAPSHOT);
    private static final Set<String> V5_FIELDS = Set.of(VERSION, ID, ComputerResident.SNAPSHOT,
        "TopologyVersion", "Profile", "Structure", "Binding", "BindingInvalid", "Epoch", "Faults",
        "ExternalListeners", "OrchestrationRuntime");

    private @Nullable UUID id = UUID.randomUUID();
    private @Nullable Tag envelope;
    private boolean occupantChanged;

    ComputerCaseArchive() {
        var fresh = new CompoundTag();
        fresh.putInt(VERSION, PHYSICAL);
        fresh.putUUID(ID, id);
        envelope = fresh;
    }

    Optional<UUID> identity() { return Optional.ofNullable(id); }

    void load(CompoundTag parent, ComputerResident resident, HolderLookup.Provider registries) {
        Tag source = parent.get(ROOT);
        envelope = source == null ? null : source.copy();
        occupantChanged = false;
        id = null;
        resident.clear();
        if (!(envelope instanceof CompoundTag data)) return;
        Tag occupant = data.get(ComputerResident.SNAPSHOT);
        boolean known = data.contains(VERSION, Tag.TAG_INT) && switch (data.getInt(VERSION)) {
            case PHYSICAL -> PHYSICAL_FIELDS.containsAll(data.getAllKeys());
            case HISTORICAL -> V5_FIELDS.containsAll(data.getAllKeys());
            default -> false;
        };
        if (!known || !data.hasUUID(ID)) {
            // May recover the original physical box on removal, never run from untrusted identity.
            if (occupant != null) resident.preserveRaw(occupant);
            return;
        }
        id = data.getUUID(ID);
        resident.commitLoad(resident.prepareLoad(occupant, registries));
    }

    void occupantChanged() {
        occupantChanged = true;
        if (envelope instanceof CompoundTag data) data.remove(ComputerResident.SNAPSHOT);
    }

    void write(CompoundTag parent, ComputerResident resident, HolderLookup.Provider registries) {
        if (envelope == null) { parent.remove(ROOT); return; }
        var data = envelope.copy();
        if (occupantChanged && data instanceof CompoundTag compound) resident.write(compound, registries);
        parent.put(ROOT, data);
    }
}
