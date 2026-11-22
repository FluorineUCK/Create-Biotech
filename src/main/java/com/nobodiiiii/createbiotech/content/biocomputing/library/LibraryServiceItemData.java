package com.nobodiiiii.createbiotech.content.biocomputing.library;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.component.CustomData;

/** Historical private item component. Carries independent service and local-control archives; neither owns book contents. */
public record LibraryServiceItemData(UUID libraryId, Tag archive, Optional<Tag> control, Optional<Tag> drive, Optional<Tag> serial) {
    public static final String COMPONENT_ID = "create_biotech:biocomputing_library_service";
    public LibraryServiceItemData(UUID libraryId, Tag archive) { this(libraryId, Objects.requireNonNull(archive), Optional.empty()); }
    public LibraryServiceItemData(UUID libraryId, Tag archive, Optional<Tag> control) { this(libraryId, archive, control, Optional.empty()); }
    public LibraryServiceItemData(UUID libraryId, Tag archive, Optional<Tag> control, Optional<Tag> drive) { this(libraryId, archive, control, drive, Optional.empty()); }
    public LibraryServiceItemData {
        Objects.requireNonNull(libraryId);
        archive = archive == null ? null : archive.copy(); control = Objects.requireNonNull(control).map(Tag::copy);
        drive = Objects.requireNonNull(drive).map(Tag::copy);
        serial = Objects.requireNonNull(serial).map(Tag::copy);
        if (archive == null && control.isEmpty() && drive.isEmpty() && serial.isEmpty() || archive != null && archive.getId() == Tag.TAG_END
            || control.filter(t -> t.getId() == Tag.TAG_END).isPresent()
            || drive.filter(t -> t.getId() == Tag.TAG_END).isPresent()
            || serial.filter(t -> t.getId() == Tag.TAG_END).isPresent()) throw new IllegalArgumentException("Missing Library archive");
    }
    @Override public Tag archive() { return archive == null ? null : archive.copy(); }
    @Override public Optional<Tag> control() { return control.map(Tag::copy); }
    @Override public Optional<Tag> drive() { return drive.map(Tag::copy); }
    @Override public Optional<Tag> serial() { return serial.map(Tag::copy); }

    public CustomData encode() {
        var tag = new CompoundTag();
        tag.putInt("Version", serial.isPresent() ? 4 : drive.isPresent() ? 3 : control.isPresent() ? 2 : 1); tag.putUUID("LibraryId", libraryId);
        if (archive != null) tag.put("Service", archive());
        control().ifPresent(value -> tag.put("Control", value));
        drive().ifPresent(value -> tag.put("Drive", value));
        serial().ifPresent(value -> tag.put("Serial", value));
        return CustomData.of(tag);
    }

    public static LibraryServiceItemData decode(CustomData data) {
        var tag = Objects.requireNonNull(data).copyTag();
        Set<String> keys = new java.util.HashSet<>(Set.of("Version", "LibraryId"));
        switch (tag.getInt("Version")) {
            case 1 -> keys.add("Service");
            case 2 -> { keys.add("Control"); if (tag.contains("Service")) keys.add("Service"); }
            case 3 -> { keys.add("Drive"); if (tag.contains("Service")) keys.add("Service"); if (tag.contains("Control")) keys.add("Control"); }
            case 4 -> { keys.add("Serial"); for (String optional : Set.of("Service", "Control", "Drive")) if (tag.contains(optional)) keys.add(optional); }
            default -> throw new IllegalArgumentException("Unsupported Library item envelope");
        }
        if (!tag.getAllKeys().equals(keys) || !tag.contains("Version", Tag.TAG_INT)
            || !tag.hasUUID("LibraryId"))
            throw new IllegalArgumentException("Invalid Library service item envelope");
        // Malformed or oversized INNER data is retained for the service's existing fail-closed diagnosis.
        return new LibraryServiceItemData(tag.getUUID("LibraryId"), tag.get("Service"), Optional.ofNullable(tag.get("Control")), Optional.ofNullable(tag.get("Drive")), Optional.ofNullable(tag.get("Serial")));
    }
}
