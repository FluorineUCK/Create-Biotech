package com.nobodiiiii.createbiotech.content.factorycluster.pattern;

import java.util.List;

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
		if (!librarianSnapshotBox.isEmpty())
			tag.put(LIBRARIAN_SNAPSHOT, librarianSnapshotBox.save(registries));
		else
			tag.remove(LIBRARIAN_SNAPSHOT);
		tag.putBoolean(PENDING_SAFE_RELEASE, pendingSafeRelease);
		if (structureSnapshot != null)
			tag.put(STRUCTURE_SNAPSHOT, structureSnapshot.save());
		else
			tag.remove(STRUCTURE_SNAPSHOT);
		tag.put(LIBRARY_INDEX, libraryIndex.save(registries));
	}

	@Override
	protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		super.read(tag, registries, clientPacket);
		librarianSnapshotBox = tag.contains(LIBRARIAN_SNAPSHOT, Tag.TAG_COMPOUND)
			? ItemStack.parseOptional(registries, tag.getCompound(LIBRARIAN_SNAPSHOT))
			: ItemStack.EMPTY;
		pendingSafeRelease = tag.getBoolean(PENDING_SAFE_RELEASE);
		structureSnapshot = tag.contains(STRUCTURE_SNAPSHOT, Tag.TAG_COMPOUND)
			? PatternStructureSnapshot.load(tag.getCompound(STRUCTURE_SNAPSHOT)).orElse(null) : null;
		libraryIndex = tag.contains(LIBRARY_INDEX, Tag.TAG_COMPOUND)
			? PatternLibraryIndex.load(tag.getCompound(LIBRARY_INDEX), registries).index()
			: new PatternLibraryIndex();
	}
}
