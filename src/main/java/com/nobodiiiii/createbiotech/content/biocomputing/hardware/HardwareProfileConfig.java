package com.nobodiiiii.createbiotech.content.biocomputing.hardware;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.ResourceLimits;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Server-owned resident bindings and explicit per-level horizontal hardware profiles. */
public final class HardwareProfileConfig {
	private static final Set<String> BINDING_RESULTS = java.util.stream.Stream.concat(
        ResidentModels.ROWS.stream().map(ResidentModels.Row::profile), java.util.stream.Stream.of("disabled"))
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
    private static List<String> defaults(boolean entity) {
        return ResidentModels.ROWS.stream().filter(r -> r.entity() == entity).map(ResidentModels.Row::binding).toList();
    }

    public final ModConfigSpec.ConfigValue<List<String>> villagerProfiles;
	public final ModConfigSpec.ConfigValue<List<String>> entityProfiles;
	private final Map<String, ProfileValues> profiles;
	private final Map<String, InterfaceCache> interfaces;
	private volatile BindingCache villagerCache = BindingCache.EMPTY;
	private volatile BindingCache entityCache = BindingCache.EMPTY;

	public HardwareProfileConfig(ModConfigSpec.Builder builder) {
		builder.comment("Resident-owned hardware profiles. Every numeric row is indexed explicitly by villager level 1-5.")
			.push("hardware");
		villagerProfiles = builder
			.comment("Exact profession bindings in namespace:profession=profile form; unknown professions require an explicit entry.")
			.define("villagerProfiles", defaults(false), value -> validBindings(value, false));
		entityProfiles = builder
			.comment("Bindings for supported non-villager residents (exact species, no inherited profession).")
			.define("entityProfiles", defaults(true), value -> validBindings(value, true));

		builder.push("profiles");
		var configured = new LinkedHashMap<String, ProfileValues>();
		for (var row : ResidentModels.ROWS) configured.put(row.profile(), profile(builder, row));
        profiles = Map.copyOf(configured);
		var descriptions = new LinkedHashMap<String, InterfaceCache>();
		configured.keySet().forEach(name -> descriptions.put(name, new InterfaceCache()));
		interfaces = Map.copyOf(descriptions);
		builder.pop(2);
	}

	Optional<String> villagerProfile(ResourceLocation profession, boolean loaded) {
		return Optional.ofNullable(bindings(villagerProfiles, loaded, false).get(profession));
	}

	Optional<String> entityProfile(ResourceLocation entityType, boolean loaded) {
		return Optional.ofNullable(bindings(entityProfiles, loaded, true).get(entityType));
	}

	ResourceLimits effective(String profile, int level, boolean loaded, ResourceLimits ceilings, int workMultiplier) {
		ProfileValues values = profiles.get(profile);
		if (values == null || level < 1 || level > 5)
			throw new IllegalArgumentException("Unknown hardware profile or villager level");
		int index = level - 1;
		return new ResourceLimits(
			Math.min(number(value(values.programUnits, loaded), index), ceilings.programUnits()),
			Math.min(number(value(values.memoryUnits, loaded), index), ceilings.memoryUnits()),
			Math.min(BiocomputingConfig.scaleWork(number(value(values.workUnitsPerTick, loaded), index), workMultiplier), ceilings.workPerSlice()), value(values.registerSlots, loaded));
	}

	HardwarePins pins(String profile, boolean loaded) {
		ProfileValues values = profiles.get(profile);
		if (values == null) throw new IllegalArgumentException("Unknown hardware profile");
        var xbus = value(values.xbusPins, loaded); var simpleIo = value(values.simpleIoPins, loaded);
        var cache = interfaces.get(profile); var previous = cache.pins;
        if (previous != null && previous.xbus().equals(xbus) && previous.simpleIo().equals(simpleIo)) return previous;
        var parsed = new HardwarePins(xbus, simpleIo);
        cache.pins = parsed;
        return parsed;
	}

