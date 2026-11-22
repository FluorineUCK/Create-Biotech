package com.nobodiiiii.createbiotech.content.biocomputing.application.production;

import java.util.*;
import java.util.function.Function;
import javax.annotation.Nullable;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfileCodecs;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.resident.ComputerResident;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;

/** Historical production-host envelope. This is not general RAM or general Cluster membership. */
public final class ProductionComputerArchive<S, B, E, L, F> {
    public static final String ROOT = "ComputerData", RUNTIME = "OrchestrationRuntime";
    private static final int VERSION = 5;
    private static final Set<String> KEYS = Set.of("Version", "ComputerId", "TopologyVersion", "Resident", "Profile",
        "Structure", "Binding", "BindingInvalid", "Epoch", "Faults", "ExternalListeners", RUNTIME);
    private final Codec<S> structures;
    private final Codec<B> bindings;
    private final Codec<E> epochs;
    private final Codec<L> listeners;
    private final Codec<F> faults;
    private final L emptyListeners;
    private final F emptyFaults;
    private Recovery recovery = new Recovery();

    public ProductionComputerArchive(Codec<S> structures, Codec<B> bindings, Codec<E> epochs,
        Codec<L> listeners, L emptyListeners, Codec<F> faults, F emptyFaults) {
        this.structures = Objects.requireNonNull(structures); this.bindings = Objects.requireNonNull(bindings);
        this.epochs = Objects.requireNonNull(epochs); this.listeners = Objects.requireNonNull(listeners);
        this.faults = Objects.requireNonNull(faults); this.emptyListeners = Objects.requireNonNull(emptyListeners);
        this.emptyFaults = Objects.requireNonNull(emptyFaults);
    }

    /** Child codecs return immutable application values; never live hosts or world references. */
    public interface Codec<T> {
        Optional<T> load(@Nullable Tag raw);
        Tag save(T value);
    }
    public static <T> Codec<T> compoundCodec(Function<CompoundTag, Optional<T>> reader,
        Function<T, CompoundTag> writer) {
        return new Codec<>() {
            @Override public Optional<T> load(Tag raw) {
                return raw instanceof CompoundTag tag ? reader.apply(tag.copy()) : Optional.empty();
            }
            @Override public Tag save(T value) { return writer.apply(value); }
        };
    }

    public record Values<S, B, E, L, F>(@Nullable UUID computerId, long topologyVersion,
        @Nullable ProductionResourceProfile profile, L listeners, @Nullable S structure,
        @Nullable B binding, boolean bindingValid, @Nullable E epoch, F faults) {
        public Values { Objects.requireNonNull(listeners); Objects.requireNonNull(faults); }
    }

    /** No live resident, recovery state or application runtime is changed while preparing. */
    public Prepared<S, B, E, L, F> prepare(@Nullable Tag raw, ComputerResident resident,
        HolderLookup.Provider registries) {
        var next = new Recovery();
        var empty = new Values<S, B, E, L, F>(null, 0, null, emptyListeners, null, null, false, null, emptyFaults);
        if (!(raw instanceof CompoundTag input)) {
            next.opaque = raw == null ? null : raw.copy();
            return new Prepared<>(empty, resident.prepareLoad(null, registries), null, next, false);
        }
        var data = input.copy();
        if (!has(data, "Version", Tag.TAG_INT) || data.getInt("Version") != VERSION
            || !KEYS.containsAll(data.getAllKeys()) || !has(data, RUNTIME, Tag.TAG_COMPOUND)) {
            // Preserve the established retirement boundary: superseded Runtime is never re-archived.
            if (!data.contains("Runtime")) next.opaque = data;
            return new Prepared<>(empty, resident.prepareLoad(null, registries), null, next, false);
        }
        UUID id = null;
        if (data.hasUUID("ComputerId")) id = data.getUUID("ComputerId"); else next.retain(data, "ComputerId");
        long topology = 0;
        if (has(data, "TopologyVersion", Tag.TAG_LONG) && data.getLong("TopologyVersion") >= 0)
            topology = data.getLong("TopologyVersion");
        else next.retain(data, "TopologyVersion");
        var loadedResident = resident.prepareLoad(data.get("Resident"), registries);
        var profile = data.get("Profile") instanceof CompoundTag tag
            ? ProductionResourceProfileCodecs.load(tag) : Optional.<ProductionResourceProfile>empty();
        // Invalid optional production profiles are discarded, not converted into physical identities.
        boolean validProfile = !data.contains("Profile") || profile.isPresent();
        var listener = loadChild(data, "ExternalListeners", listeners, true, next).orElse(emptyListeners);
        var structure = loadChild(data, "Structure", structures, false, next).orElse(null);
        var binding = loadChild(data, "Binding", bindings, false, next).orElse(null);
        boolean bindingValid = false;
        if (has(data, "BindingInvalid", Tag.TAG_BYTE)
            && (data.getByte("BindingInvalid") == 0 || data.getByte("BindingInvalid") == 1))
            bindingValid = !data.getBoolean("BindingInvalid");
        else next.retain(data, "BindingInvalid");
        var epoch = loadChild(data, "Epoch", epochs, false, next).orElse(null);
        var loadedFaults = loadChild(data, "Faults", faults, true, next).orElse(emptyFaults);
        var values = new Values<>(id, topology, profile.orElse(null), listener, structure, binding,
            bindingValid, epoch, loadedFaults);
        return new Prepared<>(values, loadedResident, data.get(RUNTIME), next,
            validProfile && !loadedResident.hadCorruption() && !next.hasProblems());
    }

