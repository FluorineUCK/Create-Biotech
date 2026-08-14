package com.nobodiiiii.createbiotech.content.factorycluster.pattern;

import java.util.List;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.nobodiiiii.createbiotech.registry.CBBlockEntityTypes;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

public class PatternStorageCoreBlockEntity extends SmartBlockEntity {
	private static final String LIBRARIAN_SNAPSHOT = "LibrarianSnapshotBox";
	private static final String PENDING_SAFE_RELEASE = "PendingSafeRelease";
	private static final String STRUCTURE_SNAPSHOT = "StructureSnapshot";
	private static final String LIBRARY_INDEX = "LibraryIndex";

	private ItemStack librarianSnapshotBox = ItemStack.EMPTY;
	private boolean pendingSafeRelease;
	@Nullable
	private PatternStructureSnapshot structureSnapshot;
	private PatternLibraryIndex libraryIndex = new PatternLibraryIndex();

	public PatternStorageCoreBlockEntity(BlockPos pos, BlockState state) {
		super(CBBlockEntityTypes.PATTERN_STORAGE_CORE.get(), pos, state);
	}

	@Override
	public void addBehaviours(List<BlockEntityBehaviour> behaviours) {}

	public void installLibrarianSnapshot(ItemStack snapshot) {
		librarianSnapshotBox = snapshot.copy();
		pendingSafeRelease = false;
		setChanged();
	}

	ItemStack snapshot() {
		return librarianSnapshotBox.copy();
	}

	boolean hasSnapshot() {
		return !librarianSnapshotBox.isEmpty()
			&& com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper
				.hasCapturedEntity(librarianSnapshotBox);
	}

	void clearSnapshot() {
		librarianSnapshotBox = ItemStack.EMPTY;
		pendingSafeRelease = false;
		setChanged();
	}

	boolean pendingSafeRelease() {
		return pendingSafeRelease;
	}

	void markPendingSafeRelease() {
		pendingSafeRelease = true;
		setChanged();
	}

	@Nullable
	PatternStructureSnapshot structureSnapshot() {
		return structureSnapshot;
	}

	void setStructureSnapshot(@Nullable PatternStructureSnapshot snapshot) {
		structureSnapshot = snapshot;
		setChanged();
	}

	PatternLibraryIndex libraryIndex() {
		return libraryIndex;
	}

	@Override
	protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		super.write(tag, registries, clientPacket);
		tag.putBoolean(PENDING_SAFE_RELEASE, pendingSafeRelease);
		writeAuthoritativeProjection(tag, clientPacket, authoritative -> {
			if (!librarianSnapshotBox.isEmpty())
				authoritative.put(LIBRARIAN_SNAPSHOT, librarianSnapshotBox.save(registries));
			else
				authoritative.remove(LIBRARIAN_SNAPSHOT);
			if (structureSnapshot != null)
				authoritative.put(STRUCTURE_SNAPSHOT, structureSnapshot.save());
			else
				authoritative.remove(STRUCTURE_SNAPSHOT);
			authoritative.put(LIBRARY_INDEX, libraryIndex.save(registries));
		});
	}

	@Override
	protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		super.read(tag, registries, clientPacket);
		pendingSafeRelease = tag.getBoolean(PENDING_SAFE_RELEASE);
		readAuthoritativeProjection(tag, clientPacket, authoritative -> {
			librarianSnapshotBox = authoritative.contains(LIBRARIAN_SNAPSHOT, Tag.TAG_COMPOUND)
				? ItemStack.parseOptional(registries, authoritative.getCompound(LIBRARIAN_SNAPSHOT))
				: ItemStack.EMPTY;
			structureSnapshot = authoritative.contains(STRUCTURE_SNAPSHOT, Tag.TAG_COMPOUND)
				? PatternStructureSnapshot.load(authoritative.getCompound(STRUCTURE_SNAPSHOT)).orElse(null)
				: null;
			libraryIndex = authoritative.contains(LIBRARY_INDEX, Tag.TAG_COMPOUND)
				? PatternLibraryIndex.load(authoritative.getCompound(LIBRARY_INDEX), registries).index()
				: new PatternLibraryIndex();
		});
	}

	static void writeAuthoritativeProjection(CompoundTag tag, boolean clientPacket,
		Consumer<CompoundTag> writer) {
		if (!clientPacket)
			writer.accept(tag);
	}

	static void readAuthoritativeProjection(CompoundTag tag, boolean clientPacket,
		Consumer<CompoundTag> reader) {
		if (!clientPacket)
			reader.accept(tag);
	}
}
