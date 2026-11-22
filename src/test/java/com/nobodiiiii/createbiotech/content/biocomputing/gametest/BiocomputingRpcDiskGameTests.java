package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import static com.nobodiiiii.createbiotech.content.biocomputing.gametest.BiocomputingWorldGameTests.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MessageProtocol;
import com.nobodiiiii.createbiotech.content.biocomputing.network.EndpointConfiguration;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockEntity;
import com.nobodiiiii.createbiotech.registry.CBBlocks;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;

/** Real registered Computers and transport; witnesses cannot backfill programs, RAM or RPC state. */
@GameTestHolder("create_biotech")
public final class BiocomputingRpcDiskGameTests {
    private static final BlockPos CLIENT = new BlockPos(24, 128, 8);
    private static final BlockPos SERVER = CLIENT.east(3);
    private static final Map<String, DataType> FIELDS = Map.of("name", DataType.Scalar.TEXT, "value", DataType.Scalar.INTEGER);
    private static final String BOOT = UUID.randomUUID().toString();

    @GameTestGenerator
    public static Collection<TestFunction> diskPhases() {
        var phase = System.getProperty("biocomputing.diskPhase", "");
        if (phase.isEmpty()) return List.of();
        if (!List.of("seed", "resume", "verify").contains(phase)) throw new IllegalArgumentException("Unknown disk phase: " + phase);
        return List.of(new TestFunction("biocomputing_rpc_disk", "biocomputing_rpc_disk_" + phase,
            "create_biotech:empty", 200, 0, true, helper -> {
                helper.getLevel().setChunkForced(1, 0, true);
                helper.getLevel().getChunkAt(CLIENT);
                switch (phase) {
                    case "seed" -> seed(helper);
                    case "resume" -> resume(helper);
                    case "verify" -> verify(helper);
                    default -> throw new AssertionError(phase);
                }
            }));
    }

    private static void seed(GameTestHelper helper) {
        helper.assertTrue(!Files.exists(Path.of("rpc-disk-seed.properties")), "RPC seed requires a fresh world");
        for (var pos : List.of(CLIENT, SERVER, CLIENT.east(), CLIENT.east(2)))
            helper.assertTrue(helper.getLevel().isEmptyBlock(pos), "RPC fixture must not overwrite existing blocks");
        var client = host(computer(helper, CLIENT));
        var server = host(computer(helper, SERVER));
        client.install(clientProgram(), ComputerRuntime.MemoryPolicy.RESET);
        server.install(serverProgram(), ComputerRuntime.MemoryPolicy.RESET);
        client.configureEndpoint(new EndpointConfiguration("rpc-client", true));
        server.configureEndpoint(new EndpointConfiguration("rpc-server", true));
        server.setPaused(true); // Admit both requests, but do not execute either before the first shutdown.
        helper.getLevel().setBlock(CLIENT.east(), CBBlocks.DATA_CABLE.get().defaultBlockState(), Block.UPDATE_ALL);
        cable(helper, true);
        client.begin(record("temperature", 21));
        helper.runAfterDelay(25, () -> {
            assertDelivery(helper, client, 1);
            helper.assertValueEqual(server.snapshot().orElseThrow().mailbox().queued().size(), 1, "First request is actually admitted");
            var old = client.snapshot().orElseThrow().rpc().pending().get("search").correlation();
            client.begin(record("temperature", 34));
            helper.runAfterDelay(25, () -> {
                assertDelivery(helper, client, 1);
                var state = server.snapshot().orElseThrow();
                helper.assertTrue(state.machine().paused(), "The server remains paused while admitting requests");
                helper.assertValueEqual(state.mailbox().queued().size(), 2, "Two real cable requests are saved in order");
                helper.assertValueEqual(memory(server, "handled"), integer(0), "Neither request has executed yet");
                var latest = client.snapshot().orElseThrow().rpc().pending().get("search").correlation();
                helper.assertTrue(!old.equals(latest), "The second query replaces only its named pending generation");
                cable(helper, false); // Preserve a physical fence through both following JVM startups.
                var witness = new Properties();
                witness.setProperty("client", client.snapshot().orElseThrow().id().toString());
                witness.setProperty("server", state.id().toString());
                witness.setProperty("old", old.eventId().toString());
                witness.setProperty("latest", latest.eventId().toString());
                finish(helper, "seed", witness);
            });
        });
    }

