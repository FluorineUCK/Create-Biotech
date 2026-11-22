package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.device.terminal.MotionPanelTerminalFixture;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CableDiscovery;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationNetworkLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.junit.jupiter.api.Test;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;
import static org.junit.jupiter.api.Assertions.*;

/** Runs the public programs and the actual serial bridge against deterministic endpoint replies. */
class MotionPanelProgramDocumentsTest {
    private static final Path EXAMPLES = Path.of("examples/biocomputing");
    private static final String ID = "motion_panel_a";
    private static final DataType POSITION = new DataType.RecordType(Map.of("x", INTEGER, "y", INTEGER, "z", INTEGER));
    private static final DataValue.RecordValue TERMINAL = position(10), TIMER = position(11), MOTION = position(12);
    private static final DataValue.RecordValue EMPTY = new DataValue.RecordValue(Map.of());
    private static final DataValue NULL = DataValue.NullValue.INSTANCE;
    private static final DataValue.TextValue ACTION = text("a90ebb3c-e1e4-4182-b590-05716e517ca5");
    private enum Scenario { READY, UNPOWERED, OLD_VIEW }

    @Test void terminalDistanceSelectsOneMotionAndShowsTheResultWithoutInventingArrival() throws Exception {
        var fixture = new Fixture(Scenario.READY);
        var result = fixture.run();
        assertEquals(List.of("terminal/status", "terminal/publish", "terminal/status", "terminal/take_event",
            "gear/motion_status", "timer/status", "timer/set", "gear/motion_start", "gear/motion_status", "gear/motion_status"),
            fixture.calls, () -> "status=" + result.field("status") + "; reply="
                + ((DataValue.RecordValue) result.field("reply")).fields() + "; line=" + fixture.sender.status("s0")
                + "; relay=" + fixture.relay.flowSnapshot().orElseThrow().state().assembly().orElseThrow().registers().values().stream()
                    .filter(DataValue.RecordValue.class::isInstance).map(DataValue.RecordValue.class::cast)
                    .filter(value -> value.fields().containsKey("status")).map(DataValue.RecordValue::fields).toList());
        assertEquals(List.of(0, 15, 0), fixture.indicatorTransitions);
        assertEquals(text("sequence_completed"), result.field("status"));
        assertEquals(number(3), result.field("distance"));
        assertEquals(number(16), result.field("interval"));
        assertEquals(new DataValue.BooleanValue(true), result.field("interval_applied"));
        assertEquals(ACTION, result.field("action"));
        assertEquals(text("not_measured"), result.field("arrival"));
        assertEquals(0, fixture.panel.queued());
    }

    @Test void noDriveLeavesTheClockAndTheRedstoneLineUnchanged() throws Exception {
        var fixture = new Fixture(Scenario.UNPOWERED);
        var result = fixture.run();
        assertEquals(List.of("terminal/status", "terminal/publish", "terminal/status", "terminal/take_event",
            "gear/motion_status"), fixture.calls);
        assertEquals(List.of(0), fixture.indicatorTransitions);
        assertEquals(text("no_drive"), result.field("status"));
        assertEquals(new DataValue.BooleanValue(false), result.field("interval_applied"));
        assertEquals(NULL, result.field("action"));
    }

    @Test void aClickFromThePreviousLayoutCannotTriggerAnAction() throws Exception {
        var fixture = new Fixture(Scenario.OLD_VIEW);
        var result = fixture.run();
        assertEquals(List.of("terminal/status", "terminal/publish", "terminal/status", "terminal/take_event"), fixture.calls);
        assertEquals(List.of(0), fixture.indicatorTransitions);
        assertEquals(text("stale_choice"), result.field("status"));
        assertEquals(0, fixture.panel.queued());
    }

    private static ProgramDocument build() throws Exception {
        Map<String, DataType> inputs = Map.of("terminal_pos", POSITION, "timer_pos", POSITION, "gear_pos", POSITION, "panel", ANY);
        Map<String, DataType> outputs = Map.of("status", TEXT, "distance", INTEGER, "interval", INTEGER, "interval_applied", BOOLEAN,
            "action", ANY, "arrival", TEXT, "reply", ANY);
        var key = new ModuleDefinition.Key(ID, 1);
        var in = identityBindings(inputs);
        var out = identityBindings(outputs);
        var program = new FlowProgram(inputs, List.of(
            new FlowProgram.Invoke("control", key, new FlowCallBindings(in, out)),
            new FlowProgram.Procedure("controller", key, inputs, outputs, out, FlowBlockRules.NONE,
                Optional.of(new AssemblySource(Files.readString(EXAMPLES.resolve(ID + ".asm"))))),
            new FlowProgram.EndProcedure("end"))).withAssembly();
        var workspace = new ProgramWorkspace();
        var initial = record(Map.of("terminal_pos", TERMINAL, "timer_pos", TIMER, "gear_pos", MOTION,
            "panel", TerminalCodec.encode(panel())));
        workspace.setFlow(Optional.of(FlowDocumentBody.fromProgram(program, initial)));
        workspace.setTerminal(Optional.of(NativeLibraryResultLayouts.fields("status", "distance", "interval", "interval_applied",
            "action", "arrival", "reply")));
        return workspace.snapshot();
    }

