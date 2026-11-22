package com.nobodiiiii.createbiotech.content.biocomputing.hardware;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.ResourceLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialWireMedium;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SharedSerialChannel;
import com.nobodiiiii.createbiotech.content.biocomputing.device.particle.ParticleOutputBurst;
import com.nobodiiiii.createbiotech.content.biocomputing.device.narrator.NarratorOutputText;
import com.nobodiiiii.createbiotech.content.biocomputing.device.localtext.SpeakerMediaLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.EnclosureLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MailboxLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.device.terminal.TerminalUiState;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialRecordCodec;
import java.util.Optional;
import com.nobodiiiii.createbiotech.content.biocomputing.network.PeripheralNetworkLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationNetworkLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.peripheral.ReadLimits;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Server-owned ceilings, archive limits and resident-selected hardware profiles. */
public final class BiocomputingConfig {
	private static final ResourceLocation VILLAGER = ResourceLocation.parse("minecraft:villager");
	private static final ResourceLocation WITCH = ResourceLocation.withDefaultNamespace("witch");
	private static final ResourceLocation WANDERING_TRADER = ResourceLocation.parse("minecraft:wandering_trader");


	public final ModConfigSpec.LongValue programUnits;
	public final ModConfigSpec.LongValue memoryUnits;
	public final ModConfigSpec.LongValue workUnitsPerTick;
    public final ModConfigSpec.IntValue executionWorkMultiplier;
    public final ModConfigSpec.IntValue taskSlots;
	public final ModConfigSpec.IntValue archiveNodes;
	public final ModConfigSpec.LongValue archiveStringChars;
	public final ModConfigSpec.LongValue archiveValueUnits;
    public final ModConfigSpec.IntValue productionNativeRequestEntries;
    public final ModConfigSpec.IntValue productionJournalDevices, productionDeliveryFragments;
    public final ModConfigSpec.IntValue productionOrderCount, productionOrderSteps, productionArchiveBytes, productionActivityTicks;
    public final ModConfigSpec.LongValue productionOrderUnits, productionArchiveHeap;
    public final ModConfigSpec.LongValue productionSerialBitsPerTick, productionSerialReceiveBytes;
    public final ModConfigSpec.IntValue productionSerialFrameBytes, productionSerialReceiveFrames;
	public final ModConfigSpec.IntValue libraryBookBytes;
	public final ModConfigSpec.LongValue libraryServiceUnits;
	public final ModConfigSpec.IntValue libraryServiceGrants;
    public final ModConfigSpec.IntValue libraryControlCableBlocks;
    public final ModConfigSpec.LongValue librarySeekBaseTicks, librarySeekTicksPerBlock, librarySelectTicks;
    public final ModConfigSpec.LongValue libraryReadBaseTicks, libraryReadBytesPerTick, libraryBufferBytes, libraryCodecWorkPerTick;
    public final ModConfigSpec.LongValue librarySerialBitsPerTick, librarySerialReceiveBytes;
    public final ModConfigSpec.IntValue librarySerialFrameBytes, librarySerialReceiveFrames;
    public final ModConfigSpec.LongValue redstoneSerialBitsPerTick, redstoneSerialReceiveBytes;
    public final ModConfigSpec.IntValue redstoneSerialFrameBytes, redstoneSerialReceiveFrames;
    public final ModConfigSpec.LongValue stockSerialBitsPerTick, stockSerialReceiveBytes, stockRetainedUnits;
    public final ModConfigSpec.IntValue stockSerialFrameBytes, stockSerialReceiveFrames, stockEntries, stockInputs, stockTextChars, stockAddressChars;
    public final ModConfigSpec.LongValue textOutputSerialBitsPerTick, textOutputSerialReceiveBytes;
    public final ModConfigSpec.LongValue musicOutputSerialBitsPerTick, musicOutputSerialReceiveBytes;
    public final ModConfigSpec.IntValue musicOutputSerialFrameBytes, musicOutputSerialReceiveFrames;
    public final ModConfigSpec.LongValue labelOutputSerialBitsPerTick, labelOutputSerialReceiveBytes;
    public final ModConfigSpec.IntValue labelOutputSerialFrameBytes, labelOutputSerialReceiveFrames, labelOutputBytes, labelOutputViewDistance;
    public final ModConfigSpec.LongValue particleOutputSerialBitsPerTick, particleOutputSerialReceiveBytes;
    public final ModConfigSpec.IntValue particleOutputSerialFrameBytes, particleOutputSerialReceiveFrames, particleOutputCount, particleOutputPerDimensionTick;
    public final ModConfigSpec.DoubleValue particleOutputRadius, particleOutputSpeed;
    public final ModConfigSpec.LongValue narratorOutputSerialBitsPerTick, narratorOutputSerialReceiveBytes;
    public final ModConfigSpec.IntValue narratorOutputSerialFrameBytes, narratorOutputSerialReceiveFrames,
        narratorOutputBytes, narratorOutputRange, narratorOutputCooldown, narratorOutputPerDimensionTick;
    public final ModConfigSpec.IntValue speakerMediaTrackBytes, speakerMediaPlayerBytes, speakerMediaWorldBytes, speakerMediaTracks;
    public final ModConfigSpec.IntValue speakerPlaybackRange, speakerPlaybackListeners;
    private final ModConfigSpec.LongValue chatOutputSerialBitsPerTick, chatOutputSerialReceiveBytes;
    private final ModConfigSpec.LongValue terminalUiSerialBitsPerTick, terminalUiSerialReceiveBytes;
    private final ModConfigSpec.LongValue virtualFilterBitsPerTick, virtualFilterReceiveBytes;
    private final ModConfigSpec.IntValue virtualFilterFrameBytes, virtualFilterReceiveFrames, virtualFilterMatchers;
    private final ModConfigSpec.LongValue componentSettingsBitsPerTick, componentSettingsReceiveBytes;
    private final ModConfigSpec.IntValue componentSettingsFrameBytes, componentSettingsReceiveFrames;
    private final ModConfigSpec.IntValue terminalUiSerialFrameBytes, terminalUiSerialReceiveFrames,
        terminalUiWidgets, terminalUiEvents, terminalUiInputChars, terminalUiPageBytes, terminalUiEventBytes;
    private final ModConfigSpec.IntValue chatOutputSerialFrameBytes, chatOutputSerialReceiveFrames,
        chatOutputBytes, chatOutputRange, chatOutputCooldown, chatOutputPerDimensionSecond;
    public final ModConfigSpec.ConfigValue<java.util.List<String>> particleOutputSimpleTypes;
    public final ModConfigSpec.IntValue textOutputSerialFrameBytes, textOutputSerialReceiveFrames, textOutputLines, textOutputBytes;
    public final ModConfigSpec.LongValue readerSerialBitsPerTick, readerSerialReceiveBytes;
    public final ModConfigSpec.IntValue readerSerialFrameBytes, readerSerialReceiveFrames;
	public final ModConfigSpec.IntValue peripheralVisited;
	public final ModConfigSpec.IntValue communicationVisited, communicationEndpoints;
    public final ModConfigSpec.LongValue serialMediumBitsPerTick, serialFramingBits, serialDecodeBytes;
    public final ModConfigSpec.LongValue fastSerialMediumBitsPerTick, duplexSerialMediumBitsPerTick;
    public final ModConfigSpec.IntValue serialFrameBytes, serialIndicatorIntervalTicks;
	public final ModConfigSpec.IntValue inboxMessages, trackedSources;
	public final ModConfigSpec.IntValue xbusHistoryComputers, xbusHistoryClaims;
	public final ModConfigSpec.IntValue enclosureMinSize, enclosureMaxSize, enclosureMaxComputers;
	public final ModConfigSpec.IntValue clusterConfigurationEntries;
	public final ModConfigSpec.IntValue xbusIndicatorIntervalTicks;
	public final ModConfigSpec.IntValue recentEvents;
	public final ModConfigSpec.IntValue peripheralReaders;
	public final ModConfigSpec.IntValue peripheralEntries;
	public final ModConfigSpec.LongValue peripheralStringChars;
	public final ModConfigSpec.LongValue peripheralValueUnits;
	public final ModConfigSpec.BooleanValue allowSerializedBlockData;
	public final HardwareProfileConfig hardware;
    private final CarrierHardwareConfig carrierHardware;
    private final ResidentRadioConfig residentRadio;

