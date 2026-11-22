package com.nobodiiiii.createbiotech.content.factorycluster.communication;

import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfile;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

/** Durable, revision-fenced list of a Wandering Trader Computer's external frequencies. */
public record ExternalListenerConfiguration(long revision,
	List<CommunicationFrequency> frequencies) {
	private static final int VERSION = 1;
	private static final java.util.Set<String> KEYS = java.util.Set.of("Version", "Revision",
		"Frequencies");

	public ExternalListenerConfiguration {
		if (revision < 0) throw new IllegalArgumentException("negative external listener revision");
		frequencies = List.copyOf(Objects.requireNonNull(frequencies, "frequencies"));
		if (frequencies.size() > ProductionResourceProfile.WANDERING_TRADER_PROCESS_SLOTS)
			throw new IllegalArgumentException("too many external listener frequencies");
		LinkedHashSet<CommunicationFrequency> distinct = new LinkedHashSet<>();
		for (CommunicationFrequency frequency : frequencies) {
			frequency = Objects.requireNonNull(frequency, "listener frequency");
			if (!frequency.configured())
				throw new IllegalArgumentException("external listener frequency is unconfigured");
			if (!distinct.add(frequency))
				throw new IllegalArgumentException("external listener frequency is duplicated");
		}
	}

	public static ExternalListenerConfiguration empty() {
		return new ExternalListenerConfiguration(0, List.of());
	}

	public CompoundTag save() {
		CompoundTag tag = new CompoundTag();
		tag.putInt("Version", VERSION);
		tag.putLong("Revision", revision);
		ListTag values = new ListTag();
		for (CommunicationFrequency frequency : frequencies)
			values.add(StringTag.valueOf(frequency.value()));
		tag.put("Frequencies", values);
		return tag;
	}

	public static Optional<ExternalListenerConfiguration> load(CompoundTag tag) {
		if (tag == null || !tag.getAllKeys().equals(KEYS)
			|| !tag.contains("Version", Tag.TAG_INT) || tag.getInt("Version") != VERSION
			|| !tag.contains("Revision", Tag.TAG_LONG) || !tag.contains("Frequencies", Tag.TAG_LIST))
			return Optional.empty();
		Tag rawFrequencies = tag.get("Frequencies");
		if (!(rawFrequencies instanceof ListTag values)
			|| (!values.isEmpty() && values.getElementType() != Tag.TAG_STRING))
			return Optional.empty();
		if (values.size() > ProductionResourceProfile.WANDERING_TRADER_PROCESS_SLOTS) return Optional.empty();
		try {
			List<CommunicationFrequency> frequencies = new ArrayList<>(values.size());
			for (Tag value : values) {
				if (!(value instanceof StringTag string)) return Optional.empty();
				frequencies.add(CommunicationFrequency.of(string.getAsString()));
			}
			return Optional.of(new ExternalListenerConfiguration(tag.getLong("Revision"), frequencies));
		} catch (IllegalArgumentException exception) {
			return Optional.empty();
		}
	}
}