    SerialHardware serial(String profile, int level, boolean loaded) {
        ProfileValues values = profiles.get(profile);
        if (values == null || level < 1 || level > 5) throw new IllegalArgumentException("Unknown serial hardware profile or level");
        var names = value(values.serialPorts, loaded);
        var duplexNames = value(values.serialDuplexPorts, loaded);
        if (!names.containsAll(duplexNames)) throw new IllegalArgumentException("A full-duplex port must be a declared physical serial interface");
        if (names.isEmpty()) return SerialHardware.NONE;
        int index = level - 1;
        long bits = number(value(values.serialBitsPerTick, loaded), index);
        int frame = Math.toIntExact(number(value(values.serialFrameBytes, loaded), index));
        int count = Math.toIntExact(number(value(values.serialReceiveFrames, loaded), index));
        long bytes = number(value(values.serialReceiveBytes, loaded), index);
        int txFrames=value(values.transmitFrames,loaded);
        long configuredBytes=value(values.transmitBytes,loaded);
        long txBytes=configuredBytes==0?frame:configuredBytes;
        var duplex = duplexNames.isEmpty() ? Optional.<SerialHardware.IndependentDuplex>empty() : Optional.of(
            new SerialHardware.IndependentDuplex(number(value(values.serialDuplexTransmitBitsPerTick, loaded), index),
                number(value(values.serialDuplexReceiveBitsPerTick, loaded), index)));
        var cache = interfaces.get(profile); var previous = cache.serial.get(index);
        if (previous != null && previous.matches(names, duplexNames, bits, frame, count, bytes, duplex,txFrames,txBytes)) return previous.hardware();
        var sharedPort = new SerialHardware.Port(bits, frame, count, bytes, Optional.empty(),txFrames,txBytes);
        var duplexPort = duplex.isEmpty() ? sharedPort : new SerialHardware.Port(bits, frame, count, bytes, duplex,txFrames,txBytes);
        var ports = new LinkedHashMap<String, SerialHardware.Port>();
        for (String name : names) ports.put(name, duplexNames.contains(name) ? duplexPort : sharedPort);
        var parsed = new SerialHardware(ports);
        cache.serial.set(index, new SerialDescription(List.copyOf(names), List.copyOf(duplexNames), duplexPort, parsed));
        return parsed;
    }
    int taskSlots(String profile, int level, boolean loaded, int ceiling) {
        ProfileValues values = profiles.get(profile);
        if (values == null || level < 1 || level > 5) throw new IllegalArgumentException("Unknown task hardware profile or level");
        return Math.min(Math.toIntExact(number(value(values.taskSlots, loaded), level - 1)), ceiling);
    }

	private Map<ResourceLocation, String> bindings(ModConfigSpec.ConfigValue<List<String>> configured,
		boolean loaded, boolean entities) {
		List<String> current = value(configured, loaded);
		BindingCache cached = entities ? entityCache : villagerCache;
		if (cached.source.equals(current))
			return cached.bindings;
		synchronized (this) {
			cached = entities ? entityCache : villagerCache;
			if (!cached.source.equals(current)) {
				var parsed = new LinkedHashMap<ResourceLocation, String>();
				for (String assignment : current) {
					int separator = assignment.indexOf('=');
					parsed.put(ResourceLocation.parse(assignment.substring(0, separator)), assignment.substring(separator + 1));
				}
				cached = new BindingCache(List.copyOf(current), Map.copyOf(parsed));
				if (entities)
					entityCache = cached;
				else
					villagerCache = cached;
			}
			return cached.bindings;
		}
	}