	public final ModConfigSpec.IntValue analyzerDepth;
	public BiocomputingConfig(ModConfigSpec.Builder builder) {
		builder.comment("Independent biocomputing resources; unrelated to factory production process slots.").push("biocomputing");
        analyzerDepth = builder.comment("Shared SIO edge records per illusioner. Full capture freezes on the next edge.").defineInRange("analyzerDepth", EdgeRecorder.DEPTH, 1, EdgeRecorder.DEPTH);
        builder.comment("Checked native production effects. These bounds do not add Computer execution slots.").push("production");
        productionNativeRequestEntries = builder.comment("Maximum native packaging entries in one checked dispatch; rejects before material effects rather than silently splitting an oversized operation.")
            .defineInRange("nativeRequestEntries", 1024, 1, 16384);
        productionOrderCount = builder.comment("Retained orders per physical production port; history is not silently evicted.").defineInRange("orders", 128, 1, 65536);
        productionJournalDevices = builder.comment("Server-owned production journals, including unloaded ports. A lower bound never silently drops retained history.").defineInRange("journalDevices", 256, 1, 65536);
        productionDeliveryFragments = builder.comment("Retained native delivery fragments per order; overflow requires review, not guessed completion.").defineInRange("deliveryFragments", 1024, 1, 65536);
        productionOrderSteps = builder.defineInRange("steps", 64, 1, 4096);
        productionOrderUnits = builder.defineInRange("valueUnits", 1048576L, 1L, Long.MAX_VALUE);
        productionArchiveBytes = builder.defineInRange("archiveBytes", 8388608, 1, Integer.MAX_VALUE);
        productionArchiveHeap = builder.defineInRange("archiveDecodeBytes", 67108864L, 1L, Long.MAX_VALUE);
        productionActivityTicks = builder.comment("Active device ticks per one dispatch or receipt observation; independent of Computer execution.").defineInRange("activityTicks", 5, 1, 1200);
        builder.push("serial");
        productionSerialBitsPerTick = builder.defineInRange("bitsPerTick", 128L, 1L, Long.MAX_VALUE);
        productionSerialFrameBytes = builder.defineInRange("frameBytes", 4096, 1, Integer.MAX_VALUE);
        productionSerialReceiveFrames = builder.defineInRange("receiveFrames", 2, 1, 1024);
        productionSerialReceiveBytes = builder.defineInRange("receiveBytes", 8192L, 1L, Long.MAX_VALUE);
        builder.pop();
        builder.pop();
		programUnits = builder.comment("Server ceiling for executable nodes and memory-write bindings per installed computer.")
			.defineInRange("programUnits", 320L, 0L, Long.MAX_VALUE);
		memoryUnits = builder.comment("Server ceiling for logical data units per computer, including reset image and working memory.")
			.defineInRange("memoryUnits", 262144L, 0L, Long.MAX_VALUE);
		workUnitsPerTick = builder.comment("Base server ceiling for execution work per tick, before executionWorkMultiplier; not a wall-clock guarantee.")
			.defineInRange("workUnitsPerTick", 32L, 1L, Long.MAX_VALUE);
        executionWorkMultiplier = builder.comment("CPU work multiplier for all resident profiles and the server ceiling, including existing configured rows. Does not accelerate device clocks, Sleep or single-step. 1 restores the original rate.")
            .defineInRange("executionWorkMultiplier", 8, 1, Integer.MAX_VALUE);
        taskSlots = builder.comment("Server ceiling for retained Flow task contexts per Computer, including main and unclaimed results. Does not multiply work/RAM or grant a Java thread.")
            .defineInRange("taskSlots", 16, 1, Integer.MAX_VALUE);
		archiveNodes = builder.comment("Maximum entries in a computer's durable value table.")
			.defineInRange("archiveNodes", 262144, 1, Integer.MAX_VALUE);
		archiveStringChars = builder.comment("Maximum aggregate UTF-16 code units in the durable value table.")
			.defineInRange("archiveStringChars", 2097152L, 0L, Long.MAX_VALUE);
		archiveValueUnits = builder.comment("Maximum logical size of the full archive, including program and checkpoint metadata.")
			.defineInRange("archiveValueUnits", 16777216L, 1L, Long.MAX_VALUE);
		builder.comment("Physical Library book components; unrelated to Computer RAM and protected recipe pages.").push("library");
		libraryBookBytes = builder.comment("Maximum uncompressed NBT bytes in the dedicated entries component of one book. Lowering this permits reads but rejects oversized replacements.")
			.defineInRange("bookBytes", 65536, 1024, 262144);
		libraryServiceUnits = builder.comment("Maximum logical units for Library service grants, request receipts, queue and one retained reply; independent of book capacity and Computer RAM.")
			.defineInRange("serviceUnits", 262144L, 1L, Long.MAX_VALUE);
		libraryServiceGrants = builder.comment("Maximum explicitly authorized volume names per Library endpoint.")
			.defineInRange("serviceGrants", 256, 1, 4096);
        builder.comment("One slow physical read mechanism. Separate from Computer RAM, CPU and serial bitrate; values are initial balance defaults.").push("drive");
        libraryControlCableBlocks = builder.comment("Maximum cable blocks on the explicitly selected local librarian-control path. Does not grant transport or bridge a serial segment.")
            .defineInRange("controlCableBlocks", 16, 0, 1024);
        librarySeekBaseTicks = builder.comment("Base device activity ticks per explicit seek, including seeking the current track.")
            .defineInRange("seekBaseTicks", 20L, 1L, Long.MAX_VALUE);
        librarySeekTicksPerBlock = builder.comment("Additional seek ticks per Manhattan block of local track displacement.")
            .defineInRange("seekTicksPerBlock", 10L, 0L, Long.MAX_VALUE);
        librarySelectTicks = builder.comment("Device activity ticks per explicit physical book-slot selection.")
            .defineInRange("selectTicks", 4L, 1L, Long.MAX_VALUE);
        libraryReadBaseTicks = builder.comment("Base ticks for a read, plus encoded value bytes divided by readBytesPerTick. Active operations retain their original duration.")
            .defineInRange("readBaseTicks", 20L, 1L, Long.MAX_VALUE);
        libraryReadBytesPerTick = builder.comment("Encoded typed-value NBT bytes read per device activity tick; rounded up, without hidden chunking.")
            .defineInRange("readBytesPerTick", 32L, 1L, Long.MAX_VALUE);
        libraryBufferBytes = builder.comment("Maximum encoded bytes for the single in-flight sample including its medium fence. Lowering never discards saved work.")
            .defineInRange("bufferBytes", 131072L, 1L, Long.MAX_VALUE);
        libraryCodecWorkPerTick = builder.comment("Incremental typed-value codec work units per Library activity tick, including bounded checkpoint reconstruction; independent of cable bitrate and Computer CPU.")
            .defineInRange("codecWorkPerTick", 32L, 1L, Long.MAX_VALUE);
        builder.comment("Library's own finite rear serial interface; independent of the delegate's resident hardware.").push("serial");
        librarySerialBitsPerTick = builder.defineInRange("bitsPerTick", 128L, 1L, Long.MAX_VALUE);
        librarySerialFrameBytes = builder.defineInRange("frameBytes", 4096, 1, Integer.MAX_VALUE);
        librarySerialReceiveFrames = builder.defineInRange("receiveFrames", 2, 1, 1024);
        librarySerialReceiveBytes = builder.defineInRange("receiveBytes", 8192L, 1L, Long.MAX_VALUE);
        builder.pop();
        builder.pop();
		builder.pop();
        builder.comment("Base redstone output hardware; separate from Computer RAM and Library mechanics.").push("redstoneOutput").push("serial");
        redstoneSerialBitsPerTick = builder.defineInRange("bitsPerTick", 128L, 1L, Long.MAX_VALUE);
        redstoneSerialFrameBytes = builder.defineInRange("frameBytes", 4096, 1, Integer.MAX_VALUE);
        redstoneSerialReceiveFrames = builder.defineInRange("receiveFrames", 2, 1, 1024);
        redstoneSerialReceiveBytes = builder.defineInRange("receiveBytes", 8192L, 1L, Long.MAX_VALUE);
        builder.pop(); builder.pop();
        builder.comment("Persistent text output page, separate from Computer RAM and finite serial buffers.").push("textOutput");
        textOutputLines = builder.defineInRange("lines", 16, 1, 256);
        textOutputBytes = builder.comment("UTF-8 bytes in the accepted page. Lowering does not truncate existing pages.").defineInRange("bytes", 2048, 1, 65536);
        builder.push("serial");
        textOutputSerialBitsPerTick = builder.defineInRange("bitsPerTick", 128L, 1L, Long.MAX_VALUE);
        textOutputSerialFrameBytes = builder.defineInRange("frameBytes", 4096, 1, Integer.MAX_VALUE);
        textOutputSerialReceiveFrames = builder.defineInRange("receiveFrames", 2, 1, 1024);
        textOutputSerialReceiveBytes = builder.defineInRange("receiveBytes", 8192L, 1L, Long.MAX_VALUE);
        builder.pop(); builder.pop();
        builder.comment("One-shot note block output hardware, separate from Computer RAM.").push("musicOutput").push("serial");
        musicOutputSerialBitsPerTick = builder.defineInRange("bitsPerTick", 128L, 1L, Long.MAX_VALUE);
        musicOutputSerialFrameBytes = builder.defineInRange("frameBytes", 4096, 1, Integer.MAX_VALUE);
        musicOutputSerialReceiveFrames = builder.defineInRange("receiveFrames", 2, 1, 1024);
        musicOutputSerialReceiveBytes = builder.defineInRange("receiveBytes", 8192L, 1L, Long.MAX_VALUE);
        builder.pop(); builder.pop();
        builder.comment("Single literal world label, stored on the output adapter.").push("labelOutput");
        labelOutputBytes = builder.comment("Maximum UTF-8 bytes per replacement; lowering it does not truncate saved labels.")
            .defineInRange("bytes", 96, 1, com.nobodiiiii.createbiotech.content.biocomputing.device.label.LabelOutputState.FORMAT_MAX_BYTES);
        labelOutputViewDistance = builder.comment("Client label visibility in blocks. Never drawn through opaque geometry.").defineInRange("viewDistance", 16, 1, 32);
        builder.push("serial");
        labelOutputSerialBitsPerTick = builder.defineInRange("bitsPerTick", 128L, 1L, Long.MAX_VALUE);
        labelOutputSerialFrameBytes = builder.defineInRange("frameBytes", 4096, 1, Integer.MAX_VALUE);
        labelOutputSerialReceiveFrames = builder.defineInRange("receiveFrames", 2, 1, 1024);
        labelOutputSerialReceiveBytes = builder.defineInRange("receiveBytes", 8192L, 1L, Long.MAX_VALUE);
        builder.pop(); builder.pop();
        builder.comment("Authorized one-shot particle emitters. Limits bound initial spawn and server dispatch, not client particle lifetimes.")
            .push("particleOutput");
        particleOutputCount = builder.defineInRange("count", 24, 1, ParticleOutputBurst.MAX_COUNT);
        particleOutputPerDimensionTick = builder.defineInRange("perDimensionTick", 128, 1, 1024);
        particleOutputRadius = builder.comment("Maximum initial offset, including spread, in blocks from the emitter nozzle.")
            .defineInRange("radius", 3d, 0.25d, ParticleOutputBurst.MAX_RADIUS);
        particleOutputSpeed = builder.comment("Maximum initial velocity in blocks per client tick.")
            .defineInRange("speed", 0.5d, 0d, ParticleOutputBurst.MAX_SPEED);
        particleOutputSimpleTypes = builder.comment("Exact IDs of registered no-argument particles. Complex kinds still need explicit adapters.")
            .define("simpleParticles", java.util.List.of("minecraft:smoke", "minecraft:electric_spark"),
                value -> value instanceof java.util.List<?> list && list.size() <= ParticleOutputBurst.MAX_SIMPLE_TYPES
                    && list.stream().allMatch(ParticleOutputBurst::validParticleId));
        builder.push("serial");
        particleOutputSerialBitsPerTick = builder.defineInRange("bitsPerTick", 128L, 1L, Long.MAX_VALUE);
        particleOutputSerialFrameBytes = builder.defineInRange("frameBytes", 4096, 1, Integer.MAX_VALUE);
        particleOutputSerialReceiveFrames = builder.defineInRange("receiveFrames", 2, 1, 1024);
        particleOutputSerialReceiveBytes = builder.defineInRange("receiveBytes", 8192L, 1L, Long.MAX_VALUE);
        builder.pop(); builder.pop();
        builder.comment("Local speech delivery; clients remain muted unless they opt in.").push("narratorOutput");
        narratorOutputBytes = builder.defineInRange("bytes", 192, 1, NarratorOutputText.FORMAT_MAX_BYTES);
        narratorOutputRange = builder.comment("Maximum listener distance in blocks.").defineInRange("range", 16, 1, 64);
        narratorOutputCooldown = builder.comment("Minimum world ticks between admitted utterances on one device.").defineInRange("cooldown", 60, 1, 1200);
        narratorOutputPerDimensionTick = builder.defineInRange("perDimensionTick", 4, 1, 32);
        builder.push("serial");
        narratorOutputSerialBitsPerTick = builder.defineInRange("bitsPerTick", 128L, 1L, Long.MAX_VALUE);
        narratorOutputSerialFrameBytes = builder.defineInRange("frameBytes", 4096, 1, Integer.MAX_VALUE);
        narratorOutputSerialReceiveFrames = builder.defineInRange("receiveFrames", 2, 1, 1024);
        narratorOutputSerialReceiveBytes = builder.defineInRange("receiveBytes", 8192L, 1L, Long.MAX_VALUE);
        builder.pop(); builder.pop();
        builder.comment("World-owned tracks imported by players from a speaker menu. Media does not consume in-world serial bandwidth.")
            .push("speakerMedia");
        speakerMediaTrackBytes = builder.defineInRange("trackBytes", 6 * 1024 * 1024, 1, SpeakerMediaLimits.HARD_TRACK_BYTES);
        speakerMediaPlayerBytes = builder.defineInRange("playerBytes", 16 * 1024 * 1024, 1, SpeakerMediaLimits.HARD_WORLD_BYTES);
        speakerMediaWorldBytes = builder.defineInRange("worldBytes", 32 * 1024 * 1024, 1, SpeakerMediaLimits.HARD_WORLD_BYTES);
        speakerMediaTracks = builder.defineInRange("tracks", 96, 1, SpeakerMediaLimits.HARD_TRACKS);
        speakerPlaybackRange = builder.comment("Maximum distance to hear MIDI or other imported audio, in blocks.")
            .defineInRange("playbackRange", 16, 1, 64);
        speakerPlaybackListeners = builder.comment("Maximum number of nearby opted-in clients per playback.")
            .defineInRange("playbackListeners", 8, 1, 16);
        builder.pop();
        builder.comment("Authorized nearby system-chat notices, independent of narration.").push("chatOutput");
        chatOutputBytes = builder.defineInRange("bytes", 192, 1, NarratorOutputText.FORMAT_MAX_BYTES);
        chatOutputRange = builder.comment("Maximum chat recipient distance in blocks.").defineInRange("range", 16, 1, 64);
        chatOutputCooldown = builder.comment("Minimum world ticks between admitted chat messages on one device.").defineInRange("cooldown", 60, 1, 1200);
        chatOutputPerDimensionSecond = builder.comment("Shared admissions per dimension in each 20-world-tick window.")
            .defineInRange("perDimensionSecond", 4, 1, 32);
        builder.push("serial");
        chatOutputSerialBitsPerTick = builder.defineInRange("bitsPerTick", 128L, 1L, Long.MAX_VALUE);
        chatOutputSerialFrameBytes = builder.defineInRange("frameBytes", 4096, 1, Integer.MAX_VALUE);
        chatOutputSerialReceiveFrames = builder.defineInRange("receiveFrames", 2, 1, 1024);
        chatOutputSerialReceiveBytes = builder.defineInRange("receiveBytes", 8192L, 1L, Long.MAX_VALUE);
        builder.pop(); builder.pop();
        builder.comment("Programmable terminal layout, interaction queue and serial limits.").push("terminalUi");
        terminalUiWidgets = builder.defineInRange("widgets", 64, 1, TerminalUiState.MAX_WIDGETS);
        terminalUiEvents = builder.defineInRange("events", 8, 1, TerminalUiState.MAX_EVENTS);
        terminalUiInputChars = builder.defineInRange("inputChars", 128, 1, TerminalUiState.MAX_INPUT_CHARS);
        terminalUiPageBytes = builder.defineInRange("pageBytes", 8192, 1, TerminalUiState.PAGE_FORMAT.maxEncodedBytes());
        terminalUiEventBytes = builder.defineInRange("eventBytes", 1536, 1, TerminalUiState.PAGE_FORMAT.maxEncodedBytes());
        builder.push("serial");
        terminalUiSerialBitsPerTick = builder.defineInRange("bitsPerTick", 128L, 1L, Long.MAX_VALUE);
        terminalUiSerialFrameBytes = builder.defineInRange("frameBytes", 4096, 1, Integer.MAX_VALUE);
        terminalUiSerialReceiveFrames = builder.defineInRange("receiveFrames", 2, 1, 1024);
        terminalUiSerialReceiveBytes = builder.defineInRange("receiveBytes", 8192L, 1L, Long.MAX_VALUE);
        builder.pop(); builder.pop();
        builder.comment("Virtual filtering snapshot and serial control interface.").push("virtualFilter");
        virtualFilterMatchers = builder.defineInRange("matchers", 16, 1, com.nobodiiiii.createbiotech.content.biocomputing.device.filter.VirtualFilterTarget.MAX_MATCHERS);
        builder.push("serial");
        virtualFilterBitsPerTick = builder.defineInRange("bitsPerTick", 128L, 1L, Long.MAX_VALUE);
        virtualFilterFrameBytes = builder.defineInRange("frameBytes", 4096, 1, Integer.MAX_VALUE);
        virtualFilterReceiveFrames = builder.defineInRange("receiveFrames", 2, 1, 1024);
        virtualFilterReceiveBytes = builder.defineInRange("receiveBytes", 8192L, 1L, Long.MAX_VALUE);
        builder.pop(); builder.pop();
        builder.comment("Serial transport for the component settings output.").push("componentSettings");
        builder.push("serial");
        componentSettingsBitsPerTick = builder.defineInRange("bitsPerTick", 128L, 1L, Long.MAX_VALUE);
        componentSettingsFrameBytes = builder.defineInRange("frameBytes", 4096, 1, Integer.MAX_VALUE);
        componentSettingsReceiveFrames = builder.defineInRange("receiveFrames", 2, 1, 1024);
        componentSettingsReceiveBytes = builder.defineInRange("receiveBytes", 8192L, 1L, Long.MAX_VALUE);
        builder.pop(); builder.pop();
        builder.comment("Opt-in Stock Keeper application storage and serial interface. Lowering capacity retains old inputs.").push("stockApplication");
        stockEntries = builder.defineInRange("entries", 16, 1, 64);
        stockInputs = builder.defineInRange("queuedInputs", 8, 1, 64);
        stockRetainedUnits = builder.defineInRange("retainedUnits", 8192L, 1L, 65536L);
        stockTextChars = builder.defineInRange("textChars", 128, 1, 512);
        stockAddressChars = builder.defineInRange("addressChars", 64, 1, 128);
        builder.push("serial");
        stockSerialBitsPerTick = builder.defineInRange("bitsPerTick", 128L, 1L, Long.MAX_VALUE);
        stockSerialFrameBytes = builder.defineInRange("frameBytes", 4096, 1, Integer.MAX_VALUE);
        stockSerialReceiveFrames = builder.defineInRange("receiveFrames", 2, 1, 1024);
        stockSerialReceiveBytes = builder.defineInRange("receiveBytes", 8192L, 1L, Long.MAX_VALUE);
        builder.pop(); builder.pop();
        builder.comment("Opt-in reader sampling hardware; independent finite device buffers, not Computer RAM.").push("reader").push("serial");
        readerSerialBitsPerTick = builder.defineInRange("bitsPerTick", 128L, 1L, Long.MAX_VALUE);
        readerSerialFrameBytes = builder.defineInRange("frameBytes", 4096, 1, Integer.MAX_VALUE);
        readerSerialReceiveFrames = builder.defineInRange("receiveFrames", 2, 1, 1024);
        readerSerialReceiveBytes = builder.defineInRange("receiveBytes", 8192L, 1L, Long.MAX_VALUE);
        builder.pop(); builder.pop();
		builder.comment("Loaded-only, immutable peripheral snapshots. Network packets have a separate fixed envelope.").push("peripherals");
		allowSerializedBlockData = builder.comment("Allow authorized readers to copy full serialized block-entity data. Disabling does not disable public sync data. Computer privacy always applies.")
			.define("allowSerializedBlockData", true);
		peripheralVisited = builder.comment("Maximum inspected topology positions per capture, including air boundaries.")
			.defineInRange("visitedPositions", 2048, 1, 65536);
		peripheralReaders = builder.comment("Maximum connected directional readers in one capture.")
			.defineInRange("readers", 8, 1, 64);
		peripheralEntries = builder.comment("Maximum value/container entries in the complete captured snapshot.")
			.defineInRange("entries", 4096, 1, 262144);
		peripheralStringChars = builder.comment("Maximum aggregate UTF-16 characters, including field names.")
			.defineInRange("stringChars", 32768L, 0L, 2097152L);
		peripheralValueUnits = builder.comment("Maximum logical value units in a captured snapshot.")
			.defineInRange("valueUnits", 65536L, 1L, 16777216L);
		builder.pop();
		hardware = new HardwareProfileConfig(builder);
        carrierHardware = new CarrierHardwareConfig(builder);
        residentRadio = new ResidentRadioConfig(builder);
		builder.comment("Closed casing geometry for concentrated I/O; not shared Computer resources.").push("cluster");
		clusterConfigurationEntries = builder.comment("Controller configuration entries: bounds members, public ports, routing entries, and combined XBus/Simple I/O wires. Independent of external cable discovery. Zero inherits communication.endpoints for compatibility with existing worlds; set a positive value to decouple. Lowering this limit suspends oversized configurations without deleting stored wires.")
			.defineInRange("configurationEntries", 0, 0, 128);
		enclosureMinSize = builder.comment("Minimum outer side length in blocks, including the casing.")
			.defineInRange("minSize", 3, 3, 16);
		enclosureMaxSize = builder.comment("Maximum outer side length in blocks. Must be at least minSize.")
			.defineInRange("maxSize", 7, 3, 16);
		enclosureMaxComputers = builder.comment("Maximum named Computer communication bindings per cluster. Geometric members are never truncated by this routing limit.")
			.defineInRange("maxComputers", 32, 1, 256);
		xbusIndicatorIntervalTicks = builder.comment("Cosmetic interval for XBus waiting markers and enabled external wiring feedback. End rod particles follow physical cables and show bindings, not delivery. Does not advance programs.")
			.defineInRange("xbusIndicatorIntervalTicks", 20, 1, 1200);
		builder.pop();
		builder.comment("Explicit loaded-only communication endpoint discovery; no shared memory.").push("communication");
		communicationVisited = builder.comment("Maximum inspected topology positions including empty frontier.")
			.defineInRange("visitedPositions", 2048, 1, 65536);
		communicationEndpoints = builder.comment("Maximum connected endpoint providers, including closed or unavailable endpoints.")
			.defineInRange("endpoints", 16, 1, 128);
		inboxMessages = builder.comment("Maximum queued value messages per Computer; full queues apply backpressure.")
			.defineInRange("inboxMessages", MailboxLimits.DEFAULT.messages(), 1, 1024);
		trackedSources = builder.comment("Maximum retained sender high-water marks; never silently evicted or cleared by reset.")
			.defineInRange("trackedSources", MailboxLimits.DEFAULT.sources(), 1, 4096);
		xbusHistoryComputers = builder.comment("Dimension-wide XBus identity/retirement witnesses. Reaching capacity rejects new participants; old witnesses are never evicted.")
			.defineInRange("xbusHistoryComputers", 4096, 1, com.nobodiiiii.createbiotech.content.biocomputing.network.XBusHistorySavedData.MAX_COMPUTERS);
		xbusHistoryClaims = builder.comment("Dimension-wide XBus consumed/reserved operation identities, first-sender occupancy (one identity plus each physical pin), and retained child-task slot witnesses, without payloads. Capacity rejects before admission/consumption; no old evidence is evicted. Main identities count toward xbusHistoryComputers.")
			.defineInRange("xbusHistoryClaims", 65536, 1, com.nobodiiiii.createbiotech.content.biocomputing.network.XBusHistorySavedData.MAX_CLAIMS);
		builder.pop();
		builder.comment("Transient, menu-authorized diagnostics; never protocol recovery history or Computer RAM.").push("diagnostics");
		recentEvents = builder.comment("Retained recent state changes per Computer or Cluster. Lowering capacity evicts oldest diagnostics with an omission notice; large views are separately bounded by the menu packet.")
			.defineInRange("recentEvents", com.nobodiiiii.createbiotech.content.biocomputing.diagnostics.RecentEventLog.DEFAULT_CAPACITY, 1,
				com.nobodiiiii.createbiotech.content.biocomputing.diagnostics.RecentEventLog.MAX_CAPACITY);
		builder.pop();

        builder.comment("Basic shared cable medium; firmware chooses addressing, arbitration and retry.").push("serial");
        serialMediumBitsPerTick = builder.comment("Shared cable bits per world tick. Branches and attached computers do not multiply this rate.")
            .defineInRange("mediumBitsPerTick", 128L, 1L, Long.MAX_VALUE);
        fastSerialMediumBitsPerTick = builder.comment("Fast shared data cable bits per world tick. A branched segment still shares one channel.")
            .defineInRange("fastMediumBitsPerTick", 512L, 1L, Long.MAX_VALUE);
        duplexSerialMediumBitsPerTick = builder.comment("Full-duplex data cable bits per world tick, per direction, subject to both ports' independent circuits.")
            .defineInRange("duplexMediumBitsPerTick", 512L, 1L, Long.MAX_VALUE);
        serialFrameBytes = builder.comment("Wired medium ceiling for one encoded record; resident interface limits may be lower. No automatic fragmentation.")
            .defineInRange("frameBytes", 65536, 1, Integer.MAX_VALUE);
        serialFramingBits = builder.comment("Abstract physical framing cost per record, not a hidden ACK or a claim to implement a particular serial standard.")
            .defineInRange("framingBits", 32L, 0L, Long.MAX_VALUE - Integer.MAX_VALUE * 8L);
        serialDecodeBytes = builder.comment("NBT parser allocation ceiling for one received serial frame, separate from encoded bytes and Computer RAM.")
            .defineInRange("decodeBytes", 1048576L, 1L, Long.MAX_VALUE);
        serialIndicatorIntervalTicks = builder.comment("Cosmetic serial cable refresh interval. Phase changes display immediately; does not change physical bitrate, CPU budget or reception.")
            .defineInRange("indicatorIntervalTicks", 20, 1, 1200);
        builder.pop();
		builder.pop();
	}

