package com.nobodiiiii.createbiotech.content.biocomputing.library.drive;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;

/** Local physical ownership, not a message address or an authorization to inspect another Computer's RAM. */
public record LibraryControlBinding(UUID computer, BlockPos member, BlockPos terminal, Direction face, Optional<UUID> controller) {
    public LibraryControlBinding {
        Objects.requireNonNull(computer); member = Objects.requireNonNull(member).immutable();
        terminal = Objects.requireNonNull(terminal).immutable(); Objects.requireNonNull(face); Objects.requireNonNull(controller);
        if (!BlockPos.of(member.asLong()).equals(member) || !BlockPos.of(terminal.asLong()).equals(terminal)
            || controller.isEmpty() && !member.equals(terminal)) throw new IllegalArgumentException("Invalid control binding");
    }
    public CompoundTag save() {
        var tag = new CompoundTag(); tag.putUUID("Computer", computer); tag.putLong("Member", member.asLong());
        tag.putLong("Terminal", terminal.asLong()); tag.putString("Face", face.getName());
        controller.ifPresent(id -> tag.putUUID("Controller", id)); return tag;
    }
    public static LibraryControlBinding load(CompoundTag tag) {
        var keys = new HashSet<>(Set.of("Computer", "Member", "Terminal", "Face"));
        if (tag.contains("Controller")) keys.add("Controller");
        if (!tag.getAllKeys().equals(keys) || !tag.hasUUID("Computer") || !tag.contains("Member", Tag.TAG_LONG)
            || !tag.contains("Terminal", Tag.TAG_LONG) || !tag.contains("Face", Tag.TAG_STRING)
            || tag.contains("Controller") && !tag.hasUUID("Controller")) throw new IllegalArgumentException("Invalid control binding");
        var face = Direction.byName(tag.getString("Face"));
        if (face == null || !face.getName().equals(tag.getString("Face"))) throw new IllegalArgumentException("Invalid control face");
        return new LibraryControlBinding(tag.getUUID("Computer"), BlockPos.of(tag.getLong("Member")), BlockPos.of(tag.getLong("Terminal")),
            face, tag.hasUUID("Controller") ? Optional.of(tag.getUUID("Controller")) : Optional.empty());
    }
    /** Human-facing configuration metadata; identities never become client-selected authority tokens. */
    public DataValue.RecordValue summary() {
        return new DataValue.RecordValue(Map.of("kind", new DataValue.TextValue(controller.isPresent() ? "cluster" : "computer"),
            "member", new DataValue.TextValue(member.toShortString()), "terminal", new DataValue.TextValue(terminal.toShortString()),
            "face", new DataValue.TextValue(face.getName())));
    }
}
