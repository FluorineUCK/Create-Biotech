package com.nobodiiiii.createbiotech.content.factorycluster.panel.view.network;

import java.util.List;
import java.util.Objects;

import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfile;

/** Local-only opaque CAS input for one currently loaded Wandering listener editor. */
public record ListenerEditorProjection(String target, List<CommunicationFrequency> frequencies,
	long configurationRevision) {
	private static final String PREFIX = "listener-";

	public ListenerEditorProjection {
		target = Objects.requireNonNull(target, "target");
		frequencies = List.copyOf(Objects.requireNonNull(frequencies, "frequencies"));
		if (!isOpaqueTarget(target) || configurationRevision < 0
			|| frequencies.size() > ProductionResourceProfile.WANDERING_TRADER_PROCESS_SLOTS
			|| frequencies.stream().anyMatch(Objects::isNull))
			throw new IllegalArgumentException("invalid local listener editor projection");
	}

	public static boolean isOpaqueTarget(String value) {
		if (value == null || value.length() != PREFIX.length() + 16 || !value.startsWith(PREFIX))
			return false;
		for (int index = PREFIX.length(); index < value.length(); index++) {
			char digit = value.charAt(index);
			if (!(digit >= '0' && digit <= '9' || digit >= 'a' && digit <= 'f')) return false;
		}
		return true;
	}
}