    private static TerminalDefinition panel() {
        // One input widget fits one default frame; no implicit layout fragmentation.
        return new TerminalDefinition("distance", Map.of("distance", new TerminalDefinition.Input(literal(text("1")),
            literal(new DataValue.BooleanValue(true)), "lower", "distance", literal(EMPTY))));
    }

    private static Map<String, String> identityBindings(Map<String, DataType> types) {
        var bindings = new LinkedHashMap<String, String>();
        types.keySet().forEach(name -> bindings.put(name, name));
        return bindings;
    }

    private static final class Fixture {
        private final BiocomputingConfig config = new BiocomputingConfig(new ModConfigSpec.Builder());
        private final ComputerHostLimits relayLimits = config.select(new ResidentHardwareIdentity(
            ResourceLocation.parse("minecraft:villager"), ResourceLocation.parse("minecraft:cleric"), 5), false).limits();
        private final ComputerHostLimits limits = new ComputerHostLimits(
            new ResourceLimits(1280, relayLimits.hardware().memoryUnits(), relayLimits.hardware().workPerSlice()),
            relayLimits.archive(), relayLimits.mailbox(), relayLimits.pins(), relayLimits.serial(), relayLimits.taskSlots(),
            relayLimits.isa1a(), relayLimits.isa1b());
        private final SerialDevice sender = new SerialDevice(relayLimits::serial, () -> true, () -> config.serialCodec(false), () -> {});
        private final SerialDevice endpoints = new SerialDevice(relayLimits::serial, () -> true, () -> config.serialCodec(false), () -> {});
        private final ComputerBlockHost controller = new ComputerBlockHost(() -> {});
        private final ComputerBlockHost relay = new ComputerBlockHost(() -> {}, pin -> {}, sender);
        private final SerialNetwork channel = new SerialNetwork();
        private final MotionPanelTerminalFixture panel = new MotionPanelTerminalFixture(config.terminalUiLimits(false), TERMINAL);
        private final Scenario scenario;
        private final List<String> calls = new ArrayList<>();
        private final List<Integer> indicatorTransitions = new ArrayList<>();
        private final Set<DataValue> requests = new HashSet<>();

        Fixture(Scenario scenario) throws Exception {
            this.scenario = scenario;
            if (scenario == Scenario.OLD_VIEW) {
                panel.seedPriorView(panel());
                panel.submit("3");
            }
            sender.configure("s0", Direction.EAST, true);
            endpoints.configure("s0", Direction.WEST, true);
            var document = build();
            var packaged = EXAMPLES.resolve(ID + ".bcp");
            if ("1".equals(System.getenv("GENERATE_MOTION_PANEL"))) {
                var encoded = ProgramDocumentWire.encode(document, limits.archive());
                Files.writeString(packaged, Base64.getEncoder().encodeToString(encoded) + "\n");
            }
            assertEquals(document, read(ID));
            var linked = PreparedProgram.prepare(document, limits);
            var units = document.flow().orElseThrow().program(document.archive()).usage(limits.hardware()).programUnits();
            System.out.println("Motion panel controller: " + units + " code units");
            controller.installProgram(linked, ComputerRuntime.MemoryPolicy.RESET, limits, true);
            relay.installProgram(PreparedProgram.prepare(read("serial_exchange"), relayLimits),
                ComputerRuntime.MemoryPolicy.RESET, relayLimits, true);
            relay.setPaused(false, relayLimits, true);
            controller.setPaused(false, limits, true);
        }

        private ProgramDocument read(String id) throws Exception {
            return ProgramDocumentWire.decode(Base64.getDecoder().decode(Files.readString(EXAMPLES.resolve(id + ".bcp")).strip()),
                limits.archive());
        }