    private static void resume(GameTestHelper helper) {
        var seed = witness(helper, "seed");
        assertDisconnected(helper);
        var client = loaded(helper, CLIENT, seed.getProperty("client"), clientProgram());
        var server = loaded(helper, SERVER, seed.getProperty("server"), serverProgram());
        assertWaiting(helper, client, seed);
        var state = server.snapshot().orElseThrow();
        helper.assertTrue(state.machine().paused(), "Pause must survive actual disk loading");
        helper.assertValueEqual(state.mailbox().queued().size(), 2, "Disk loading must retain both accepted requests");
        for (int i = 0; i < 2; i++) {
            var queued = state.mailbox().queued().get(i);
            var context = ((MessageProtocol.Request) queued.protocol()).context();
            helper.assertValueEqual(context.correlation().eventId().toString(), seed.getProperty(i == 0 ? "old" : "latest"), "Request identity survives disk");
            helper.assertValueEqual(context.returnEndpoint(), "rpc-client", "The system return address survives disk");
            helper.assertValueEqual(queued.payload(), record("temperature", i == 0 ? 21 : 34), "Typed payload is restored from the chunk");
        }
        server.setPaused(false);
        helper.runAfterDelay(30, () -> {
            assertOldReply(helper, client, server, seed);
            helper.assertValueEqual(server.transportResult().orElseThrow().code(), "not_found", "Old reply is attempting the disconnected cable route");
            var witness = new Properties();
            var delivery = server.snapshot().orElseThrow().delivery().orElseThrow();
            witness.setProperty("reply_event", delivery.eventId().toString());
            witness.setProperty("reply_sequence", Long.toString(delivery.sequenceBase()));
            finish(helper, "resume", witness);
        });
    }

    private static void verify(GameTestHelper helper) {
        var seed = witness(helper, "seed");
        var resumed = witness(helper, "resume");
        assertDisconnected(helper);
        var client = loaded(helper, CLIENT, seed.getProperty("client"), clientProgram());
        var server = loaded(helper, SERVER, seed.getProperty("server"), serverProgram());
        assertOldReply(helper, client, server, seed);
        var delivery = server.snapshot().orElseThrow().delivery().orElseThrow();
        helper.assertValueEqual(delivery.eventId().toString(), resumed.getProperty("reply_event"), "Disk must retain the old committed reply identity");
        helper.assertValueEqual(Long.toString(delivery.sequenceBase()), resumed.getProperty("reply_sequence"), "Disk must retain the reply sequence");
        client.setPaused(true); // Admission still works; observe which replies remain queued before any callback runs.
        cable(helper, true);
        helper.runAfterDelay(35, () -> {
            assertDelivery(helper, server, 1);
            helper.assertValueEqual(memory(server, "handled"), integer(2), "The old reply was acknowledged and the second request was handled");
            helper.assertTrue(server.snapshot().orElseThrow().mailbox().queued().isEmpty(), "Both requests have left the server queue");
            assertWaiting(helper, client, seed);
            var queued = client.snapshot().orElseThrow().mailbox().queued();
            helper.assertValueEqual(queued.size(), 1, "The stale reply must not occupy the paused client's queue");
            var reply = (MessageProtocol.Reply) queued.getFirst().protocol();
            helper.assertValueEqual(reply.correlation().eventId().toString(), seed.getProperty("latest"), "Only the newest reply is admitted");
            helper.assertValueEqual(queued.getFirst().payload(), record("temperature", 68), "The second query was actually computed");
            client.setPaused(false);
            helper.runAfterDelay(25, () -> {
                helper.assertValueEqual(memory(client, "result"), record("temperature", 68), "Only the newest computed result reaches client RAM");
                helper.assertValueEqual(memory(client, "replies"), integer(1), "The stale response never ran a callback");
                helper.assertTrue(client.snapshot().orElseThrow().rpc().pending().isEmpty(), "Successful reply consumes the wait");
                helper.assertTrue(client.snapshot().orElseThrow().mailbox().queued().isEmpty(), "The reply queue drains");
                helper.assertValueEqual(client.snapshot().orElseThrow().machine().status(), ComputationMachine.Status.SUCCEEDED, "The callback committed successfully");
                finish(helper, "verify", new Properties());
            });
        });
    }

