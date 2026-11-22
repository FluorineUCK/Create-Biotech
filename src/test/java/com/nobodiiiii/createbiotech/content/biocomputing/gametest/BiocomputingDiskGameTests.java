package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import static com.nobodiiiii.createbiotech.content.biocomputing.gametest.BiocomputingWorldGameTests.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationMachine;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBehaviour;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerRuntime;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import com.nobodiiiii.createbiotech.content.biocomputing.network.EndpointConfiguration;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockEntity;
import com.nobodiiiii.createbiotech.registry.CBBlocks;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;

/** Three separate JVMs; only Minecraft's normal chunk loading restores device data. */
@GameTestHolder("create_biotech")
public final class BiocomputingDiskGameTests {
    // Outside GameTest's random structure plane (y=-59), in one explicitly ticking chunk.
    private static final BlockPos SOURCE = new BlockPos(8, 128, 8);
    private static final BlockPos RECEIVER = SOURCE.east(3);
    private static final BlockPos COUNTER = SOURCE.south(3);
    private static final DataValue.RecordValue EMPTY = new DataValue.RecordValue(Map.of());
    private static final String BOOT = UUID.randomUUID().toString();

    @GameTestGenerator
    public static Collection<TestFunction> diskPhases() {
        var phase = System.getProperty("biocomputing.diskPhase", "");
        if (phase.isEmpty()) return List.of(); // Ordinary live tests do not include a vacuous disk test.
        if (!List.of("seed", "resume", "verify").contains(phase)) throw new IllegalArgumentException("Unknown disk phase: " + phase);
        return List.of(new TestFunction("biocomputing_disk", "biocomputing_disk_" + phase,
            "create_biotech:empty", 180, 0, true, helper -> {
                var level = helper.getLevel();
                level.setChunkForced(0, 0, true);
                level.getChunkAt(SOURCE);
                switch (phase) {
                    case "seed" -> seed(helper);
                    case "resume" -> resume(helper);
                    case "verify" -> verify(helper);
                    default -> throw new AssertionError(phase);
                }
            }));
    }

    private static void seed(GameTestHelper helper) {
        helper.assertTrue(!Files.exists(Path.of("disk-seed.properties")), "Seed must use a fresh verification world");
        for (var pos : List.of(SOURCE, RECEIVER, COUNTER))
            helper.assertTrue(helper.getLevel().isEmptyBlock(pos), "Seed must not overwrite an existing device");
        var source = host(computer(helper, SOURCE));
        var receiver = host(computer(helper, RECEIVER));
        var counter = host(computer(helper, COUNTER));
        source.install(sender(), ComputerRuntime.MemoryPolicy.RESET);
        receiver.install(receiver(), ComputerRuntime.MemoryPolicy.RESET);
        counter.install(counter(), ComputerRuntime.MemoryPolicy.RESET);
        source.configureEndpoint(new EndpointConfiguration("source", true));
        receiver.configureEndpoint(new EndpointConfiguration("receiver", true));
        helper.getLevel().setBlock(SOURCE.east(), CBBlocks.DATA_CABLE.get().defaultBlockState(), Block.UPDATE_ALL);
        source.begin(EMPTY);
        counter.begin(EMPTY);
        helper.runAfterDelay(25, () -> {
            assertNumber(helper, counter, "count", 1);
            counter.setPaused(true);
            counter.begin(EMPTY); // Preserve both noninitial RAM and an unfinished second event.
            helper.runAfterDelay(8, () -> {
                assertNumber(helper, counter, "count", 1);
                assertNumber(helper, receiver, "deliveries", 0);
                assertOutbox(helper, source, 0);
                helper.assertValueEqual(source.transportResult().orElseThrow().code(), "not_found", "The real disconnected transport attempted delivery");
                var witness = new Properties();
                witness.setProperty("source", source.snapshot().orElseThrow().id().toString());
                witness.setProperty("receiver", receiver.snapshot().orElseThrow().id().toString());
                witness.setProperty("counter", counter.snapshot().orElseThrow().id().toString());
                var delivery = source.snapshot().orElseThrow().delivery().orElseThrow();
                witness.setProperty("event", delivery.eventId().toString());
                witness.setProperty("sequence", Long.toString(delivery.sequenceBase()));
                finish(helper, "seed", witness);
            });
        });
    }