        DataValue.RecordValue run() {
            var cable = new BlockPos(1, 0, 0);
            var devices = Map.of(BlockPos.ZERO, sender, new BlockPos(2, 0, 0), endpoints);
            var world = new CableDiscovery.World() {
                public boolean loaded(BlockPos pos) { return true; }
                public CableDiscovery.Cell cell(BlockPos pos) {
                    return new CableDiscovery.Cell(pos.equals(cable) ? CableDiscovery.Kind.CABLE
                        : devices.containsKey(pos) ? CableDiscovery.Kind.ENDPOINT : CableDiscovery.Kind.EMPTY, null);
                }
            };
            for (int tick = 0; tick < 16000; tick++) {
                controller.tick(limits, true);
                relay.tick(relayLimits, true, Map.of("x0", pendingSend(controller) ? 15 : 0));
                controller.exchange("x0", limits, true, relay, "x0", relayLimits, true);
                channel.tick(world, devices, new CommunicationNetworkLimits(64, 4), config.serialMedium(false));
                if (endpoints.status("s0").receive().frames() > 0) {
                    var pending = endpoints.prepareReceive("s0");
                    var command = assertInstanceOf(DataValue.RecordValue.class, pending.result().field("value"));
                    assertTrue(pending.replying(respond(command)).publish());
                }
                var state = controller.flowSnapshot().orElseThrow().state();
                int level = state.signals().outputs().getOrDefault("p0", 0);
                if (indicatorTransitions.isEmpty() || indicatorTransitions.getLast() != level) indicatorTransitions.add(level);
                assertNotEquals(FlowRuntime.Status.FAILED, state.status(), () -> state.diagnostic().toString());
                if (state.status() == FlowRuntime.Status.COMPLETE) return state.locals();
            }
            throw new AssertionError("Controller failed to finish after " + calls);
        }

        private boolean pendingSend(ComputerBlockHost computer) {
            return computer.flowSnapshot().orElseThrow().state().status() == FlowRuntime.Status.WAITING_WRITE;
        }

        private DataValue.RecordValue respond(DataValue.RecordValue command) {
            assertTrue(requests.add(command.field("request")), "No duplicate correlation");
            var target = command.field("target");
            String device = target.equals(TERMINAL) ? "terminal" : target.equals(TIMER) ? "timer" : "gear";
            if (device.equals("gear")) assertEquals(MOTION, target);
            String op = assertInstanceOf(DataValue.TextValue.class, command.field("operation")).value();
            String call = device + "/" + op;
            calls.add(call);
            var args = assertInstanceOf(DataValue.RecordValue.class, command.field("arguments"));
            if (device.equals("terminal")) {
                var reply = panel.reply(command);
                if (op.equals("publish")) {
                    assertEquals(text("published"), reply.field("code"), () -> reply.fields().toString());
                    if (scenario != Scenario.OLD_VIEW) panel.submit("3");
                }
                return reply;
            }
            String code = "ok";
            DataValue state = NULL;
            switch (call) {
                case "gear/motion_status" -> {
                    assertEquals(EMPTY, args);
                    long polls = calls.stream().filter(call::equals).count();
                    state = record(Map.of("availability", text(scenario == Scenario.UNPOWERED ? "no_drive" : polls == 2 ? "busy" : "ok"),
                        "token", text("gear-token"),
                        "phase", text(polls == 1 ? "idle" : polls == 2 ? "running" : "sequence_completed"),
                        "action", polls == 1 ? NULL : ACTION, "arrival", text("not_measured")));
                }
                case "timer/status" -> {
                    assertEquals(text("interval"), args.field("field"));
                    state = record(Map.of("token", text("timer-token")));
                }
                case "timer/set" -> {
                    assertEquals(record(Map.of("token", text("timer-token"), "field", text("interval"), "value", number(16))), args);
                    code = "applied";
                }
                case "gear/motion_start" -> {
                    assertEquals(record(Map.of("token", text("gear-token"), "mode", text("distance"),
                        "amount", number(3), "speed", number(1))), args);
                    code = "accepted";
                    state = record(Map.of("action", ACTION, "arrival", text("not_measured")));
                }
                default -> fail("Unexpected request " + command);
            }
            return record(Map.of("kind", text("component_settings_reply"), "version", number(1), "target", target,
                "request", command.field("request"), "code", text(code), "state", state));
        }
    }

    private static TerminalBinding.Literal literal(DataValue value) { return new TerminalBinding.Literal(value); }
    private static DataValue.RecordValue record(Map<String, DataValue> values) { return new DataValue.RecordValue(values); }
    private static DataValue.IntegerValue number(long value) { return new DataValue.IntegerValue(value); }
    private static DataValue.TextValue text(String value) { return new DataValue.TextValue(value); }
    private static DataValue.RecordValue position(int x) { return record(Map.of("x", number(x), "y", number(0), "z", number(0))); }
}
