package com.nobodiiiii.createbiotech.content.biocomputing.hardware;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import com.electronwill.nightconfig.core.CommentedConfig;
import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.fml.config.ModConfig;
import java.nio.file.Path;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.junit.jupiter.api.Test;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.*;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;

class BiocomputingConfigTest {
    @Test void libraryMechanismLimitsReloadIndependentlyFromComputerResourcesAndCableSpeed() throws ReflectiveOperationException {
        var builder = new ModConfigSpec.Builder(); var config = new BiocomputingConfig(builder); var spec = builder.build();
        var defaults = config.libraryDrive(false);
        assertTrue(defaults.seekBaseTicks() > 0 && defaults.selectTicks() > 0 && defaults.readBytesPerTick() > 0);
        var values = CommentedConfig.inMemory(); spec.correct(values);
        values.set("biocomputing.library.drive.seekBaseTicks", 7L);
        values.set("biocomputing.library.drive.seekTicksPerBlock", 5L);
        values.set("biocomputing.library.drive.selectTicks", 3L);
        values.set("biocomputing.library.drive.readBaseTicks", 9L);
        values.set("biocomputing.library.drive.readBytesPerTick", 8L);
        values.set("biocomputing.library.drive.codecWorkPerTick", 11L);
        values.set("biocomputing.library.drive.bufferBytes", 4096L);
        values.set("biocomputing.library.drive.controlCableBlocks", 3);
        var constructor = Class.forName("net.neoforged.fml.config.LoadedConfig").getDeclaredConstructor(CommentedConfig.class, Path.class, ModConfig.class);
        constructor.setAccessible(true); spec.acceptConfig((IConfigSpec.ILoadedConfig)constructor.newInstance(values, null, null));
        try {
            var limits = config.libraryDrive(true);
            assertEquals(11,limits.codecWorkPerTick());
            assertEquals(3, config.libraryControlCableBlocks(true));
            assertEquals(22, limits.seekTicks(new com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryTrackAddress(0, 0, 0),
                new com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryTrackAddress(2, 1, 0)));
            assertEquals(3, limits.selectTicks()); assertEquals(12, limits.readTicks(17)); assertEquals(4096, limits.bufferBytes());
            var computer = config.current(true); var serial = config.serialMedium(true); var bookBytes = config.libraryBookBytes(true);
            values.set("biocomputing.library.drive.readBytesPerTick", 16L); spec.afterReload();
            assertEquals(11, config.libraryDrive(true).readTicks(17));
            assertEquals(11, config.libraryDrive(true).codecWorkPerTick());
            assertEquals(computer, config.current(true)); assertEquals(serial, config.serialMedium(true)); assertEquals(bookBytes, config.libraryBookBytes(true));
            assertEquals(defaults, config.libraryDrive(false));
            var mechanical = config.libraryDrive(true);
            values.set("biocomputing.library.drive.serial.bitsPerTick", 37L);
            values.set("biocomputing.library.drive.serial.frameBytes", 1024);
            values.set("biocomputing.library.drive.serial.receiveFrames", 3);
            values.set("biocomputing.library.drive.serial.receiveBytes", 2048L); spec.afterReload();
            var port = config.librarySerial(true).ports().get("s0");
            assertEquals(37, port.bitsPerTick()); assertEquals(1024, port.maxFrameBytes()); assertEquals(3, port.receiveFrames()); assertEquals(2048, port.receiveBytes());
            assertEquals(computer, config.current(true)); assertEquals(serial, config.serialMedium(true)); assertEquals(mechanical, config.libraryDrive(true));
            values.set("biocomputing.library.drive.serial.receiveBytes", 512L); spec.afterReload();
            assertEquals(512, config.librarySerial(true).ports().get("s0").maxFrameBytes(), "Smaller hardware stays bounded; existing device queues still restore atomically");
        } finally { spec.acceptConfig(null); }
    }
    @Test void serialIndicatorReloadDoesNotChangeHardwareOrMediumLimits() throws ReflectiveOperationException {
        var builder = new ModConfigSpec.Builder(); var config = new BiocomputingConfig(builder); var spec = builder.build();
        assertEquals(20, config.serialIndicatorInterval(false));
        var values = CommentedConfig.inMemory(); spec.correct(values);
        values.set("biocomputing.serial.indicatorIntervalTicks", 7);
        var constructor = Class.forName("net.neoforged.fml.config.LoadedConfig").getDeclaredConstructor(CommentedConfig.class, Path.class, ModConfig.class);
        constructor.setAccessible(true); spec.acceptConfig((IConfigSpec.ILoadedConfig)constructor.newInstance(values, null, null));
        try {
            assertEquals(7, config.serialIndicatorInterval(true));
            var medium = config.serialMedium(true); var computer = config.current(true);
            values.set("biocomputing.serial.indicatorIntervalTicks", 1); spec.afterReload();
            assertEquals(1, config.serialIndicatorInterval(true));
            assertEquals(medium, config.serialMedium(true)); assertEquals(computer, config.current(true));
        } finally { spec.acceptConfig(null); }
    }
    @Test void recentEventCapacityIsSeparateReloadableAndLoweringReportsOmittedEvents() throws ReflectiveOperationException {
        var builder = new ModConfigSpec.Builder(); var config = new BiocomputingConfig(builder); var spec = builder.build();
        assertEquals(32, config.recentEventCapacity(false));
        var values = CommentedConfig.inMemory(); spec.correct(values);
        values.set("biocomputing.diagnostics.recentEvents", 4);
        var constructor = Class.forName("net.neoforged.fml.config.LoadedConfig").getDeclaredConstructor(CommentedConfig.class, Path.class, ModConfig.class);
        constructor.setAccessible(true); spec.acceptConfig((IConfigSpec.ILoadedConfig) constructor.newInstance(values, null, null));
        try {
            var log = new com.nobodiiiii.createbiotech.content.biocomputing.diagnostics.RecentEventLog(() -> 17L, () -> config.recentEventCapacity(true));
            for (int i = 0; i < 6; i++) log.append(com.nobodiiiii.createbiotech.content.biocomputing.diagnostics.RecentEventLog.Kind.WAIT, "waiting_read", "x0");
            assertEquals(new com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue.IntegerValue(2), log.value().field("omitted"));
            values.set("biocomputing.diagnostics.recentEvents", 2); spec.afterReload();
            assertEquals(2, ((com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue.ListValue)log.value().field("entries")).values().size());
            assertEquals(new com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue.IntegerValue(4), log.value().field("omitted"));
            assertEquals(config.current(true).hardware(), config.current(false).hardware());
        } finally { spec.acceptConfig(null); }
    }
    @Test void xbusHistoryLimitsUseReloadableServerConfigWithoutAddingHardwareOrMailboxCapacity() throws ReflectiveOperationException {
        var builder = new ModConfigSpec.Builder(); var config = new BiocomputingConfig(builder); var spec = builder.build();
        assertEquals(4096, config.xbusHistoryComputerLimit(false)); assertEquals(65536, config.xbusHistoryClaimLimit(false));
        var values = CommentedConfig.inMemory(); spec.correct(values);
        values.set("biocomputing.communication.xbusHistoryComputers", 8); values.set("biocomputing.communication.xbusHistoryClaims", 32);
        var constructor = Class.forName("net.neoforged.fml.config.LoadedConfig").getDeclaredConstructor(CommentedConfig.class, Path.class, ModConfig.class);
        constructor.setAccessible(true); spec.acceptConfig((IConfigSpec.ILoadedConfig) constructor.newInstance(values, null, null));
        try {
            assertEquals(8, config.xbusHistoryComputerLimit(true)); assertEquals(32, config.xbusHistoryClaimLimit(true));
            values.set("biocomputing.communication.xbusHistoryClaims", 1); spec.afterReload();
            assertEquals(1, config.xbusHistoryClaimLimit(true));
            assertEquals(config.current(false), config.current(true));
        } finally { spec.acceptConfig(null); }
    }
	@Test void enclosureUsesIndependentConfigAndReloadChangesAcceptance() throws ReflectiveOperationException {
		var builder = new ModConfigSpec.Builder(); var config = new BiocomputingConfig(builder); var spec = builder.build();
		var values = CommentedConfig.inMemory(); spec.correct(values);
		values.set("biocomputing.cluster.minSize", 4); values.set("biocomputing.cluster.maxSize", 6); values.set("biocomputing.cluster.maxComputers", 2);
		var constructor = Class.forName("net.neoforged.fml.config.LoadedConfig").getDeclaredConstructor(CommentedConfig.class, Path.class, ModConfig.class);
		constructor.setAccessible(true); spec.acceptConfig((IConfigSpec.ILoadedConfig) constructor.newInstance(values, null, null));
		try {
			assertEquals(new EnclosureLimits(4, 6, 2), config.enclosure(true));
			values.set("biocomputing.cluster.maxSize", 3); spec.afterReload();
			assertThrows(IllegalArgumentException.class, () -> config.enclosure(true)); // Conflicting min/max never silently relaxes the limit.
			values.set("biocomputing.cluster.minSize", 3); spec.afterReload();
			assertEquals(new EnclosureLimits(3, 3, 2), config.enclosure(true));
		} finally { spec.acceptConfig(null); }
		assertEquals(new EnclosureLimits(3, 7, 32), config.enclosure(false));
	}
    @Test void explicitClusterCapacityIsIndependentAndLoweringItPreservesWiring() throws ReflectiveOperationException {
        var builder=new ModConfigSpec.Builder();var config=new BiocomputingConfig(builder);var spec=builder.build();
        var values=CommentedConfig.inMemory();spec.correct(values);
        var constructor=Class.forName("net.neoforged.fml.config.LoadedConfig").getDeclaredConstructor(CommentedConfig.class,Path.class,ModConfig.class);
        constructor.setAccessible(true);spec.acceptConfig((IConfigSpec.ILoadedConfig)constructor.newInstance(values,null,null));
        values.set("biocomputing.cluster.configurationEntries",19);spec.afterReload();
        try {
            var members=new java.util.LinkedHashMap<String,ClusterConfiguration.Member>();
            for(int i=0;i<12;i++)members.put("c"+i,new ClusterConfiguration.Member(UUID.randomUUID(),new BlockPos(i,0,0)));
            var wires=new java.util.LinkedHashMap<String,XBusWire>();
            for(int i=0;i<14;i++){String a="c"+(i%12),b="c"+((i+1)%12),pin=i<12?"x0":"x1";wires.put("w"+i,new XBusWire(new XBusWire.Pin(a,a,pin),new XBusWire.Pin(b,b,pin),true));}
            var signals=new java.util.LinkedHashMap<String,SimpleIoWire>();
            for(int i=0;i<5;i++){String a="c"+i,b="c"+(i+1);signals.put("s"+i,new SimpleIoWire(new SimpleIoWire.Pin(a,a,"p0"),new SimpleIoWire.Pin(b,b,"p0"),true));}
            var routes=new ClusterConfiguration(members,Map.of(),Map.of(),wires,signals);
            var router=new ClusterRouter(()->{});
            assertEquals("configured",router.configure(routes,config.cluster(true)));
            assertEquals(16,config.communication(true).maxEndpoints(),"More internal wiring must not broaden external discovery");
            var saved=router.write();
            values.set("biocomputing.communication.endpoints",2);spec.afterReload();
            assertEquals(19,config.cluster(true).configurationEntries());assertEquals(2,config.communication(true).maxEndpoints());
            values.set("biocomputing.cluster.configurationEntries",18);spec.afterReload();
            assertTrue(router.snapshot(config.cluster(true)).isEmpty());assertEquals("configuration_limit",router.diagnostic().orElseThrow());
            assertEquals(saved,router.write(),"A lower limit must not truncate or rewrite stored wires");
            values.set("biocomputing.cluster.configurationEntries",19);spec.afterReload();
            var restored=new ClusterRouter(()->{});restored.read(saved);
            assertEquals(routes,restored.snapshot(config.cluster(true)).orElseThrow().configuration());
            values.set("biocomputing.cluster.configurationEntries",0);values.set("biocomputing.communication.endpoints",32);spec.afterReload();
            assertEquals(32,config.cluster(true).configurationEntries(),"Zero preserves old configured capacity");
            assertEquals(config.current(false),config.current(true),"No CPU/RAM/ISA capacity change");
        } finally {spec.acceptConfig(null);}
        assertEquals(16,config.cluster(false).configurationEntries(),"Unconfigured worlds retain the old default");
    }
	@Test void clusterRouteCapacityUsesLoadedCommunicationConfigAndRecoversOnReload() throws ReflectiveOperationException {
		var builder = new ModConfigSpec.Builder(); var config = new BiocomputingConfig(builder); var spec = builder.build();
		var values = CommentedConfig.inMemory(); spec.correct(values); values.set("biocomputing.communication.endpoints", 1);
		var constructor = Class.forName("net.neoforged.fml.config.LoadedConfig").getDeclaredConstructor(CommentedConfig.class, Path.class, ModConfig.class);
		constructor.setAccessible(true); spec.acceptConfig((IConfigSpec.ILoadedConfig) constructor.newInstance(values, null, null));
		try {
			var member = new ClusterConfiguration.Member(UUID.randomUUID(), BlockPos.ZERO);
			var routes = new ClusterConfiguration(Map.of("A", member), Map.of(
				"one", new ClusterConfiguration.Port("A", "input", "out1", "external", true),
				"two", new ClusterConfiguration.Port("A", "input", "out2", "external", true)));
			var router = new ClusterRouter(() -> {});
			assertEquals("configuration_limit", router.configure(routes, config.cluster(true)));
			values.set("biocomputing.communication.endpoints", 2); spec.afterReload();
			assertEquals("configured", router.configure(routes, config.cluster(true)));
			assertEquals(config.current(true).mailbox(), config.cluster(true).mailbox());
			assertEquals(config.current(true).archive(), config.cluster(true).archive());
			assertEquals(config.current(true).hardware().memoryUnits(), config.cluster(true).stateUnits());
		} finally { spec.acceptConfig(null); }
		assertNotNull(config.cluster(false));
	}
	@Test void fallbackCanBeReadBeforeServerConfigLoadsAndHasAnIndependentNamespace() {
		var builder = new ModConfigSpec.Builder();
		var config = new BiocomputingConfig(builder);
		var spec = builder.build();
		var limits = config.current(false);
		assertEquals(256, limits.hardware().programUnits());
		assertEquals(262144, limits.hardware().memoryUnits());
		assertEquals(256, limits.hardware().workPerSlice());
		assertSame(config.programUnits, spec.getValues().get(List.of("biocomputing", "programUnits")));
		assertSame(config.archiveNodes, spec.getValues().get(List.of("biocomputing", "archiveNodes")));
		assertFalse(spec.getValues().contains("factoryCluster"));
		assertTrue(limits.archive().maxValueUnits() > limits.hardware().memoryUnits());
	}