	/** Construction and early NBT reads may precede NeoForge's server-config load. */
	public int serialIndicatorInterval(boolean loaded) { return value(serialIndicatorIntervalTicks, loaded); }
	public int libraryBookBytes(boolean loaded) { return value(libraryBookBytes, loaded); }
    public int libraryControlCableBlocks(boolean loaded) { return value(libraryControlCableBlocks, loaded); }
    public SerialHardware librarySerial(boolean loaded) {
        long bytes = value(librarySerialReceiveBytes, loaded);
        int frame = (int)Math.min(bytes, Math.min(value(librarySerialFrameBytes, loaded), value(serialFrameBytes, loaded)));
        return new SerialHardware(java.util.Map.of("s0", new SerialHardware.Port(value(librarySerialBitsPerTick, loaded), frame,
            value(librarySerialReceiveFrames, loaded), bytes)));
    }
    public SerialHardware redstoneSerial(boolean loaded) {
        long bytes = value(redstoneSerialReceiveBytes, loaded);
        int frame = (int)Math.min(bytes, Math.min(value(redstoneSerialFrameBytes, loaded), value(serialFrameBytes, loaded)));
        return new SerialHardware(java.util.Map.of("s0", new SerialHardware.Port(value(redstoneSerialBitsPerTick, loaded), frame,
            value(redstoneSerialReceiveFrames, loaded), bytes)));
    }
    public SerialHardware stockSerial(boolean loaded) {
        long bytes = value(stockSerialReceiveBytes, loaded);
        int frame = (int)Math.min(bytes, Math.min(value(stockSerialFrameBytes, loaded), value(serialFrameBytes, loaded)));
        return new SerialHardware(java.util.Map.of("s0", new SerialHardware.Port(value(stockSerialBitsPerTick, loaded), frame,
            value(stockSerialReceiveFrames, loaded), bytes)));
    }
    public com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.StockApplication.Limits stockApplication(boolean loaded) {
        return new com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.StockApplication.Limits(value(stockEntries, loaded),
            value(stockInputs, loaded), value(stockRetainedUnits, loaded), value(stockTextChars, loaded), value(stockAddressChars, loaded));
    }
    public SerialHardware textOutputSerial(boolean loaded) {
        long bytes = value(textOutputSerialReceiveBytes, loaded);
        int frame = (int)Math.min(bytes, Math.min(value(textOutputSerialFrameBytes, loaded), value(serialFrameBytes, loaded)));
        return new SerialHardware(java.util.Map.of("s0", new SerialHardware.Port(value(textOutputSerialBitsPerTick, loaded), frame,
            value(textOutputSerialReceiveFrames, loaded), bytes)));
    }
    public SerialHardware narratorOutputSerial(boolean loaded) {
        long bytes = value(narratorOutputSerialReceiveBytes, loaded);
        int frame = (int)Math.min(bytes, Math.min(value(narratorOutputSerialFrameBytes, loaded), value(serialFrameBytes, loaded)));
        return new SerialHardware(java.util.Map.of("s0", new SerialHardware.Port(value(narratorOutputSerialBitsPerTick, loaded), frame,
            value(narratorOutputSerialReceiveFrames, loaded), bytes)));
    }
    public int narratorOutputBytes(boolean loaded) { return value(narratorOutputBytes, loaded); }
    public int narratorOutputRange(boolean loaded) { return value(narratorOutputRange, loaded); }
    public int speakerPlaybackRange(boolean loaded) { return value(speakerPlaybackRange, loaded); }
    public int speakerPlaybackListeners(boolean loaded) { return value(speakerPlaybackListeners, loaded); }
    public int narratorOutputCooldown(boolean loaded) { return value(narratorOutputCooldown, loaded); }
    public int narratorOutputPerDimensionTick(boolean loaded) { return value(narratorOutputPerDimensionTick, loaded); }
    public SerialHardware chatOutputSerial(boolean loaded) {
        long bytes = value(chatOutputSerialReceiveBytes, loaded);
        int frame = (int)Math.min(bytes, Math.min(value(chatOutputSerialFrameBytes, loaded), value(serialFrameBytes, loaded)));
        return new SerialHardware(java.util.Map.of("s0", new SerialHardware.Port(value(chatOutputSerialBitsPerTick, loaded), frame,
            value(chatOutputSerialReceiveFrames, loaded), bytes)));
    }
    public int chatOutputBytes(boolean loaded) { return value(chatOutputBytes, loaded); }
    public int chatOutputRange(boolean loaded) { return value(chatOutputRange, loaded); }
    public int chatOutputCooldown(boolean loaded) { return value(chatOutputCooldown, loaded); }
    public int chatOutputPerDimensionSecond(boolean loaded) { return value(chatOutputPerDimensionSecond, loaded); }
    public int virtualFilterMatchers(boolean loaded) { return value(virtualFilterMatchers, loaded); }
    public SerialHardware componentSettingsSerial(boolean loaded) {
        long bytes = value(componentSettingsReceiveBytes, loaded);
        int frame = (int)Math.min(bytes, Math.min(value(componentSettingsFrameBytes, loaded), value(serialFrameBytes, loaded)));
        return new SerialHardware(java.util.Map.of("s0", new SerialHardware.Port(value(componentSettingsBitsPerTick, loaded), frame,
            value(componentSettingsReceiveFrames, loaded), bytes)));
    }
    public SerialHardware virtualFilterSerial(boolean loaded) {
        long bytes = value(virtualFilterReceiveBytes, loaded);
        int frame = (int)Math.min(bytes, Math.min(value(virtualFilterFrameBytes, loaded), value(serialFrameBytes, loaded)));
        return new SerialHardware(java.util.Map.of("s0", new SerialHardware.Port(value(virtualFilterBitsPerTick, loaded), frame,
            value(virtualFilterReceiveFrames, loaded), bytes)));
    }
    public SerialHardware terminalUiSerial(boolean loaded) {
        long bytes = value(terminalUiSerialReceiveBytes, loaded);
        int frame = (int)Math.min(bytes, Math.min(value(terminalUiSerialFrameBytes, loaded), value(serialFrameBytes, loaded)));
        return new SerialHardware(java.util.Map.of("s0", new SerialHardware.Port(value(terminalUiSerialBitsPerTick, loaded), frame,
            value(terminalUiSerialReceiveFrames, loaded), bytes)));
    }
    public TerminalUiState.Limits terminalUiLimits(boolean loaded) {
        var format = TerminalUiState.PAGE_FORMAT;
        return new TerminalUiState.Limits(value(terminalUiWidgets, loaded), value(terminalUiEvents, loaded),
            value(terminalUiInputChars, loaded),
            new SerialRecordCodec.Limits(value(terminalUiPageBytes, loaded), format.maxDecodeHeapBytes(), format.values()),
            new SerialRecordCodec.Limits(value(terminalUiEventBytes, loaded), format.maxDecodeHeapBytes(), format.values()));
    }
    public SerialHardware particleOutputSerial(boolean loaded) {
        long bytes = value(particleOutputSerialReceiveBytes, loaded);
        int frame = (int)Math.min(bytes, Math.min(value(particleOutputSerialFrameBytes, loaded), value(serialFrameBytes, loaded)));
        return new SerialHardware(java.util.Map.of("s0", new SerialHardware.Port(value(particleOutputSerialBitsPerTick, loaded), frame,
            value(particleOutputSerialReceiveFrames, loaded), bytes)));
    }
    public ParticleOutputBurst.Limits particleOutputLimits(boolean loaded) {
        var simple = value(particleOutputSimpleTypes, loaded).stream().map(ResourceLocation::parse)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new ParticleOutputBurst.Limits(value(particleOutputCount, loaded), value(particleOutputRadius, loaded),
            value(particleOutputSpeed, loaded), simple);
    }
    public int particleOutputPerDimensionTick(boolean loaded) { return value(particleOutputPerDimensionTick, loaded); }
    public SpeakerMediaLimits speakerMediaLimits(boolean loaded) {
        return new SpeakerMediaLimits(value(speakerMediaTrackBytes, loaded), value(speakerMediaWorldBytes, loaded),
            value(speakerMediaPlayerBytes, loaded), value(speakerMediaTracks, loaded));
    }
    public SerialHardware labelOutputSerial(boolean loaded) {
        long bytes = value(labelOutputSerialReceiveBytes, loaded);
        int frame = (int)Math.min(bytes, Math.min(value(labelOutputSerialFrameBytes, loaded), value(serialFrameBytes, loaded)));
        return new SerialHardware(java.util.Map.of("s0", new SerialHardware.Port(value(labelOutputSerialBitsPerTick, loaded), frame,
            value(labelOutputSerialReceiveFrames, loaded), bytes)));
    }
    public int labelOutputBytes(boolean loaded) { return value(labelOutputBytes, loaded); }
    public int labelOutputViewDistance(boolean loaded) { return value(labelOutputViewDistance, loaded); }
    public SerialHardware musicOutputSerial(boolean loaded) {
        long bytes = value(musicOutputSerialReceiveBytes, loaded);
        int frame = (int)Math.min(bytes, Math.min(value(musicOutputSerialFrameBytes, loaded), value(serialFrameBytes, loaded)));
        return new SerialHardware(java.util.Map.of("s0", new SerialHardware.Port(value(musicOutputSerialBitsPerTick, loaded), frame,
            value(musicOutputSerialReceiveFrames, loaded), bytes)));
    }
    public SerialHardware productionSerial(boolean loaded) {
        long bytes = value(productionSerialReceiveBytes, loaded);
        int frame = (int)Math.min(bytes, Math.min(value(productionSerialFrameBytes, loaded), value(serialFrameBytes, loaded)));
        return new SerialHardware(java.util.Map.of("s0", new SerialHardware.Port(value(productionSerialBitsPerTick, loaded), frame,
            value(productionSerialReceiveFrames, loaded), bytes)));
    }
    public int productionInterval(boolean loaded) { return value(productionActivityTicks, loaded); }
    public com.nobodiiiii.createbiotech.content.biocomputing.application.production.order.ProductionOrderService.Limits productionOrders(boolean loaded) {
        return new com.nobodiiiii.createbiotech.content.biocomputing.application.production.order.ProductionOrderService.Limits(
            value(productionOrderCount, loaded), value(productionOrderSteps, loaded), value(productionOrderUnits, loaded));
    }
    public com.nobodiiiii.createbiotech.content.biocomputing.application.production.order.ProductionOrderFileJournal.Limits productionArchive(boolean loaded) {
        return new com.nobodiiiii.createbiotech.content.biocomputing.application.production.order.ProductionOrderFileJournal.Limits(
            value(productionArchiveBytes, loaded), value(productionArchiveHeap, loaded), current(loaded).archive());
    }
    public com.nobodiiiii.createbiotech.content.biocomputing.device.display.TextOutputState.Limits textOutputLimits(boolean loaded) {
        return new com.nobodiiiii.createbiotech.content.biocomputing.device.display.TextOutputState.Limits(value(textOutputLines, loaded), value(textOutputBytes, loaded));
    }
    public SerialHardware readerSerial(boolean loaded) {
        long bytes = value(readerSerialReceiveBytes, loaded);
        int frame = (int)Math.min(bytes, Math.min(value(readerSerialFrameBytes, loaded), value(serialFrameBytes, loaded)));
        return new SerialHardware(java.util.Map.of("s0", new SerialHardware.Port(value(readerSerialBitsPerTick, loaded), frame,
            value(readerSerialReceiveFrames, loaded), bytes)));
    }
    public com.nobodiiiii.createbiotech.content.biocomputing.library.drive.LibraryDriveLimits libraryDrive(boolean loaded) {
        return new com.nobodiiiii.createbiotech.content.biocomputing.library.drive.LibraryDriveLimits(
            value(librarySeekBaseTicks, loaded), value(librarySeekTicksPerBlock, loaded), value(librarySelectTicks, loaded),
            value(libraryReadBaseTicks, loaded), value(libraryReadBytesPerTick, loaded), value(libraryBufferBytes, loaded), current(loaded).archive(), value(libraryCodecWorkPerTick, loaded));
    }
    public SharedSerialChannel.Limits serialMedium(boolean loaded) {
        return serialCable(SerialWireMedium.Material.BASIC, loaded).limits();
    }
    public SerialWireMedium serialCable(SerialWireMedium.Material material, boolean loaded) {
        var rate = switch (material) {
            case BASIC -> serialMediumBitsPerTick;
            case FAST -> fastSerialMediumBitsPerTick;
            case DUPLEX -> duplexSerialMediumBitsPerTick;
        };
        return new SerialWireMedium(material, new SharedSerialChannel.Limits(
            Math.multiplyExact(value(communicationEndpoints, loaded), HardwarePins.MAX_PINS), value(serialFrameBytes, loaded),
            value(rate, loaded), value(serialFramingBits, loaded)));
    }
    public com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialRecordCodec.Limits serialCodec(boolean loaded) {
        return new com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialRecordCodec.Limits(
            value(serialFrameBytes, loaded), value(serialDecodeBytes, loaded), current(loaded).archive());
    }
	public int xbusIndicatorInterval(boolean loaded) { return value(xbusIndicatorIntervalTicks, loaded); }
	public int recentEventCapacity(boolean loaded) { return value(recentEvents, loaded); }
	public int xbusHistoryComputerLimit(boolean loaded) { return value(xbusHistoryComputers, loaded); }
	public int xbusHistoryClaimLimit(boolean loaded) { return value(xbusHistoryClaims, loaded); }
	public com.nobodiiiii.createbiotech.content.biocomputing.library.service.LibraryServiceLimits libraryService(boolean loaded) {
		var common = current(loaded);
		return new com.nobodiiiii.createbiotech.content.biocomputing.library.service.LibraryServiceLimits(
			value(libraryServiceUnits, loaded), value(libraryServiceGrants, loaded), common.mailbox(), common.archive());
	}

