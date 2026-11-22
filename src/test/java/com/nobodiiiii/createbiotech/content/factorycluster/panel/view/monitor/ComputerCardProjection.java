package com.nobodiiiii.createbiotech.content.factorycluster.panel.view.monitor;

import java.util.Objects;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionProfession;

/** Immutable summary for one formed local Computer member. */
public record ComputerCardProjection(UUID memberId, String shortMemberId,
	ProductionProfession residentProfession, int residentLevel, int usedSlots, int totalSlots,
	AggregateState aggregateState, int processCount) {
	public ComputerCardProjection {
		memberId = Objects.requireNonNull(memberId, "memberId");
		shortMemberId = Objects.requireNonNull(shortMemberId, "shortMemberId");
		residentProfession = Objects.requireNonNull(residentProfession, "residentProfession");
		aggregateState = Objects.requireNonNull(aggregateState, "aggregateState");
		if (!shortMemberId.equals(shortId(memberId)))
			throw new IllegalArgumentException("Computer short ID is not canonical");
		if (residentLevel < 1 || residentLevel > 5 || usedSlots < 0 || totalSlots < 0
			|| usedSlots > totalSlots || processCount < 0 || processCount > totalSlots)
			throw new IllegalArgumentException("invalid Computer card state");
	}

	public ComputerCardProjection(UUID memberId, ProductionProfession residentProfession,
		int residentLevel, int usedSlots, int totalSlots, AggregateState aggregateState,
		int processCount) {
		this(memberId, shortId(memberId), residentProfession, residentLevel, usedSlots, totalSlots,
			aggregateState, processCount);
	}

	/** Stable display-only identity; full local UUID remains available solely for selection. */
	public static String shortId(UUID id) {
		String compact = Objects.requireNonNull(id, "id").toString().replace("-", "");
		return compact.substring(compact.length() - 8);
	}

	public static boolean isCanonicalShortId(String value) {
		if (value == null || value.length() != 8) return false;
		for (int index = 0; index < value.length(); index++) {
			char digit = value.charAt(index);
			if (!(digit >= '0' && digit <= '9' || digit >= 'a' && digit <= 'f')) return false;
		}
		return true;
	}

	public enum AggregateState {
		IDLE,
		RUN,
		SLP,
		FAULT
	}
}