	@Test void loadedValuesAndReloadsComeFromTheServerConfigRatherThanCachedDefaults() throws ReflectiveOperationException {
		var builder = new ModConfigSpec.Builder(); var config = new BiocomputingConfig(builder); var spec = builder.build();
		var values = CommentedConfig.inMemory(); spec.correct(values);
		values.set("biocomputing.programUnits", 512L); values.set("biocomputing.workUnitsPerTick", 2L);
		// ILoadedConfig is sealed; construct the actual loader record instead of shipping a fake implementation.
		var constructor = Class.forName("net.neoforged.fml.config.LoadedConfig")
			.getDeclaredConstructor(CommentedConfig.class, Path.class, ModConfig.class);
		constructor.setAccessible(true);
		spec.acceptConfig((IConfigSpec.ILoadedConfig) constructor.newInstance(values, null, null));
		try {
			assertEquals(512, config.current(spec.isLoaded()).hardware().programUnits());
			assertEquals(16, config.current(spec.isLoaded()).hardware().workPerSlice());
			values.set("biocomputing.workUnitsPerTick", 7L); spec.afterReload();
			assertEquals(56, config.current(spec.isLoaded()).hardware().workPerSlice());
		} finally { spec.acceptConfig(null); }
		assertEquals(256, config.current(spec.isLoaded()).hardware().workPerSlice());
	}
}
