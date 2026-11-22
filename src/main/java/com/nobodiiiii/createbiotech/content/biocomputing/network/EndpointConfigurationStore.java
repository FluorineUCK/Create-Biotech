package com.nobodiiiii.createbiotech.content.biocomputing.network;

import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

/** Owns the endpoint's isolated normal-save payload, including opaque invalid future data. */
public final class EndpointConfigurationStore {
	private Tag raw;
	private EndpointConfiguration configuration;
	private String diagnostic;

	public void read(@Nullable Tag tag) {
		raw = tag == null ? null : tag.copy();
		configuration = null; diagnostic = null;
		if (raw == null) return;
		try { configuration = decode(raw); }
		catch (IllegalArgumentException invalid) { diagnostic = invalid.getMessage(); }
	}
	public @Nullable Tag write() { return raw == null ? null : raw.copy(); }
	public Optional<EndpointConfiguration> configuration() { return Optional.ofNullable(configuration); }
	public Optional<String> diagnostic() { return Optional.ofNullable(diagnostic); }
	public void configure(EndpointConfiguration config) {
		var encoded = encode(java.util.Objects.requireNonNull(config));
		raw = encoded; configuration = config; diagnostic = null;
	}
	public void disable() {
		if (configuration != null) configure(new EndpointConfiguration(configuration.name(), false));
	}
	public void clear() { raw = null; configuration = null; diagnostic = null; }

	private static CompoundTag encode(EndpointConfiguration config) {
		var tag = new CompoundTag(); tag.putInt("Version", 1); tag.putString("Name", config.name()); tag.putBoolean("Enabled", config.enabled());
		return tag;
	}
	private static EndpointConfiguration decode(Tag raw) {
		if (!(raw instanceof CompoundTag tag)) throw new IllegalArgumentException("Endpoint data must be a compound");
		if (!tag.getAllKeys().equals(java.util.Set.of("Version", "Name", "Enabled")))
			throw new IllegalArgumentException("Endpoint data requires exact fields");
		require(tag, "Version", Tag.TAG_INT); require(tag, "Name", Tag.TAG_STRING); require(tag, "Enabled", Tag.TAG_BYTE);
		if (tag.getInt("Version") != 1) throw new IllegalArgumentException("Unsupported endpoint version");
		byte enabled = tag.getByte("Enabled");
		if (enabled != 0 && enabled != 1) throw new IllegalArgumentException("Invalid endpoint enabled value");
		return new EndpointConfiguration(tag.getString("Name"), enabled == 1);
	}
	private static void require(CompoundTag tag, String key, int type) {
		if (!tag.contains(key, type)) throw new IllegalArgumentException("Invalid endpoint field: " + key);
	}
}