    private static void assertOldReply(GameTestHelper helper, ComputerBehaviour client, ComputerBehaviour server, Properties seed) {
        assertWaiting(helper, client, seed);
        assertDelivery(helper, server, 0);
        var state = server.snapshot().orElseThrow();
        helper.assertValueEqual(state.rpc().request().orElseThrow().correlation().eventId().toString(), seed.getProperty("old"), "The undelivered reply belongs to the first request");
        helper.assertValueEqual(state.machine().output().orElseThrow().field("payload"), record("temperature", 42), "The old reply holds the first computed value");
        helper.assertValueEqual(memory(server, "handled"), integer(1), "Only the first server event is committed");
        helper.assertValueEqual(state.mailbox().queued().size(), 1, "The newer request waits behind the old outbox");
    }

    private static void assertWaiting(GameTestHelper helper, ComputerBehaviour client, Properties seed) {
        var waits = client.snapshot().orElseThrow().rpc().pending();
        helper.assertValueEqual(waits.size(), 1, "Exactly one named query generation is pending");
        helper.assertValueEqual(waits.get("search").correlation().eventId().toString(), seed.getProperty("latest"), "The latest wait is restored, not recreated");
        helper.assertValueEqual(memory(client, "result"), record("", 0), "An old response must not overwrite RAM");
        helper.assertValueEqual(memory(client, "replies"), integer(0), "No callback has run yet");
    }

    private static void assertDelivery(GameTestHelper helper, ComputerBehaviour computer, int acknowledged) {
        var state = computer.snapshot().orElseThrow();
        helper.assertValueEqual(state.machine().status(), ComputationMachine.Status.SUCCEEDED, "Only committed effects are inspected");
        helper.assertValueEqual(state.delivery().orElseThrow().acknowledged(), acknowledged, "The real transport owns the ACK cursor");
    }

    private static ComputerBehaviour loaded(GameTestHelper helper, BlockPos pos, String expectedId, ProgramArchive expectedProgram) {
        var block = helper.getLevel().getBlockEntity(pos);
        helper.assertTrue(block instanceof ComputerBlockEntity, "The saved Computer must already exist at " + pos);
        var computer = host((ComputerBlockEntity) block);
        computer.document(); // Observation only: never install, begin or load a test-generated NBT backup.
        helper.assertValueEqual(computer.snapshot().orElseThrow().id().toString(), expectedId, "Disk loading retains Computer identity");
        helper.assertValueEqual(computer.snapshot().orElseThrow().program(), expectedProgram, "Disk loading retains the installed program");
        return computer;
    }

    private static void cable(GameTestHelper helper, boolean connected) {
        helper.getLevel().setBlock(CLIENT.east(2), (connected ? CBBlocks.DATA_CABLE.get() : Blocks.AIR).defaultBlockState(), Block.UPDATE_ALL);
    }

    private static void assertDisconnected(GameTestHelper helper) {
        helper.assertTrue(helper.getLevel().isEmptyBlock(CLIENT.east(2)), "Saved disconnect prevents startup retries from repairing missing state");
    }