	private static ProfileValues profile(ModConfigSpec.Builder builder, ResidentModels.Row row) {
        var program = row.levels(row.code()); var memory = row.levels(row.ramKi()*1024L);
        var work = row.levels(row.cpu()/8); var tasks = row.taskLevels();
        builder.push(row.profile());
		var result = new ProfileValues(
			builder.comment("Program-unit budgets for levels 1-5.")
				.define("programUnits", program, value -> validBudgetRow(value, false)),
			builder.comment("Memory-unit budgets for levels 1-5.")
				.define("memoryUnits", memory, value -> validBudgetRow(value, false)),
			builder.comment("Work units per server tick for levels 1-5.")
				.define("workUnitsPerTick", work, value -> validBudgetRow(value, true)),
			builder.comment("Fixed XBus pin names supplied by this hardware profile at every level. Programs cannot add pins. Removing an in-use pin suspends the saved flow without rewriting it.")
				.define("xbusPins", ResidentModels.pins("x",row.x()), HardwareProfileConfig::validPins),
			builder.comment("Independent Simple I/O pin names at every level. Names must not overlap xbusPins; overlap disables this profile until corrected. Provisional defaults, not fixed balancing.")
				.define("simpleIoPins", ResidentModels.pins("p",row.p()), HardwareProfileConfig::validPins),
            builder.comment("Physical serial interface names, independent of public aliases and XBus/Simple I/O pins. Empty removes serial interfaces only. Does not open or connect any port. Fresh-world defaults depend on the resident model.")
                .define("serialPorts", ResidentModels.pins("s",row.serial()), HardwareProfileConfig::validPins),
            builder.comment("Serial hardware bits per medium tick for levels 1-5, independent of CPU work budget. Cable speed may constrain transmission further.")
                .define("serialBitsPerTick", List.of(128L, 128L, 128L, 128L, 128L), v -> validBudgetRow(v, true)),
            builder.comment("Subset of serialPorts with independent transmit and receive circuits. Empty leaves existing ports shared-only. Does not open ports or make a shared cable full duplex.")
                .define("serialDuplexPorts", row.duplex() ? List.of("s0") : List.of(), HardwareProfileConfig::validPins),
            builder.comment("Independent transmit bits per tick on explicitly duplex-capable ports, for levels 1-5.")
                .define("serialDuplexTransmitBitsPerTick", List.of(512L, 512L, 512L, 512L, 512L), v -> validBudgetRow(v, true)),
            builder.comment("Independent receive bits per tick on explicitly duplex-capable ports, for levels 1-5.")
                .define("serialDuplexReceiveBitsPerTick", List.of(512L, 512L, 512L, 512L, 512L), v -> validBudgetRow(v, true)),
            builder.comment("Maximum encoded frame bytes per physical serial port for levels 1-5. No hidden fragmentation. Provisional baseline.")
                .define("serialFrameBytes", List.of(4096L, 4096L, 4096L, 4096L, 4096L), HardwareProfileConfig::validIntRow),
            builder.comment("Maximum received frames per physical serial port for levels 1-5; independent of RAM and legacy inbox capacity.")
                .define("serialReceiveFrames", List.of(2L, 2L, 2L, 2L, 2L), HardwareProfileConfig::validIntRow),
            builder.comment("Maximum total encoded receive bytes per physical serial port for levels 1-5. Each row must fit serialFrameBytes; incompatible rows disable this profile without truncating saved data.")
                .define("serialReceiveBytes", List.of(8192L, 8192L, 8192L, 8192L, 8192L), v -> validBudgetRow(v, true)),
            builder.comment("Retained task contexts for levels 1-5, including main and unclaimed results. Tasks share CPU and RAM; these are not extra cores. Initial balance defaults.")
                .define("taskSlots", tasks, HardwareProfileConfig::validIntRow),
            builder.comment("Maximum simultaneously occupied ISA registers in each active frame, not a limit on register IDs.")
                .defineInRange("registerSlots",row.registers(),1,Integer.MAX_VALUE),
            builder.comment("Maximum preloaded TX frames per port, including the frame on the wire. No retries.")
                .defineInRange("serialTransmitFrames",row.profile().equals("pillager")?4:1,1,65536),
            builder.comment("Total encoded TX bytes, including the frame on the wire. Zero follows the single-frame limit.")
                .defineInRange("serialTransmitBytes",row.profile().equals("pillager")?4096L:0L,0L,Integer.MAX_VALUE));
		builder.pop();
		return result;
	}