    private static void resume(GameTestHelper helper) {
        var seed = witness(helper, "seed");
        var source = loaded(helper, SOURCE, seed.getProperty("source"), sender());
        var receiver = loaded(helper, RECEIVER, seed.getProperty("receiver"), receiver());
        var counter = loaded(helper, COUNTER, seed.getProperty("counter"), counter());
        assertDeliveryIdentity(helper, source, seed);
        assertOutbox(helper, source, 0);
        assertNumber(helper, counter, "count", 1);
        assertNumber(helper, receiver, "deliveries", 0);
        helper.assertTrue(counter.snapshot().orElseThrow().machine().paused(), "Disk loading must preserve pause");
        helper.runAfterDelay(12, () -> {
            assertNumber(helper, counter, "count", 1);
            assertOutbox(helper, source, 0);
            counter.setPaused(false);
            helper.getLevel().setBlock(SOURCE.east(2), CBBlocks.DATA_CABLE.get().defaultBlockState(), Block.UPDATE_ALL);
            helper.runAfterDelay(35, () -> {
                assertCompleted(helper, source, receiver, counter);
                assertDeliveryIdentity(helper, source, seed);
                // Forced chunks can tick before the next GameTest starts. Prevent a startup retry
                // from repairing a lost ACK or receiver RAM before verify can observe it.
                helper.getLevel().setBlock(SOURCE.east(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                helper.assertTrue(helper.getLevel().isEmptyBlock(SOURCE.east(2)), "Save the final ACK while disconnected");
                finish(helper, "resume", new Properties());
            });
        });
    }

    private static void verify(GameTestHelper helper) {
        var seed = witness(helper, "seed");
        witness(helper, "resume");
        helper.assertTrue(helper.getLevel().isEmptyBlock(SOURCE.east(2)), "The saved disconnect must prevent pre-assertion delivery retries");
        var source = loaded(helper, SOURCE, seed.getProperty("source"), sender());
        var receiver = loaded(helper, RECEIVER, seed.getProperty("receiver"), receiver());
        var counter = loaded(helper, COUNTER, seed.getProperty("counter"), counter());
        assertCompleted(helper, source, receiver, counter);
        assertDeliveryIdentity(helper, source, seed);
        helper.getLevel().setBlock(SOURCE.east(2), CBBlocks.DATA_CABLE.get().defaultBlockState(), Block.UPDATE_ALL);
        helper.runAfterDelay(35, () -> {
            assertCompleted(helper, source, receiver, counter);
            finish(helper, "verify", new Properties());
        });
    }

    private static ComputerBehaviour loaded(GameTestHelper helper, BlockPos pos, String expectedId, ProgramArchive program) {
        var block = helper.getLevel().getBlockEntity(pos);
        helper.assertTrue(block instanceof ComputerBlockEntity, "Computer must exist in the saved chunk at " + pos);
        var host = host((ComputerBlockEntity) block);
        host.document(); // Authorized observation, never installation, begin, or NBT backfill.
        var state = host.snapshot().orElseThrow();
        helper.assertValueEqual(state.id().toString(), expectedId, "Disk loading must retain device identity");
        helper.assertValueEqual(state.program(), program, "Disk loading must retain the entire installed program");
        return host;
    }

    private static void assertCompleted(GameTestHelper helper, ComputerBehaviour source, ComputerBehaviour receiver, ComputerBehaviour counter) {
        assertNumber(helper, counter, "count", 2);
        assertNumber(helper, source, "sentinel", 99);
        assertNumber(helper, receiver, "deliveries", 1);
        helper.assertValueEqual(memory(receiver, "received"), record("temperature", 21), "The saved receiver has both typed named values");
        helper.assertTrue(!counter.snapshot().orElseThrow().machine().paused(), "Resumed pause state persists too");
        assertOutbox(helper, source, 1);
    }

    private static void assertOutbox(GameTestHelper helper, ComputerBehaviour source, int acknowledged) {
        var state = source.snapshot().orElseThrow();
        helper.assertValueEqual(state.machine().status(), ComputationMachine.Status.SUCCEEDED, "The send is already committed");
        helper.assertValueEqual(state.delivery().orElseThrow().acknowledged(), acknowledged, "The committed outbox acknowledgement cursor is durable");
    }

    private static void assertDeliveryIdentity(GameTestHelper helper, ComputerBehaviour source, Properties seed) {
        var delivery = source.snapshot().orElseThrow().delivery().orElseThrow();
        helper.assertValueEqual(delivery.eventId().toString(), seed.getProperty("event"), "Do not replace the saved send event");
        helper.assertValueEqual(Long.toString(delivery.sequenceBase()), seed.getProperty("sequence"), "Do not reallocate a message identity");
    }

    private static void assertNumber(GameTestHelper helper, ComputerBehaviour host, String cell, long expected) {
        helper.assertValueEqual(memory(host, cell), new DataValue.IntegerValue(expected), "Persisted RAM: " + cell);
    }

    private static Properties witness(GameTestHelper helper, String phase) {
        var path = Path.of("disk-" + phase + ".properties");
        helper.assertTrue(Files.isRegularFile(path), "Missing previous disk phase witness: " + phase);
        var properties = new Properties();
        try (var input = Files.newInputStream(path)) {
            properties.load(input);
        } catch (IOException exception) { throw new UncheckedIOException(exception); }
        helper.assertTrue(!BOOT.equals(properties.getProperty("boot")), "Each phase must use a different JVM boot");
        return properties;
    }

    private static void finish(GameTestHelper helper, String phase, Properties properties) {
        properties.setProperty("boot", BOOT);
        properties.setProperty("pid", Long.toString(ProcessHandle.current().pid()));
        // A witness contains identities only. Minecraft writes all program/RAM/outbox data on normal shutdown.
        try (var output = Files.newOutputStream(Path.of("disk-" + phase + ".properties"), StandardOpenOption.CREATE_NEW)) {
            properties.store(output, "Disk verification witness; not a device backup or restoration source");
        } catch (IOException exception) { throw new UncheckedIOException(exception); }
        System.out.println("BIOCOMPUTING_DISK_PHASE " + phase + " passed; pid=" + ProcessHandle.current().pid() + " boot=" + BOOT);
        helper.succeed();
    }
}
