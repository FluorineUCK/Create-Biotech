package com.nobodiiiii.createbiotech.content.factorycluster.createbridge;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.lang.ref.WeakReference;

import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;

import net.createmod.catnip.math.BlockFace;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.items.IItemHandler;

/** Stable description of the exact inventory face attached to one Packager. */
public record InventoryIdentity(SpaceAddress inventoryAddress, Direction accessFace,
	ResourceLocation blockId, Optional<ResourceLocation> blockEntityTypeId,
	UUID attachmentInstanceId, long attachmentGeneration) {
	public InventoryIdentity {
		inventoryAddress = Objects.requireNonNull(inventoryAddress, "inventoryAddress");
		accessFace = Objects.requireNonNull(accessFace, "accessFace");
		blockId = Objects.requireNonNull(blockId, "blockId");
		blockEntityTypeId = Objects.requireNonNull(blockEntityTypeId, "blockEntityTypeId");
		attachmentInstanceId = Objects.requireNonNull(attachmentInstanceId,
			"attachmentInstanceId");
		if (attachmentGeneration < 0)
			throw new IllegalArgumentException("Attachment generation cannot be negative");
	}

	/** Captures physical topology plus a persistent Biotech-owned binding generation. */
	public static Optional<InventoryIdentity> capture(PackagerBlockEntity packager,
		PersistentAttachmentBindingRegistry bindings) {
		packager = Objects.requireNonNull(packager, "packager");
		bindings = Objects.requireNonNull(bindings, "bindings");
		Level level = packager.getLevel();
		if (level == null || packager.targetInventory == null
			|| !packager.targetInventory.hasInventory()) return Optional.empty();
		BlockFace face = packager.targetInventory.getTarget().getOpposite();
		BlockEntity target = level.getBlockEntity(face.getPos());
		ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(
			level.getBlockState(face.getPos()).getBlock());
		Optional<ResourceLocation> blockEntityType = target == null ? Optional.empty()
			: Optional.of(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(target.getType()));
		PhysicalAttachment physical = new PhysicalAttachment(
			SpaceAddress.capture(level, face.getPos()), face.getFace(), blockId, blockEntityType);
		SpaceAddress packagerAddress = SpaceAddress.capture(level, packager.getBlockPos());
		IItemHandler inventory = packager.targetInventory.getInventory();
		LiveAttachmentBinding capabilityBinding = new LiveAttachmentBinding(target, inventory,
			inventory.getSlots());
		return bindings.resolve(packagerAddress, physical, capabilityBinding)
			.map(binding -> new InventoryIdentity(
			physical.inventoryAddress(), physical.accessFace(), physical.blockId(),
			physical.blockEntityTypeId(), binding.instanceId(), binding.generation()));
	}

	public record PhysicalAttachment(SpaceAddress inventoryAddress, Direction accessFace,
		ResourceLocation blockId, Optional<ResourceLocation> blockEntityTypeId) {
		public PhysicalAttachment {
			inventoryAddress = Objects.requireNonNull(inventoryAddress, "inventoryAddress");
			accessFace = Objects.requireNonNull(accessFace, "accessFace");
			blockId = Objects.requireNonNull(blockId, "blockId");
			blockEntityTypeId = Objects.requireNonNull(blockEntityTypeId, "blockEntityTypeId");
		}
	}

	public record AttachmentBinding(UUID instanceId, long generation) {
		public AttachmentBinding {
			instanceId = Objects.requireNonNull(instanceId, "instanceId");
			if (generation < 0)
				throw new IllegalArgumentException("Attachment generation cannot be negative");
		}
	}

	public record PersistedAttachmentBinding(PhysicalAttachment physical,
		AttachmentBinding binding) {
		public PersistedAttachmentBinding {
			physical = Objects.requireNonNull(physical, "physical");
			binding = Objects.requireNonNull(binding, "binding");
		}
	}

	public interface AttachmentBindingStore {
		Optional<PersistedAttachmentBinding> load(SpaceAddress packager);
		void persist(SpaceAddress packager, PersistedAttachmentBinding binding);
	}

	/** Non-persistent live topology proof; the registry retains its world objects only weakly. */
	public record LiveAttachmentBinding(Object blockEntityIdentity, Object capabilityIdentity,
		int slots) {
		public LiveAttachmentBinding {
			capabilityIdentity = Objects.requireNonNull(capabilityIdentity, "capabilityIdentity");
			if (slots < 0) throw new IllegalArgumentException("Capability slot count cannot be negative");
		}
	}

	/**
	 * Biotech-owned binding authority. A replaced backing inventory, changed physical topology, or
	 * process restart advances the durable generation before an identity is exposed. Capability
	 * wrappers are not inventory identity when a stable backing BlockEntity is available.
	 */
	public static final class PersistentAttachmentBindingRegistry {
		private final AttachmentBindingStore store;
		private final Map<SpaceAddress, LiveBindingReference> liveCapabilities = new HashMap<>();

		public PersistentAttachmentBindingRegistry(AttachmentBindingStore store) {
			this.store = Objects.requireNonNull(store, "store");
		}

		public synchronized Optional<AttachmentBinding> resolve(SpaceAddress packager,
			PhysicalAttachment physical, LiveAttachmentBinding capabilityBinding) {
			packager = Objects.requireNonNull(packager, "packager");
			physical = Objects.requireNonNull(physical, "physical");
			capabilityBinding = Objects.requireNonNull(capabilityBinding, "capabilityBinding");
			try {
				liveCapabilities.entrySet().removeIf(entry -> entry.getValue().cleared());
				Optional<PersistedAttachmentBinding> loaded = Objects.requireNonNull(
					store.load(packager), "persisted attachment binding");
				boolean liveKnown = liveCapabilities.containsKey(packager);
				boolean rebound = liveKnown
					&& !liveCapabilities.get(packager).matches(capabilityBinding);
				boolean reattachedAfterRestart = !liveKnown && loaded.isPresent();
				boolean physicalChanged = loaded.isPresent()
					&& !loaded.orElseThrow().physical().equals(physical);
				AttachmentBinding binding;
				if (loaded.isEmpty()) {
					binding = new AttachmentBinding(UUID.randomUUID(), 0);
				} else if (rebound || reattachedAfterRestart || physicalChanged) {
					AttachmentBinding prior = loaded.orElseThrow().binding();
					binding = new AttachmentBinding(prior.instanceId(),
						Math.addExact(prior.generation(), 1));
				} else {
					binding = loaded.orElseThrow().binding();
				}
				if (loaded.isEmpty() || rebound || reattachedAfterRestart || physicalChanged)
					store.persist(packager, new PersistedAttachmentBinding(physical, binding));
				liveCapabilities.put(packager, new LiveBindingReference(capabilityBinding));
				return Optional.of(binding);
			} catch (RuntimeException unavailableOrExhausted) {
				return Optional.empty();
			}
		}
	}

	private static final class LiveBindingReference {
		private final WeakReference<Object> blockEntity;
		private final boolean hasBlockEntity;
		private final WeakReference<Object> capability;
		private final int slots;

		private LiveBindingReference(LiveAttachmentBinding binding) {
			hasBlockEntity = binding.blockEntityIdentity() != null;
			blockEntity = new WeakReference<>(binding.blockEntityIdentity());
			capability = new WeakReference<>(binding.capabilityIdentity());
			slots = binding.slots();
		}

		private boolean matches(LiveAttachmentBinding binding) {
			if (slots != binding.slots()) return false;
			if (hasBlockEntity || binding.blockEntityIdentity() != null)
				return hasBlockEntity && blockEntity.get() == binding.blockEntityIdentity();
			return capability.get() == binding.capabilityIdentity();
		}

		private boolean cleared() {
			return hasBlockEntity ? blockEntity.get() == null : capability.get() == null;
		}
	}
}