	private static boolean validBudgetRow(Object candidate, boolean positive) {
		if (!(candidate instanceof List<?> values) || values.size() != 5)
			return false;
		for (Object value : values) {
			if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long))
				return false;
			long number = ((Number) value).longValue();
			if (positive ? number <= 0 : number < 0)
				return false;
		}
		return true;
	}

    private static boolean validIntRow(Object candidate) {
        return validBudgetRow(candidate, true) && ((List<?>)candidate).stream()
            .allMatch(value -> ((Number)value).longValue() <= Integer.MAX_VALUE);
    }

	private static boolean validPins(Object candidate) {
		if (!(candidate instanceof List<?> names) || names.stream().anyMatch(name -> !(name instanceof String))) return false;
		try { new HardwarePins(names.stream().map(String.class::cast).toList()); return true; }
		catch (IllegalArgumentException invalid) { return false; }
	}

	private static boolean validBindings(Object candidate, boolean entities) {
		if (!(candidate instanceof List<?> assignments))
			return false;
		var keys = new HashSet<ResourceLocation>();
		for (Object value : assignments) {
			if (!(value instanceof String assignment))
				return false;
			int separator = assignment.indexOf('=');
			if (separator <= 0 || separator != assignment.lastIndexOf('=') || separator == assignment.length() - 1)
				return false;
			String key = assignment.substring(0, separator);
			int namespaceSeparator = key.indexOf(':');
			if (namespaceSeparator <= 0 || namespaceSeparator == key.length() - 1
				|| !BINDING_RESULTS.contains(assignment.substring(separator + 1)))
				return false;
			ResourceLocation resource = ResourceLocation.tryParse(key);
			if (assignment.substring(separator + 1).equals("parser") && (entities || !key.equals("minecraft:cleric")))
				return false;
			if (resource == null || !keys.add(resource))
				return false;
			if (entities && !ResidentModels.ENTITY_IDS.contains(resource))
				return false;
		}
		return true;
	}

	private static long number(List<?> values, int index) {
		return ((Number) values.get(index)).longValue();
	}

	private static <T> T value(ModConfigSpec.ConfigValue<T> configured, boolean loaded) {
		return loaded ? configured.get() : configured.getDefault();
	}

	private record ProfileValues(ModConfigSpec.ConfigValue<List<Long>> programUnits,
		ModConfigSpec.ConfigValue<List<Long>> memoryUnits,
		ModConfigSpec.ConfigValue<List<Long>> workUnitsPerTick,
		ModConfigSpec.ConfigValue<List<String>> xbusPins,
		ModConfigSpec.ConfigValue<List<String>> simpleIoPins,
        ModConfigSpec.ConfigValue<List<String>> serialPorts,
        ModConfigSpec.ConfigValue<List<Long>> serialBitsPerTick,
        ModConfigSpec.ConfigValue<List<String>> serialDuplexPorts,
        ModConfigSpec.ConfigValue<List<Long>> serialDuplexTransmitBitsPerTick,
        ModConfigSpec.ConfigValue<List<Long>> serialDuplexReceiveBitsPerTick,
        ModConfigSpec.ConfigValue<List<Long>> serialFrameBytes,
        ModConfigSpec.ConfigValue<List<Long>> serialReceiveFrames,
        ModConfigSpec.ConfigValue<List<Long>> serialReceiveBytes,
        ModConfigSpec.ConfigValue<List<Long>> taskSlots, ModConfigSpec.IntValue registerSlots, ModConfigSpec.IntValue transmitFrames, ModConfigSpec.LongValue transmitBytes) {}

	private record BindingCache(List<String> source, Map<ResourceLocation, String> bindings) {
		private static final BindingCache EMPTY = new BindingCache(List.of(), Map.of());
	}

    /** Bounded by registered profiles and the five resident levels, not by requests or config history.
     * Only immutable descriptions are reused. Every lookup reads and compares current config values;
     * resident eligibility, CPU/RAM budgets and cross-interface overlap are still checked by select. */
    private static final class InterfaceCache {
        private volatile HardwarePins pins;
        private final java.util.concurrent.atomic.AtomicReferenceArray<SerialDescription> serial =
            new java.util.concurrent.atomic.AtomicReferenceArray<>(5);
    }
    private record SerialDescription(List<String> names, List<String> duplexNames, SerialHardware.Port port, SerialHardware hardware) {
        private boolean matches(List<String> current, List<String> duplexPorts, long bits, int frame, int count, long bytes,
                                Optional<SerialHardware.IndependentDuplex> duplex,int txFrames,long txBytes) {
            return port.transmitFrames()==txFrames && port.transmitBytes()==txBytes && port.bitsPerTick() == bits && port.maxFrameBytes() == frame && port.receiveFrames() == count
                && port.receiveBytes() == bytes && port.duplex().equals(duplex) && names.equals(current) && duplexNames.equals(duplexPorts);
        }
    }
}
