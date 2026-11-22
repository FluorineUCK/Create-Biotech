package com.nobodiiiii.createbiotech.content.biocomputing.library;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Strict stack-owned Library identity/configuration; never book contents or Computer RAM.
 * Frequency and priority retain their legacy routing meaning until that application is migrated.
 * The existing namespaced payload remains byte-shape compatible with recovered physical items.
 */
public record LibraryCoreItemData(boolean configured, CommunicationFrequency frequency,
	int libraryPriority, Optional<UUID> preservedLibraryId, long revision) {
	private static final String ROOT_KEY = "create_biotech:pattern_core";
	private static final String CONFIGURED = "Configured";
	private static final String FREQUENCY = "Frequency";
	private static final String LIBRARY_PRIORITY = "LibraryPriority";
	private static final String PRESERVED_LIBRARY_ID = "PreservedLibraryId";
	private static final String REVISION = "Revision";
	private static final Set<String> REQUIRED_KEYS = Set.of(
		CONFIGURED, FREQUENCY, LIBRARY_PRIORITY, REVISION);
	private static final Set<String> KEYS_WITH_IDENTITY = Set.of(
		CONFIGURED, FREQUENCY, LIBRARY_PRIORITY, PRESERVED_LIBRARY_ID, REVISION);

	public LibraryCoreItemData {
		frequency = Objects.requireNonNull(frequency, "frequency");
		preservedLibraryId = Objects.requireNonNull(preservedLibraryId,
			"preservedLibraryId");
		if (revision < 0)
			throw new IllegalArgumentException("negative item revision");
		if (configured != frequency.configured())
			throw new IllegalArgumentException("item configured state contradicts frequency");
		if (!configured && preservedLibraryId.isPresent() && (libraryPriority != 0 || revision != 0))
			throw new IllegalArgumentException("identity-only core carries routing settings");
	}

    /** Ordinary wrench recovery carries identity, not a fictitious routing frequency. */
    public static LibraryCoreItemData identityOnly(UUID id) {
        return new LibraryCoreItemData(false, CommunicationFrequency.UNCONFIGURED, 0, Optional.of(id), 0);
    }

	public static LibraryCoreItemData unconfigured() {
		return new LibraryCoreItemData(false, CommunicationFrequency.UNCONFIGURED,
			0, Optional.empty(), 0);
	}

	public static LibraryCoreItemData configured(CommunicationFrequency frequency,
		int libraryPriority, UUID preservedLibraryId, long revision) {
		return configured(frequency, libraryPriority,
			Optional.of(Objects.requireNonNull(preservedLibraryId, "preservedLibraryId")),
			revision);
	}

	public static LibraryCoreItemData configured(CommunicationFrequency frequency,
		int libraryPriority, Optional<UUID> preservedLibraryId, long revision) {
		return new LibraryCoreItemData(true, frequency, libraryPriority,
			preservedLibraryId, revision);
	}

	/** Encodes only the current schema below one namespaced custom-data compound. */
	public CompoundTag save() {
		CompoundTag payload = new CompoundTag();
        if (!configured && preservedLibraryId.isPresent()) {
            payload.putUUID(PRESERVED_LIBRARY_ID, preservedLibraryId.orElseThrow());
            CompoundTag root = new CompoundTag();
            root.put(ROOT_KEY, payload);
            return root;
        }
		payload.putBoolean(CONFIGURED, configured);
		frequency.write(payload, FREQUENCY);
		payload.putInt(LIBRARY_PRIORITY, libraryPriority);
		preservedLibraryId.ifPresent(id -> payload.putUUID(PRESERVED_LIBRARY_ID, id));
		payload.putLong(REVISION, revision);
		CompoundTag root = new CompoundTag();
		root.put(ROOT_KEY, payload);
		return root;
	}

	/** Decodes only the current exact payload; malformed and legacy shapes fail closed. */
	public static Optional<LibraryCoreItemData> load(CompoundTag root) {
		Objects.requireNonNull(root, "root");
		if (!root.contains(ROOT_KEY, Tag.TAG_COMPOUND))
			return Optional.empty();
		CompoundTag payload = root.getCompound(ROOT_KEY);
        if (payload.getAllKeys().equals(Set.of(PRESERVED_LIBRARY_ID)) && payload.hasUUID(PRESERVED_LIBRARY_ID))
            return Optional.of(identityOnly(payload.getUUID(PRESERVED_LIBRARY_ID)));
		boolean hasIdentity = payload.contains(PRESERVED_LIBRARY_ID);
		if (!payload.getAllKeys().equals(hasIdentity ? KEYS_WITH_IDENTITY : REQUIRED_KEYS)
			|| !payload.contains(CONFIGURED, Tag.TAG_BYTE)
			|| !canonicalBoolean(payload, CONFIGURED)
			|| !payload.contains(FREQUENCY, Tag.TAG_STRING)
			|| !payload.contains(LIBRARY_PRIORITY, Tag.TAG_INT)
			|| !payload.contains(REVISION, Tag.TAG_LONG)
			|| payload.getLong(REVISION) < 0
			|| (hasIdentity && (!payload.hasUUID(PRESERVED_LIBRARY_ID) || !payload.getBoolean(CONFIGURED))))
			return Optional.empty();
		Optional<CommunicationFrequency> frequency = CommunicationFrequency.read(
			payload, FREQUENCY);
		if (frequency.isEmpty())
			return Optional.empty();
		try {
			return Optional.of(new LibraryCoreItemData(payload.getBoolean(CONFIGURED),
				frequency.orElseThrow(), payload.getInt(LIBRARY_PRIORITY),
				hasIdentity ? Optional.of(payload.getUUID(PRESERVED_LIBRARY_ID))
					: Optional.empty(),
				payload.getLong(REVISION)));
		} catch (IllegalArgumentException invalid) {
			return Optional.empty();
		}
	}

	public static Optional<LibraryCoreItemData> read(ItemStack stack) {
		Objects.requireNonNull(stack, "stack");
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return data == null ? Optional.empty() : load(data.copyTag());
	}

	/** Plain current items begin unconfigured; a present malformed owned payload fails closed. */
	public static Optional<LibraryCoreItemData> readForPlacement(ItemStack stack) {
		Objects.requireNonNull(stack, "stack");
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) return Optional.of(unconfigured());
		CompoundTag root = data.copyTag();
		return root.contains(ROOT_KEY) ? load(root) : Optional.of(unconfigured());
	}

	public static void write(ItemStack stack, LibraryCoreItemData data) {
		Objects.requireNonNull(stack, "stack");
		data = Objects.requireNonNull(data, "data");
		CompoundTag payload = data.save().getCompound(ROOT_KEY).copy();
		CustomData.update(DataComponents.CUSTOM_DATA, stack,
			root -> root.put(ROOT_KEY, payload));
	}

	private static boolean canonicalBoolean(CompoundTag tag, String key) {
		byte value = tag.getByte(key);
		return value == 0 || value == 1;
	}
}
