package com.nobodiiiii.createbiotech.content.biocomputing.library;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;

import net.minecraft.world.entity.Entity;

/** Preserves movement leases across Create's serialize-then-discard carriage virtualization. */
public final class LibraryCarriageVirtualization {
	private static final ThreadLocal<Set<Object>> SERIALIZED_DISCARDS = new ThreadLocal<>();

	private LibraryCarriageVirtualization() {}

	public static void discardSerializedCarriage(CarriageContraptionEntity entity,
		Runnable discard) {
		Objects.requireNonNull(entity, "entity");
		Objects.requireNonNull(discard, "discard");
		Contraption contraption = entity.getContraption();
		discardAfterSerialization(entity, () -> {
			if (contraption instanceof LibraryMovementLeaseOwner owner)
				LibraryContraptionMovement.detachAfterSerialization(owner, entity.level());
		}, discard);
	}

	static void discardAfterSerialization(Object entity, Runnable beforeDiscard,
		Runnable discard) {
		Objects.requireNonNull(entity, "entity");
		Objects.requireNonNull(beforeDiscard, "beforeDiscard");
		Objects.requireNonNull(discard, "discard");
		beforeDiscard.run();
		Set<Object> active = SERIALIZED_DISCARDS.get();
		if (active == null) {
			active = Collections.newSetFromMap(new IdentityHashMap<>());
			SERIALIZED_DISCARDS.set(active);
		}
		active.add(entity);
		try {
			discard.run();
		} finally {
			active.remove(entity);
			if (active.isEmpty())
				SERIALIZED_DISCARDS.remove();
		}
	}

	public static boolean isSerializedCarriageDiscard(Object entity) {
		Set<Object> active = SERIALIZED_DISCARDS.get();
		return entity != null && active != null && active.contains(entity);
	}

	/** Explicit permanent reasons; Create's marked serialized discard is not destruction. */
	public static boolean shouldReleaseForRemoval(String reason,
		boolean serializedCarriageDiscard) {
		return !serializedCarriageDiscard
			&& ("KILLED".equals(reason) || "DISCARDED".equals(reason));
	}
}
