package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.ModuleDefinition;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerRuntime;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.PreparedProgram;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowBlockRules;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowCallBindings;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowRuntime;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.XBusExchange;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.BiocomputingConfig;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ResidentHardwareIdentity;
import com.nobodiiiii.createbiotech.content.biocomputing.library.drive.LibraryDriveCommand;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CableDiscovery;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationNetworkLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialDevice;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialNetwork;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.junit.jupiter.api.Test;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpeakerMediaLibraryStageProgramTest {
    private static final Path EXAMPLES = Path.of("examples/biocomputing");
    private static final Path EXAMPLE_ASSETS = Path.of("src/main/resources/assets/create_biotech/biocomputing/examples");
    private static final DataType.RecordType JOB = new DataType.RecordType(Map.of("o", TEXT, "d", ANY, "p", BOOLEAN));
    private static final DataType.RecordType CALL = new DataType.RecordType(Map.of("p", ANY, "e", TEXT));
    private static final DataType.RecordType REPLY = new DataType.RecordType(Map.of("r", ANY));
    private static final DataType ROWS = new DataType.ListType(new DataType.RecordType(
        Map.of("o", TEXT, "s", BOOLEAN, "a", INTEGER, "e", TEXT, "c", BOOLEAN, "z", BOOLEAN)));

    @Test void eachMediaStagePreparesWithTwoPinsOnTheDefaultMasterCleric() throws Exception {
        var limits = hardware();
        for (var source : Map.of("Library", libraryStage(), "Speaker token", outputStage(), "Speaker frame", frameStage(),
                "Play coordinator", coordinator(true), "Stop coordinator", coordinator(false), "Delivery", deliveryStage(),
                "Serial bridge", wireStage()).entrySet()) {
            var document = source.getValue();
            var metering = new com.nobodiiiii.createbiotech.content.biocomputing.computation.ResourceLimits(4096, limits.hardware().memoryUnits(), limits.hardware().workPerSlice());
            var units = document.flow().orElseThrow().program(document.archive()).usage(metering).programUnits();
            System.out.println(source.getKey() + " media stage code units: " + units);
            PreparedProgram.prepare(document, limits);
            assertTrue(units <= limits.hardware().programUnits());
        }
    }

    @Test void eachWorkerStaysReadyWhenItsUpstreamMachineHasNotBeenStarted() throws Exception {
        var limits = hardware();
        for (var document : List.of(libraryStage(), deliveryStage(), outputStage(), frameStage(), wireStage())) {
            var worker = new ComputerBlockHost(() -> {});
            worker.installProgram(PreparedProgram.prepare(document, limits), ComputerRuntime.MemoryPolicy.RESET, limits, true);
            worker.setPaused(false, limits, true);
            for (int tick = 0; tick < 30; tick++) {
                worker.tick(limits, true, Map.of("x0", 0));
                // This is the same rejection the world scheduler applies if the peer is paused.
                worker.rejectPausedXBus("x0", limits);
                var current = worker.flowSnapshot().orElseThrow();
                assertFalse(current.state().status() == FlowRuntime.Status.FAILED, () -> current.state().diagnostic().toString());
                assertFalse(current.pinClaims().entries().stream().anyMatch(claim -> claim.pin().equals("x0")),
                    "A worker must not claim a read while there is no sender");
            }
        }
    }

    @Test void editableDefaultMediaArchivesMatchTheirSources() throws Exception {
        var limits = hardware();
        var documents = Map.of("speaker_media_default_play", coordinator(true), "speaker_media_default_stop", coordinator(false),
            "speaker_media_library_stage", libraryStage(), "speaker_media_delivery_stage", deliveryStage(),
            "speaker_media_output_stage", outputStage(), "speaker_media_frame_stage", frameStage());
        for (var entry : documents.entrySet()) {
            String filename = entry.getKey() + ".bcp";
            var editable = EXAMPLES.resolve(filename);
            var bundled = EXAMPLE_ASSETS.resolve(filename);
            if ("1".equals(System.getenv("GENERATE_DEFAULT_SPEAKER_MEDIA"))) {
                String archive = Base64.getEncoder().encodeToString(ProgramDocumentWire.encode(entry.getValue(), limits.archive())) + "\n";
                Files.writeString(editable, archive);
                Files.writeString(bundled, archive);
            }
            assertEquals(Files.readString(editable), Files.readString(bundled));
            var document = ProgramDocumentWire.decode(Base64.getDecoder().decode(Files.readString(bundled).strip()), limits.archive());
            assertEquals(entry.getValue(), document, filename);
            PreparedProgram.prepare(document, limits);
        }
    }

    @Test void defaultHardwareProgramsPlayThroughTheSerialBridgeAndCanStopWithoutTheLibrary() throws Exception {
        var track = zeroPosition();
        var reference = new DataValue.RecordValue(Map.of("library", track, "device", text("book-device"),
            "sequence", number(3), "version", new DataValue.RecordValue(Map.of("entry", text("song")))));
        var play = new MediaBus(true);
        play.run(List.of(
            library("status", 0, "idle", 0), library("seek", 0, track, "accepted", 1),
            library("result", 1, "busy", 1), library("result", 1, "positioned", 1), library("ack", 1, "acknowledged", 1),
            library("select", 1, number(0), "accepted", 2), library("result", 2, "selected", 2),
            library("ack", 2, "acknowledged", 2), library("read_begin", 2, text("song"), "accepted", 3),
            library("read_ref", 3, "busy", 3), new Frame(true, "read_ref", 3, DataValue.NullValue.INSTANCE, "read_ref", 3, reference, ""),
            speaker("status", "ok", "idle"), new Frame(false, "play", 0, reference, "accepted", 0, DataValue.NullValue.INSTANCE, "preparing"),
            speaker("status", "ok", "preparing"), speaker("status", "ok", "transferring"),
            speaker("status", "ok", "dispatched"), library("read_close", 3, "acknowledged", 3)));
        assertEquals(text("read_close"), play.result().field("stage"));
        assertEquals(text("acknowledged"), record(play.result().field("reply")).field("code"));
        assertEquals(text("dispatched"), record(record(play.result().field("speaker_reply")).field("state")).field("playback"));

        play.installStop();
        play.run(List.of(speaker("status", "ok", "transferring"), speaker("stop", "accepted", "idle")));
        assertEquals(text("stop"), play.result().field("stage"));
        assertEquals(text("accepted"), record(play.result().field("speaker_reply")).field("code"));
    }

    @Test void failedDeliveryDoesNotRetryPlayOrCloseTheLibraryReadHead() throws Exception {
        var reference = new DataValue.RecordValue(Map.of("sequence", number(3), "device", text("book-device")));
        var play = new MediaBus(true);
        play.run(List.of(
            library("status", 0, "idle", 0), library("seek", 0, zeroPosition(), "accepted", 1),
            library("result", 1, "positioned", 1), library("ack", 1, "acknowledged", 1),
            library("select", 1, number(0), "accepted", 2), library("result", 2, "selected", 2),
            library("ack", 2, "acknowledged", 2), library("read_begin", 2, text("song"), "accepted", 3),
            new Frame(true, "read_ref", 3, DataValue.NullValue.INSTANCE, "read_ref", 3, reference, ""),
            speaker("status", "ok", "idle"), new Frame(false, "play", 0, reference, "accepted", 0, DataValue.NullValue.INSTANCE, "preparing"),
            speaker("status", "closed", "preparing")));
        assertEquals(text("status"), play.result().field("stage"));
        assertEquals(text("closed"), record(play.result().field("speaker_reply")).field("code"));
    }

    private record Frame(boolean library, String operation, long sequence, DataValue argument,
                         String code, long nextSequence, DataValue response, String playback) {
        DataValue.RecordValue respond(DataValue.RecordValue message) {
            var fields = new java.util.HashMap<String, DataValue>();
            fields.put("target", message.field("target"));
            fields.put("request", message.field("request"));
            fields.put("code", text(code));
            if (library) {
                var command = LibraryDriveCommand.decode(message);
                assertEquals(operation, command.operation());
                assertEquals(sequence, command.sequence());
                assertEquals(argument, command.data());
                fields.put("kind", text("library_reply"));
                fields.put("sequence", number(nextSequence));
                fields.put("value", response);
            } else {
                assertEquals(text("speaker_media_command"), message.field("kind"));
                assertEquals(text(operation), message.field("operation"));
                var args = record(message.field("arguments"));
                if (operation.equals("status")) assertTrue(args.fields().isEmpty());
                else {
                    var expected = new java.util.HashMap<>(speakerToken());
                    if (operation.equals("play")) expected.put("reference", argument);
                    assertEquals(new DataValue.RecordValue(expected), args);
                }
                var state = new java.util.HashMap<>(speakerToken());
                state.put("playback", text(playback));
                fields.put("kind", text("speaker_media_reply"));
                fields.put("state", new DataValue.RecordValue(state));
            }
            return new DataValue.RecordValue(fields);
        }
    }

    private static Map<String, DataValue> speakerToken() {
        return Map.of("device", text("test-speaker"), "session", text("test-session"), "revision", number(1));
    }

    private static Frame library(String operation, long sequence, String code, long next) {
        return library(operation, sequence, DataValue.NullValue.INSTANCE, code, next);
    }

    private static Frame library(String operation, long sequence, DataValue argument, String code, long next) {
        return new Frame(true, operation, sequence, argument, code, next, DataValue.NullValue.INSTANCE, "");
    }

    private static Frame speaker(String operation, String code, String playback) {
        return new Frame(false, operation, 0, DataValue.NullValue.INSTANCE, code, 0, DataValue.NullValue.INSTANCE, playback);
    }

    private record Link(ComputerBlockHost a, String aPin, ComputerBlockHost b, String bPin) {
        void exchange(ComputerHostLimits limits) { a.exchange(aPin, limits, true, b, bPin, limits, true); }
    }

    // All six real documents and the shared serial medium execute here. The peripheral is a
    // scripted endpoint: this test does not exercise a real Library, sound, or player menus.
    private static final class MediaBus {
        private final BiocomputingConfig config = new BiocomputingConfig(new ModConfigSpec.Builder());
        private final ComputerHostLimits limits = hardware();
        private final SerialDevice serial = new SerialDevice(limits::serial, () -> true, () -> config.serialCodec(false), () -> {});
        private final SerialDevice peripheral = new SerialDevice(limits::serial, () -> true, () -> config.serialCodec(false), () -> {});
        private final ComputerBlockHost wire = new ComputerBlockHost(() -> {}, pin -> {}, serial);
        private final ComputerBlockHost coordinator = new ComputerBlockHost(() -> {});
        private final ComputerBlockHost library = new ComputerBlockHost(() -> {});
        private final ComputerBlockHost delivery = new ComputerBlockHost(() -> {});
        private final ComputerBlockHost output = new ComputerBlockHost(() -> {});
        private final ComputerBlockHost frame = new ComputerBlockHost(() -> {});
        private final HashSet<DataValue> requests = new HashSet<>();
        private final SerialNetwork channel = new SerialNetwork();
        private final List<ComputerBlockHost> upstream = List.of(coordinator, library, delivery, output, frame);
        private final List<Link> links = List.of(new Link(coordinator, "x0", library, "x0"), new Link(coordinator, "x1", delivery, "x0"),
            new Link(delivery, "x1", output, "x0"), new Link(output, "x1", frame, "x0"),
            new Link(library, "x1", wire, "x0"), new Link(frame, "x1", wire, "x0"));

        MediaBus(boolean play) throws Exception {
            serial.configure("s0", Direction.EAST, true);
            peripheral.configure("s0", Direction.WEST, true);
            install(coordinator, coordinator(play));
            install(library, libraryStage());
            install(delivery, deliveryStage());
            install(output, outputStage());
            install(frame, frameStage());
            install(wire, wireStage());
        }

        private void install(ComputerBlockHost host, ProgramDocument document) {
            host.installProgram(PreparedProgram.prepare(document, limits), ComputerRuntime.MemoryPolicy.RESET, limits, true);
            host.setPaused(false, limits, true);
        }

        void installStop() throws Exception {
            assertEquals(FlowRuntime.Status.COMPLETE, coordinator.flowSnapshot().orElseThrow().state().status());
            // The local pair seam retains consumed receipts. Follow the existing explicit
            // restart path for this completed A only; do not touch the workers or device.
            coordinator.reset(limits, true);
            install(coordinator, coordinator(false));
        }

        void run(List<Frame> expected) {
            int index = 0;
            var cable = new BlockPos(1, 0, 0);
            var devices = Map.of(BlockPos.ZERO, serial, new BlockPos(2, 0, 0), peripheral);
            var world = new CableDiscovery.World() {
                public boolean loaded(BlockPos pos) { return true; }
                public CableDiscovery.Cell cell(BlockPos pos) {
                    return new CableDiscovery.Cell(pos.equals(cable) ? CableDiscovery.Kind.CABLE
                        : devices.containsKey(pos) ? CableDiscovery.Kind.ENDPOINT : CableDiscovery.Kind.EMPTY, null);
                }
            };
            for (int tick = 0; tick < 10_000; tick++) {
                // Sample pending writers before any CPU advances, as in a Cluster round.
                var levels = Map.of(library, sendLevel(coordinator, "x0"), delivery, sendLevel(coordinator, "x1"),
                    output, sendLevel(delivery, "x1"), frame, sendLevel(output, "x1"),
                    wire, Math.max(sendLevel(library, "x1"), sendLevel(frame, "x1")));
                for (var host : upstream) host.tick(limits, true, Map.of("x0", levels.getOrDefault(host, 0)));
                wire.tick(limits, true, Map.of("x0", levels.get(wire)));
                for (var link : links) link.exchange(limits);
                channel.tick(world, devices, new CommunicationNetworkLimits(64, 4), config.serialMedium(false));
                if (peripheral.status("s0").receive().frames() > 0) {
                    var pending = peripheral.prepareReceive("s0");
                    var message = record(pending.result().field("value"));
                    assertTrue(index < expected.size(), () -> "Extra frame: " + message);
                    assertTrue(requests.add(message.field("request")), "Each request must use a fresh correlation");
                    assertTrue(pending.replying(expected.get(index++).respond(message)).publish());
                }
                var state = coordinator.flowSnapshot().orElseThrow().state();
                if (state.status() == FlowRuntime.Status.COMPLETE) {
                    assertEquals(expected.size(), index, () -> "Coordinator result: " + state.locals());
                    return;
                }
                assertTrue(state.status() != FlowRuntime.Status.FAILED, () -> "Coordinator failed: " + state.diagnostic());
            }
            throw new AssertionError("Timed out after " + index + " frames: " +
                java.util.stream.Stream.concat(upstream.stream(), java.util.stream.Stream.of(wire))
                    .map(host -> host.flowSnapshot().orElseThrow().state())
                    .map(state -> state.status() + " " + state.diagnostic()).toList());
        }

        DataValue.RecordValue result() { return coordinator.flowSnapshot().orElseThrow().state().locals(); }
    }

    private static DataValue.RecordValue record(DataValue value) { return assertInstanceOf(DataValue.RecordValue.class, value); }
    private static DataValue.IntegerValue number(long value) { return new DataValue.IntegerValue(value); }

    @Test void libraryStagePreservesSequenceAndExactReplyAcrossXbusRendezvous() throws Exception {
        var limits = hardware();
        var worker = new ComputerBlockHost(() -> {});
        worker.installProgram(PreparedProgram.prepare(libraryStage(), limits), ComputerRuntime.MemoryPolicy.RESET, limits, true);
        worker.setPaused(false, limits, true);

        var first = transact(worker, limits, "status", DataValue.NullValue.INSTANCE, true, 0,
            new DataValue.RecordValue(Map.of("sequence", new DataValue.IntegerValue(4), "code", text("idle"))));
        var missing = transact(worker, limits, "read_begin", text("song"), false, 4,
            new DataValue.RecordValue(Map.of("code", text("disconnected"))));
        var reference = new DataValue.RecordValue(Map.of("sequence", new DataValue.IntegerValue(4), "device", text("media-library")));
        var third = transact(worker, limits, "read_ref", DataValue.NullValue.INSTANCE, false, 4,
            new DataValue.RecordValue(Map.of("sequence", new DataValue.IntegerValue(4), "code", text("read_ref"), "value", reference)));
        assertNotEquals(first, missing);
        assertNotEquals(missing, third);
    }

    // Isolate sequence handling from the other media programs.
    private static DataValue transact(ComputerBlockHost worker, ComputerHostLimits limits, String operation, DataValue data,
                                      boolean poll, long sequence, DataValue.RecordValue deviceReply) {
        var packet = LibraryDriveCommand.decode(viaWire(worker, limits, job(operation, data, poll), "library_reply", deviceReply));
        assertEquals(operation, packet.operation());
        assertEquals(sequence, packet.sequence());
        assertEquals(data, packet.data());
        return packet.request();
    }

    @Test void outputStageUsesOneFreshStatusPerMutationAndPreservesTheMediaReference() throws Exception {
        var limits = hardware();
        var worker = new ComputerBlockHost(() -> {});
        worker.installProgram(PreparedProgram.prepare(outputStage(), limits), ComputerRuntime.MemoryPolicy.RESET, limits, true);
        worker.setPaused(false, limits, true);
        var framer = new ComputerBlockHost(() -> {});
        framer.installProgram(PreparedProgram.prepare(frameStage(), limits), ComputerRuntime.MemoryPolicy.RESET, limits, true);
        framer.setPaused(false, limits, true);
        var none = DataValue.NullValue.INSTANCE;
        var empty = new DataValue.RecordValue(Map.of());
        var reference = new DataValue.RecordValue(Map.of("device", text("book-device"), "sequence", new DataValue.IntegerValue(8)));
        var token = Map.<String, DataValue>of("device", text("speaker-1"), "session", text("session-8"), "revision", new DataValue.IntegerValue(15));
        var status = new DataValue.RecordValue(Map.of("code", text("ok"), "state", new DataValue.RecordValue(token)));
        var denied = new DataValue.RecordValue(Map.of("code", text("closed"), "state", new DataValue.RecordValue(token)));
        var accepted = new DataValue.RecordValue(Map.of("code", text("accepted"), "state", new DataValue.RecordValue(token)));

        assertStatusRequired(worker, limits, reference);
        var query = viaWire(worker, limits, job("status", none, false), "speaker_media_reply", status, Optional.of(framer));
        assertEquals(text("status"), query.field("operation"));
        assertEquals(empty, query.field("arguments"));
        var play = viaWire(worker, limits, job("play", reference, false), "speaker_media_reply", accepted, Optional.of(framer));
        assertEquals(text("speaker_media_command"), play.field("kind"));
        assertEquals(new DataValue.IntegerValue(1), play.field("version"));
        assertEquals(text("play"), play.field("operation"));
        var args = new java.util.HashMap<>(token);
        args.put("reference", reference);
        assertEquals(new DataValue.RecordValue(args), play.field("arguments"));
        assertNotEquals(query.field("request"), play.field("request"));
        assertStatusRequired(worker, limits, reference);

        viaWire(worker, limits, job("status", none, false), "speaker_media_reply", denied, Optional.of(framer));
        assertStatusRequired(worker, limits, reference);
        viaWire(worker, limits, job("status", none, false), "speaker_media_reply", status, Optional.of(framer));
        var stop = viaWire(worker, limits, job("stop", none, false), "speaker_media_reply", accepted, Optional.of(framer));
        assertEquals(text("stop"), stop.field("operation"));
        assertEquals(new DataValue.RecordValue(token), stop.field("arguments"), "Stop takes no Library reference");
    }

    private static void assertStatusRequired(ComputerBlockHost worker, ComputerHostLimits limits, DataValue reference) {
        var caller = caller(limits, job("play", reference, false));
        meet(limits, caller, "x0", worker, "x0");
        // No wire peer is present. A stale mutation would block on x1 rather than returning.
        meet(limits, worker, "x0", caller, "x0");
        assertEquals(wrap(new DataValue.RecordValue(Map.of("code", text("status_required")))), answer(caller, limits));
    }

    private static DataValue.RecordValue job(String operation, DataValue data, boolean poll) {
        return new DataValue.RecordValue(Map.of("o", text(operation), "d", data, "p", new DataValue.BooleanValue(poll)));
    }

    private static DataValue.RecordValue wrap(DataValue.RecordValue reply) { return new DataValue.RecordValue(Map.of("r", reply)); }

    private static ComputerBlockHost caller(ComputerHostLimits limits, DataValue.RecordValue job) {
        return peer(limits, "job", JOB, job, List.of(
            new FlowProgram.Write("send", "x0", "job"), new FlowProgram.Read("reply", "x0", "answer", REPLY)));
    }

    private static DataValue answer(ComputerBlockHost caller, ComputerHostLimits limits) {
        caller.tick(limits, true);
        return caller.flowSnapshot().orElseThrow().state().locals().field("answer");
    }

    private static DataValue.RecordValue viaWire(ComputerBlockHost worker, ComputerHostLimits limits, DataValue.RecordValue job,
                                                 String responseKind, DataValue.RecordValue deviceReply) {
        return viaWire(worker, limits, job, responseKind, deviceReply, Optional.empty());
    }

    private static DataValue.RecordValue viaWire(ComputerBlockHost worker, ComputerHostLimits limits, DataValue.RecordValue job,
                                                 String responseKind, DataValue.RecordValue deviceReply, Optional<ComputerBlockHost> framer) {
        var caller = caller(limits, job);
        var reply = wrap(deviceReply);
        var wire = peer(limits, "outcome", REPLY, reply, List.of(
            new FlowProgram.Read("frame", "x0", "call", CALL), new FlowProgram.Write("send", "x0", "outcome")));
        meet(limits, caller, "x0", worker, "x0");
        if (framer.isPresent()) meet(limits, worker, "x1", framer.orElseThrow(), "x0");
        var edge = framer.orElse(worker);
        meet(limits, edge, "x1", wire, "x0");
        var framed = assertInstanceOf(DataValue.RecordValue.class, wire.flowSnapshot().orElseThrow().state().locals().field("call"));
        assertEquals(text(responseKind), framed.field("e"));
        meet(limits, wire, "x0", edge, "x1");
        if (framer.isPresent()) meet(limits, edge, "x0", worker, "x1");
        meet(limits, worker, "x0", caller, "x0");
        assertEquals(reply, answer(caller, limits));
        return assertInstanceOf(DataValue.RecordValue.class, framed.field("p"));
    }

    private static ComputerBlockHost peer(ComputerHostLimits limits, String input, DataType type, DataValue value,
                                          List<FlowProgram.Step> steps) {
        var peer = new ComputerBlockHost(() -> {});
        peer.installFlow(new FlowProgram(Map.of(input, type), steps).withAssembly(),
            new DataValue.RecordValue(Map.of(input, value)), limits, true);
        peer.setPaused(false, limits, true);
        return peer;
    }

    private static void meet(ComputerHostLimits limits, ComputerBlockHost from, String output, ComputerBlockHost to, String input) {
        for (int i = 0; i < 24; i++) {
            int level = sendLevel(from, output);
            from.tick(limits, true);
            to.tick(limits, true, Map.of(input, level));
            if (from.exchange(output, limits, true, to, input, limits, true) == XBusExchange.Status.EXCHANGED) return;
        }
        throw new AssertionError("No XBus rendezvous: " + from.flowSnapshot() + " -> " + to.flowSnapshot());
    }

    private static int sendLevel(ComputerBlockHost host, String pin) {
        var snapshot = host.flowSnapshot().orElseThrow();
        return snapshot.state().status() == FlowRuntime.Status.WAITING_WRITE
            && snapshot.pinClaims().entries().stream().anyMatch(claim -> claim.pin().equals(pin)) ? 15 : 0;
    }

    private static ComputerHostLimits hardware() {
        return new BiocomputingConfig(new ModConfigSpec.Builder()).select(new ResidentHardwareIdentity(
            ResourceLocation.parse("minecraft:villager"), ResourceLocation.parse("minecraft:cleric"), 5), false).limits();
    }

    private static DataValue.TextValue text(String value) { return new DataValue.TextValue(value); }

    static ProgramDocument coordinator(boolean play) throws Exception {
        var types = Map.<String, DataType>of("rows", ROWS,
            "nothing", ANY, "track", new DataType.RecordType(Map.of("x", INTEGER, "y", INTEGER, "z", INTEGER)), "slot", INTEGER, "entry", TEXT,
            "one", INTEGER, "reference_index", INTEGER);
        var rows = new DataValue.ListValue(List.of(
            step("status", false, 0, "idle", false, false),
            step("seek", false, 1, "accepted", false, false), step("result", false, 0, "positioned", false, true),
            step("ack", false, 0, "acknowledged", false, false),
            step("select", false, 2, "accepted", false, false), step("result", false, 0, "selected", false, true),
            step("ack", false, 0, "acknowledged", false, false),
            step("read_begin", false, 3, "accepted", false, false), step("read_ref", false, 0, "read_ref", true, true),
            step("status", true, 0, "ok", false, false), step("play", true, 4, "accepted", false, false),
            step("status", true, 0, "ok", false, true), step("read_close", false, 0, "acknowledged", false, false)));
        var stop = new DataValue.ListValue(List.of(step("status", true, 0, "ok", false, false),
            step("stop", true, 0, "accepted", false, false)));
        var inputs = new DataValue.RecordValue(Map.of("rows", play ? rows : stop,
            "nothing", DataValue.NullValue.INSTANCE, "track", zeroPosition(), "slot", new DataValue.IntegerValue(0), "entry", text("song"),
            "one", new DataValue.IntegerValue(1), "reference_index", new DataValue.IntegerValue(4)));
        return stage("speaker_media_default_coordinator", types, inputs, Map.of("stage", TEXT, "reply", ANY, "speaker_reply", ANY));
    }

    private static DataValue.RecordValue step(String operation, boolean speaker, int argument, String expected, boolean capture, boolean poll) {
        return new DataValue.RecordValue(Map.of("o", text(operation), "s", new DataValue.BooleanValue(speaker),
            "a", new DataValue.IntegerValue(argument), "e", text(expected), "c", new DataValue.BooleanValue(capture), "z", new DataValue.BooleanValue(poll)));
    }

    static ProgramDocument libraryStage() throws Exception {
        var position = new DataType.RecordType(Map.of("x", INTEGER, "y", INTEGER, "z", INTEGER));
        return stage("speaker_media_library_stage", Map.of("library", position, "result_op", TEXT, "reference_op", TEXT, "busy_code", TEXT,
                "poll_limit", INTEGER, "poll_ticks", INTEGER, "one", INTEGER, "zero", INTEGER),
            new DataValue.RecordValue(Map.of("library", zeroPosition(), "result_op", text("result"), "reference_op", text("read_ref"),
                "busy_code", text("busy"), "poll_limit", new DataValue.IntegerValue(80), "poll_ticks", new DataValue.IntegerValue(4),
                "one", new DataValue.IntegerValue(1), "zero", new DataValue.IntegerValue(0))));
    }

    static ProgramDocument outputStage() throws Exception {
        return stage("speaker_media_output_stage", Map.of("status_op", TEXT, "poll_ticks", INTEGER, "zero", INTEGER),
            new DataValue.RecordValue(Map.of("status_op", text("status"), "poll_ticks", number(4), "zero", number(0))));
    }

    static ProgramDocument frameStage() throws Exception {
        var position = new DataType.RecordType(Map.of("x", INTEGER, "y", INTEGER, "z", INTEGER));
        return stage("speaker_media_frame_stage",
            Map.of("speaker", position, "status_op", TEXT, "ok_code", TEXT, "poll_ticks", INTEGER, "zero", INTEGER),
            new DataValue.RecordValue(Map.of("speaker", zeroPosition(), "status_op", text("status"), "ok_code", text("ok"),
                "poll_ticks", number(4), "zero", number(0))));
    }

    static ProgramDocument deliveryStage() throws Exception {
        return stage("speaker_media_delivery_stage",
            Map.of("status_op", TEXT, "ok_code", TEXT, "preparing", TEXT, "transferring", TEXT,
                "poll_limit", INTEGER, "poll_ticks", INTEGER, "one", INTEGER, "zero", INTEGER),
            new DataValue.RecordValue(Map.of("status_op", text("status"), "ok_code", text("ok"),
                "preparing", text("preparing"), "transferring", text("transferring"),
                "poll_limit", new DataValue.IntegerValue(80), "poll_ticks", new DataValue.IntegerValue(4),
                "one", new DataValue.IntegerValue(1), "zero", new DataValue.IntegerValue(0))));
    }

    static ProgramDocument wireStage() throws Exception {
        return stage("speaker_media_wire", Map.of("poll_ticks", INTEGER, "poll_limit", INTEGER),
            new DataValue.RecordValue(Map.of("poll_ticks", number(4), "poll_limit", number(128))));
    }

    private static DataValue.RecordValue zeroPosition() {
        var zero = new DataValue.IntegerValue(0);
        return new DataValue.RecordValue(Map.of("x", zero, "y", zero, "z", zero));
    }

    private static ProgramDocument stage(String source, Map<String, DataType> inputs, DataValue.RecordValue initial) throws Exception {
        return stage(source, inputs, initial, Map.of());
    }

    private static ProgramDocument stage(String source, Map<String, DataType> inputs, DataValue.RecordValue initial,
                                         Map<String, DataType> outputs) throws Exception {
        var results = outputs.keySet().stream().collect(java.util.stream.Collectors.toMap(name -> name, name -> name));
        var bindings = new FlowCallBindings(inputs.keySet().stream().collect(java.util.stream.Collectors.toMap(name -> name, name -> name)), results);
        var key = new ModuleDefinition.Key(source, 1);
        var assembly = Files.readString(EXAMPLES.resolve(source + ".asm"));
        var program = new FlowProgram(inputs, List.of(
            new FlowProgram.Invoke("run", key, bindings),
            new FlowProgram.Procedure("media_worker", key, inputs, outputs, results, FlowBlockRules.NONE,
                Optional.of(new AssemblySource(assembly))),
            new FlowProgram.EndProcedure("end"))).withAssembly();
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(FlowDocumentBody.fromProgram(program, initial)));
        if (!outputs.isEmpty()) workspace.setTerminal(Optional.of(NativeLibraryResultLayouts.fields("stage", "reply", "speaker_reply")));
        return workspace.snapshot();
    }
}