	/** Construction and early NBT reads may precede NeoForge's server-config load. */
	public ComputerHostLimits current(boolean configLoaded) {
		return new ComputerHostLimits(new ResourceLimits(value(programUnits, configLoaded), value(memoryUnits, configLoaded),
			scaleWork(value(workUnitsPerTick, configLoaded), value(executionWorkMultiplier, configLoaded))), new ArchiveLimits(value(archiveNodes, configLoaded),
			value(archiveStringChars, configLoaded), value(archiveValueUnits, configLoaded)),
			new MailboxLimits(value(inboxMessages, configLoaded), value(trackedSources, configLoaded)),
            HardwarePins.DEFAULT, SerialHardware.NONE, value(taskSlots, configLoaded));
	}

	/** Read budgets remain server-owned and can be sampled before the config is loaded. */
	public PeripheralNetworkLimits peripherals(boolean loaded) {
		return new PeripheralNetworkLimits(
			value(peripheralVisited, loaded), value(peripheralReaders, loaded),
			new ReadLimits(value(peripheralEntries, loaded),
				value(peripheralStringChars, loaded), value(peripheralValueUnits, loaded)));
	}
	public CommunicationNetworkLimits communication(boolean loaded) {
		return new CommunicationNetworkLimits(value(communicationVisited, loaded), value(communicationEndpoints, loaded));
	}
	/** One controller's routing storage; no resident profile, production slot or member-resource aggregation. */
	public ClusterLimits cluster(boolean loaded) {
		var ceiling = current(loaded);
		int entries = value(clusterConfigurationEntries, loaded);
		return new ClusterLimits(entries == 0 ? value(communicationEndpoints, loaded) : entries, ceiling.mailbox(), ceiling.hardware().memoryUnits(), ceiling.archive());
	}
	/** Invalid min/max combinations fail explicitly instead of widening a configured boundary. */
	public EnclosureLimits enclosure(boolean loaded) {
		return new EnclosureLimits(value(enclosureMinSize, loaded), value(enclosureMaxSize, loaded), value(enclosureMaxComputers, loaded));
	}