    private static DataValue.IntegerValue integer(long value) { return new DataValue.IntegerValue(value); }

    private static ProgramArchive clientProgram() {
        var nodes = new LinkedHashMap<String, ComputationGraph.Node>(payloadNodes());
        nodes.put("target", new ComputationGraph.Constant(new DataValue.TextValue("rpc-server")));
        var root = new ComputationGraph(FIELDS, nodes, Map.of("target", "target", "payload", "payload"));
        return new ProgramArchive(new ProgramDefinition(root, Map.of(
            "result", new ProgramDefinition.Cell(new DataType.RecordType(FIELDS), record("", 0)),
            "replies", new ProgramDefinition.Cell(DataType.Scalar.INTEGER, integer(0))), List.of(),
            List.of(ProgramDefinition.Send.request("target", "payload", "search", "result")),
            Map.of("result", handler("replies", false))), Map.of());
    }

    private static ProgramArchive serverProgram() {
        return new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()),
            Map.of("handled", new ProgramDefinition.Cell(DataType.Scalar.INTEGER, integer(0))), List.of(), List.of(),
            Map.of("request", handler("handled", true))), Map.of());
    }

    private static ProgramDefinition.Entry handler(String count, boolean server) {
        var nodes = new LinkedHashMap<String, ComputationGraph.Node>(payloadNodes());
        nodes.put("old", new ComputationGraph.ReadMemory(count));
        nodes.put("one", new ComputationGraph.Constant(integer(1)));
        nodes.put("next", new ComputationGraph.Binary(ComputationGraph.BinaryOp.ADD_INTEGER, "old", "one"));
        if (server) {
            nodes.put("double", new ComputationGraph.Binary(ComputationGraph.BinaryOp.ADD_INTEGER, "value", "value"));
            nodes.put("payload", new ComputationGraph.MakeRecord(Map.of("name", "name", "value", "double")));
        }
        return new ProgramDefinition.Entry(new ComputationGraph(FIELDS, nodes, Map.of("payload", "payload", "count", "next")),
            server ? List.of(new ProgramDefinition.Write(count, "count"))
                : List.of(new ProgramDefinition.Write(count, "count"), new ProgramDefinition.Write("result", "payload")),
            server ? List.of(ProgramDefinition.Send.reply("payload")) : List.of());
    }

    private static Map<String, ComputationGraph.Node> payloadNodes() {
        return Map.of("name", new ComputationGraph.Input("name"), "value", new ComputationGraph.Input("value"),
            "payload", new ComputationGraph.MakeRecord(Map.of("name", "name", "value", "value")));
    }

    private static Properties witness(GameTestHelper helper, String phase) {
        var path = Path.of("rpc-disk-" + phase + ".properties");
        helper.assertTrue(Files.isRegularFile(path), "Missing previous RPC disk witness: " + phase);
        var properties = new Properties();
        try (var input = Files.newInputStream(path)) { properties.load(input); }
        catch (IOException error) { throw new UncheckedIOException(error); }
        helper.assertTrue(!BOOT.equals(properties.getProperty("boot")), "Each RPC phase must use a different JVM boot");
        return properties;
    }

    private static void finish(GameTestHelper helper, String phase, Properties properties) {
        properties.setProperty("boot", BOOT);
        properties.setProperty("pid", Long.toString(ProcessHandle.current().pid()));
        try (var output = Files.newOutputStream(Path.of("rpc-disk-" + phase + ".properties"), StandardOpenOption.CREATE_NEW)) {
            properties.store(output, "Identity witness only; not a Computer data backup");
        } catch (IOException error) { throw new UncheckedIOException(error); }
        System.out.println("BIOCOMPUTING_RPC_DISK_PHASE " + phase + " passed; pid=" + ProcessHandle.current().pid() + " boot=" + BOOT);
        helper.succeed();
    }
}