    private static <T> Optional<T> loadChild(CompoundTag data, String key, Codec<T> codec,
        boolean required, Recovery recovery) {
        Tag raw = data.get(key);
        if (raw == null && !required) return Optional.empty();
        var decoded = codec.load(raw == null ? null : raw.copy());
        if (decoded.isEmpty()) recovery.retain(data, key);
        return decoded;
    }

    public void commitRecovery(Prepared<S, B, E, L, F> prepared) { recovery = prepared.recovery.copy(); }
    public Recovery recovery() { return recovery; }
    public void reset() { recovery = new Recovery(); }

    /** Returns a complete replacement for ROOT; callers must not recursively merge it. */
    public Tag save(Values<S, B, E, L, F> values, ComputerResident resident, Tag runtime,
        HolderLookup.Provider registries) {
        if (recovery.opaque != null) return recovery.opaque.copy();
        var data = new CompoundTag();
        data.putInt("Version", VERSION);
        var id = new CompoundTag();
        if (values.computerId() == null) id.putString("ComputerId", "invalid");
        else id.putUUID("ComputerId", values.computerId());
        recovery.put(data, "ComputerId", id.get("ComputerId"));
        recovery.put(data, "TopologyVersion", LongTag.valueOf(values.topologyVersion()));
        resident.write(data, registries);
        if (values.profile() != null) data.put("Profile", ProductionResourceProfileCodecs.save(values.profile()));
        recovery.put(data, "ExternalListeners", listeners.save(values.listeners()));
        recovery.put(data, "Structure", values.structure() == null ? null : structures.save(values.structure()));
        recovery.put(data, "Binding", values.binding() == null ? null : bindings.save(values.binding()));
        recovery.put(data, "BindingInvalid", ByteTag.valueOf(!values.bindingValid()));
        recovery.put(data, "Epoch", values.epoch() == null ? null : epochs.save(values.epoch()));
        recovery.put(data, "Faults", faults.save(values.faults()));
        data.put(RUNTIME, runtime.copy());
        return data;
    }

    public static final class Prepared<S, B, E, L, F> {
        private final Values<S, B, E, L, F> values;
        private final ComputerResident.Loaded resident;
        private final @Nullable Tag runtime;
        private final Recovery recovery;
        private final boolean valid;
        private Prepared(Values<S, B, E, L, F> values, ComputerResident.Loaded resident,
            @Nullable Tag runtime, Recovery recovery, boolean valid) {
            this.values = values; this.resident = resident; this.runtime = runtime == null ? null : runtime.copy();
            this.recovery = recovery; this.valid = valid;
        }
        public Values<S, B, E, L, F> values() { return values; }
        public ComputerResident.Loaded resident() { return resident; }
        public @Nullable Tag runtime() { return runtime == null ? null : runtime.copy(); }
        public boolean valid() { return valid; }
    }

    /** Exact recovery evidence, owned here rather than by the physical BE. All tags are copied. */
    public static final class Recovery {
        private @Nullable Tag opaque;
        private final Map<String, Tag> raw = new LinkedHashMap<>();
        private final Set<String> missing = new HashSet<>();
        public boolean hasOpaque() { return opaque != null; }
        public @Nullable Tag opaqueResident() {
            Tag value = opaque instanceof CompoundTag tag ? tag.get("Resident") : null;
            return value == null ? null : value.copy();
        }
        public boolean hasProblems() { return opaque != null || !raw.isEmpty() || !missing.isEmpty(); }
        public boolean contains(String key) { return raw.containsKey(key) || missing.contains(key); }
        public boolean containsAny(Set<String> keys) { return keys.stream().anyMatch(this::contains); }
        public @Nullable Tag raw(String key) { var value = raw.get(key); return value == null ? null : value.copy(); }
        public void restoreRaw(String key, @Nullable Tag value) {
            if (value == null) raw.remove(key); else raw.put(key, value.copy());
        }
        public void forget(String... keys) { forget(Set.of(keys)); }
        public void forget(Set<String> keys) { raw.keySet().removeAll(keys); missing.removeAll(keys); }
        private void retain(CompoundTag data, String key) {
            var value = data.get(key); if (value == null) missing.add(key); else raw.put(key, value.copy());
        }
        private void put(CompoundTag data, String key, @Nullable Tag normal) {
            if (missing.contains(key)) return;
            var value = raw.getOrDefault(key, normal);
            if (value != null) data.put(key, value.copy());
        }
        private Recovery copy() {
            var result = new Recovery(); result.opaque = opaque == null ? null : opaque.copy();
            raw.forEach((key, value) -> result.raw.put(key, value.copy())); result.missing.addAll(missing);
            return result;
        }
    }
    private static boolean has(CompoundTag data, String key, int type) {
        Tag value = data.get(key); return value != null && value.getId() == type;
    }
}