	/** Serialization policy is separate from public sync data and Computer privacy. */
	public boolean serializedBlockDataAllowed(boolean loaded) { return value(allowSerializedBlockData, loaded); }

    public CarrierHardwareConfig.Limits carrierLimits(boolean configLoaded) { return carrierHardware.current(configLoaded); }
    public int witchNarrowWidth(boolean loaded) { return residentRadio.witchNarrowWidth(loaded); }
    public ResidentRadioHardware residentRadio(boolean configLoaded) { return residentRadio.current(configLoaded); }

	/** Resolves one resident identity against one coherent view of the current server config. */
	public HardwareSelection select(ResidentHardwareIdentity resident, boolean configLoaded) {
		ComputerHostLimits ceilings = current(configLoaded);
		if (resident == null)
			return unavailable(HardwareSelection.Status.NO_RESIDENT, null, "", ceilings);
		if (resident.entityType() == null || resident.profession() == null)
			return unavailable(HardwareSelection.Status.INVALID_RESIDENT, resident, "", ceilings);

		Optional<String> profile;
		int level;
		if (resident.entityType().equals(VILLAGER)) {
			if (resident.level() < 1 || resident.level() > 5)
				return unavailable(HardwareSelection.Status.INVALID_RESIDENT, resident, "", ceilings);
			profile = hardware.villagerProfile(resident.profession(), configLoaded);
			level = resident.level();
		} else if (ResidentModels.ENTITY_IDS.contains(resident.entityType())) {
			profile = hardware.entityProfile(resident.entityType(), configLoaded);
			level = 1;
		} else {
			return unavailable(HardwareSelection.Status.UNSUPPORTED_RESIDENT, resident, "", ceilings);
		}

		if (profile.isEmpty())
			return unavailable(HardwareSelection.Status.UNSUPPORTED_RESIDENT, resident, "", ceilings);
		if (profile.get().equals("disabled"))
			return unavailable(HardwareSelection.Status.DISABLED, resident, "disabled", ceilings);
		HardwarePins pins;
		try { pins = hardware.pins(profile.get(), configLoaded); }
		catch (IllegalArgumentException invalidPins) {
			return unavailable(HardwareSelection.Status.INVALID_PINS, resident, profile.get(), ceilings);
		}
        SerialHardware serial;
        try {
            serial = hardware.serial(profile.get(), level, configLoaded);
            serial.requireSeparateNames(pins);
            if (resident.entityType().equals(WANDERING_TRADER)) serial = residentRadio(configLoaded).expose(resident, serial, pins);
        } catch (IllegalArgumentException invalidSerial) {
            return unavailable(HardwareSelection.Status.INVALID_SERIAL, resident, profile.get(), ceilings);
        }
		var effective = new ComputerHostLimits(
			hardware.effective(profile.get(), level, configLoaded, ceilings.hardware(), value(executionWorkMultiplier, configLoaded)), ceilings.archive(), ceilings.mailbox(),
			pins, serial, hardware.taskSlots(profile.get(), level, configLoaded, ceilings.taskSlots()),
            resident.entityType().equals(WITCH) || profile.get().equals("parser") && resident.entityType().equals(VILLAGER)
                && resident.profession().equals(ResourceLocation.parse("minecraft:cleric")),
            resident.entityType().equals(WITCH) || resident.entityType().equals(VILLAGER) && level == 5);
		return new HardwareSelection(HardwareSelection.Status.READY, Optional.of(resident), profile.get(), effective);
	}

    /** Saturating multiplication keeps extreme configured budgets positive and bounded. */
    static long scaleWork(long base, int multiplier) {
        return base > Long.MAX_VALUE / multiplier ? Long.MAX_VALUE : base * multiplier;
    }

	private static HardwareSelection unavailable(HardwareSelection.Status status, ResidentHardwareIdentity resident,
		String profile, ComputerHostLimits limits) {
		return new HardwareSelection(status, Optional.ofNullable(resident), profile, limits);
	}

	private static <T> T value(ModConfigSpec.ConfigValue<T> value, boolean loaded) { return loaded ? value.get() : value.getDefault(); }
}
