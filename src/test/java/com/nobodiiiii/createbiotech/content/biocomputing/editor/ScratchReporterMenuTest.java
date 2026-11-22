package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerFlowScreenTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MailboxLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.extension.ScratchExtension;
import com.nobodiiiii.createbiotech.content.biocomputing.extension.ScratchExtensions;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ScratchReporterMenuTest {
    private final ComputerProgramScreenTest environment = new ComputerProgramScreenTest();
    @BeforeAll static void bootstrapClient() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void client() throws Exception { environment.client(); }
    @AfterEach void restoreClient() throws Exception { environment.restore(); }

    @Test void aPublicTypedMenuWorksInAnAttachedReporterAndAnUnattachedPredicate() throws Exception {
        withCommonSetup(ScratchReporterMenuTest::useMenus);
    }

    @Test void aTypedPresetInsideARecordStaysLocalUntilApply() throws Exception {
        withCommonSetup(() -> {
            String addon = "scratch_menu_probe:constructor";
            var level = command("level", ScratchExtension.Shape.REPORTER, DataType.Scalar.INTEGER, number(2), number(5));
            var extension = new ScratchExtension(ResourceLocation.parse(addon), 1, "fixture.extension", "fixture.help",
                Map.of(), List.of(level));
            ScratchExtensions.register(extension);
            var binding = new ScratchExtensions.Binding(extension, level);
            var draft = new FlowDraft(FlowDocumentBody.emptyAssembly());
            draft.setExtension(addon, 1L);
            FlowProcedureTools.define(draft, binding.template("fixture_level"), false);
            var expression = graph(binding);
            var nodes = new java.util.LinkedHashMap<>(expression.nodes());
            nodes.put("record", new ComputationGraph.MakeRecord(Map.of("part", "value")));
            var record = new ComputationGraph(expression.inputs(), nodes, Map.of("value", "record"));
            var assignment = new FlowProgram.Evaluate("assignment", "assignment",
                new FlowCallBindings(Map.of(), Map.of("value", "answer")));
            draft.place(List.of(assignment), new FlowDraft.Socket(null, 0), FlowBlockLayout.DEFAULT.main(), Map.of("assignment", record));
            var workspace = new ProgramWorkspace();
            workspace.setFlow(Optional.of(draft.snapshot()));
            var original = workspace.snapshot();
            var screen = FlowCallScreenTest.editor(workspace, 640);
            var canvas = (FlowBlockCanvas)get(screen, "canvas");

            for (boolean apply : List.of(false, true)) {
                var root = canvas.inlineSlots().stream().filter(s -> "record".equals(s.node())).findFirst().orElseThrow();
                assertTrue(screen.mouseClicked(root.x() + 8, root.y() + root.height() / 2, 0));
                screen.mouseReleased(root.x() + 8, root.y() + root.height() / 2, 0);
                var member = FlowInlineScreenTest.constructorMember(screen);
                assertTrue(screen.mouseClicked(member.getX() + 4, member.getY() + 5, 0));
                var slots = (List<FlowBlockCanvas.Slot>)get(screen, "constructionFocusSlots");
                var preset = slots.stream().filter(s -> "option".equals(s.node())).findFirst().orElseThrow();
                assertTrue(preset.label().endsWith(" ▾"));
                assertTrue(screen.mouseClicked(preset.x() + preset.width() - 4, preset.y() + preset.height() / 2, 0));
                select(screen, "fixture.level.second");
                assertEquals(original, workspace.snapshot(), "The choice belongs to the temporary constructor");
                click(screen, apply ? "construction_apply" : "construction_cancel");
                if (!apply) assertEquals(original, workspace.snapshot());
            }
            var saved = ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(workspace.snapshot()));
            assertEquals(new DataValue.RecordValue(Map.of("part", number(5))), FlowInlineScreenTest.run(saved).field("answer"));
            click(screen, "undo");
            assertEquals(original, workspace.snapshot());
        });
    }

    @ParameterizedTest
    @EnumSource(value = ScratchExtension.Shape.class, names = {"CONDITIONAL", "CONDITIONAL_ELSE", "LOOP", "HAT"})
    void aTypedPresetIsEditableOnAControlOrEventBlock(ScratchExtension.Shape shape) throws Exception {
        withCommonSetup(() -> useControlMenu(shape));
    }

    @FunctionalInterface
    private interface RegistrationTest { void run() throws Exception; }

    private static void withCommonSetup(RegistrationTest run) throws Exception {
        synchronized (ScratchExtensions.class) {
            var registry = ScratchExtensions.class;
            var frozen = registry.getDeclaredField("frozen"); frozen.setAccessible(true);
            var definitions = registry.getDeclaredField("DEFINITIONS"); definitions.setAccessible(true);
            var linked = registry.getDeclaredField("BINDINGS"); linked.setAccessible(true);
            var registeredExtensions = (Map<?, ?>) definitions.get(null);
            var linkedCommands = (Map<?, ?>) linked.get(null);
            var extensionKeys = Set.copyOf(registeredExtensions.keySet());
            var commandKeys = Set.copyOf(linkedCommands.keySet());
            boolean wasFrozen = frozen.getBoolean(null);
            try {
                // Reproduce one add-on during common setup, independently of test execution order.
                frozen.setBoolean(null, false);
                run.run();
            } finally {
                registeredExtensions.keySet().retainAll(extensionKeys);
                linkedCommands.keySet().retainAll(commandKeys);
                frozen.setBoolean(null, wasFrozen);
            }
        }
    }

    private static void useControlMenu(ScratchExtension.Shape shape) throws Exception {
        String addon = "scratch_menu_probe:control", opcode = "fixture_threshold";
        var integer = DataType.Scalar.INTEGER;
        var threshold = new ScratchExtension.Command(opcode, "create_biotech.biocomputing.flow_editor.extension_" + opcode, "fixture.help",
            Map.of("threshold", integer), Map.of("threshold", number(1)), Map.of("matches", DataType.Scalar.BOOLEAN),
            Map.of("matches", "matches"),
            new AssemblySource("REG level INTEGER\nREG matches BOOLEAN\nSIO.READ level p0\nLESS_EQUAL_INTEGER matches threshold level\nRET"),
            Map.of("threshold", List.of(new ScratchExtension.MenuOption("fixture.threshold.low", number(1)),
                new ScratchExtension.MenuOption("fixture.threshold.high", number(8)))), shape, ScratchExtension.SensingRole.INPUT);
        ScratchExtensions.register(new ScratchExtension(ResourceLocation.parse(addon), 1, "fixture.control", "fixture.help",
            Map.of(), List.of(threshold)));
        var draft = new FlowDraft(FlowDocumentBody.emptyAssembly());
        draft.setExtension(addon, 1L);
        var archive = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()),
            Map.of("hits", new ProgramDefinition.Cell(integer, number(0))), List.of(), List.of()), Map.of());
        var workspace = new ProgramWorkspace(new ProgramDocument(archive, Map.of(), Optional.empty(), Optional.of(draft.snapshot())));
        var screen = FlowCallScreenTest.editor(workspace, 640);
        set(screen, "height", 460);
        screen.init();
        var canvas = (FlowBlockCanvas)get(screen, "canvas");
        FlowPaletteScreenTest.search(screen).setValue(opcode);
        var prototype = button(screen, "extension_" + opcode);
        var joint = canvas.joints().getFirst();
        double x = (int)get(canvas, "x") + (shape == ScratchExtension.Shape.HAT ? 30 : joint.x() + 8);
        double y = (int)get(canvas, "y") + (shape == ScratchExtension.Shape.HAT ? 78 : joint.y() + 10);
        dropControl(screen, prototype, x, y);
        FlowPaletteScreenTest.search(screen).setValue("public_add");
        var cavity = canvas.joints().stream().filter(j -> shape == ScratchExtension.Shape.HAT
            ? j.socket().stack() != null && j.socket().index() == 2
            : j.opening() > 0 && j.socket().index() == 1).findFirst().orElseThrow();
        dropControl(screen, button(screen, "add_public_add"), (int)get(canvas, "x") + cavity.x() + 8,
            (int)get(canvas, "y") + cavity.y() + 10);
        var before = workspace.snapshot();
        var slot = canvas.inlineSlots().stream().filter(s -> before.flow().orElseThrow().inline().get(s.call()).nodes().get(s.node())
            instanceof ComputationGraph.Constant value && number(1).equals(value.value()) && s.label().endsWith(" ▾"))
            .findFirst().orElseThrow(() -> new AssertionError("The preset uses the visible typed slot on the control head"));
        assertTrue(slot.label().endsWith(" ▾"), "The preset uses the visible typed slot on the control head");
        assertTrue(screen.mouseClicked(slot.x() + slot.width() - 4, slot.y() + slot.height() / 2, 0));
        assertEquals(before, workspace.snapshot(), "Opening the menu does not edit the draft");
        select(screen, "fixture.threshold.high");

        var updated = ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(workspace.snapshot()));
        assertNotEquals(before, updated);
        assertEquals(updated, workspace.snapshot());
        assertEquals(new ComputationGraph.Constant(number(8)),
            updated.flow().orElseThrow().inline().get(slot.call()).nodes().get(slot.node()));
        var limits = new ComputerHostLimits(new ResourceLimits(512, 65_536, 16), SubgraphWorkspaceTest.LIMITS,
            MailboxLimits.DEFAULT, HardwarePins.DEFAULT, SerialHardware.NONE, 3);
        updated.validate(limits.hardware(), limits.pins());
        var low = start(updated, limits);
        drive(low, limits, 0, 4);
        assertEquals(0, hits(low), "A level above the old default but below the selected preset must skip the body");
        var high = shape == ScratchExtension.Shape.HAT ? low : start(updated, limits);
        drive(high, limits, 24, 15);
        if (shape == ScratchExtension.Shape.LOOP) assertTrue(hits(high) > 1);
        else assertEquals(1, hits(high));
        click(screen, "undo");
        assertEquals(before, workspace.snapshot());
    }

    private static void dropControl(ComputerFlowScreen screen, AbstractWidget prototype, double x, double y) {
        assertTrue(screen.mouseClicked(prototype.getX() + 8, prototype.getY() + 8, 0));
        screen.mouseDragged(x, y, 0, 40, 0);
        assertTrue(screen.mouseReleased(x, y, 0));
    }

    private static ComputerBlockHost start(ProgramDocument document, ComputerHostLimits limits) {
        var flow = document.flow().orElseThrow();
        var host = new ComputerBlockHost(() -> {});
        host.installFlow(flow.program(document.archive()), flow.initial(), limits, true);
        host.setPaused(false, limits, true);
        return host;
    }

    private static void drive(ComputerBlockHost host, ComputerHostLimits limits, long from, int p0) {
        for (long tick = from; tick < from + 24; tick++) host.tick(tick, limits, true, Map.of("p0", p0));
    }

    private static long hits(ComputerBlockHost host) {
        return assertInstanceOf(DataValue.IntegerValue.class, host.flowSnapshot().orElseThrow().memory().cells().field("hits")).value();
    }

    private static void useMenus() throws Exception {
        String addon = "scratch_menu_probe:preset";
        var display = new ScratchExtension(ResourceLocation.parse(addon), 1, "fixture.extension", "fixture.help", Map.of(), List.of(
            command("level", ScratchExtension.Shape.REPORTER, DataType.Scalar.INTEGER, number(2), number(5)),
            command("ready", ScratchExtension.Shape.BOOLEAN, DataType.Scalar.BOOLEAN, new DataValue.BooleanValue(false), new DataValue.BooleanValue(true))));
        ScratchExtensions.register(display);
        var draft = new FlowDraft(FlowDocumentBody.emptyAssembly());
        draft.setExtension(addon, 1L);
        var level = new ScratchExtensions.Binding(display, display.commands().getFirst());
        var ready = new ScratchExtensions.Binding(display, display.commands().getLast());
        FlowProcedureTools.define(draft, level.template("fixture_level"), false);
        FlowProcedureTools.define(draft, ready.template("fixture_ready"), false);
        var expression = graph(level);
        var assignment = new FlowProgram.Evaluate("assignment", "assignment", new FlowCallBindings(Map.of(), Map.of("value", "answer")));
        draft.place(List.of(assignment), new FlowDraft.Socket(null, 0), FlowBlockLayout.DEFAULT.main(), Map.of("assignment", expression));
        var parked = new ScratchValueLayout.Reporter(new ProgramDocument.Position(95, 82), graph(ready), Map.of(), Map.of());
        draft.editValues(draft.snapshot().canvas().values().withParked(List.of(parked)));

        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(draft.snapshot()));
        var before = workspace.snapshot();
        var screen = FlowCallScreenTest.editor(workspace, 640);
        var canvas = (FlowBlockCanvas) get(screen, "canvas");
        var slot = canvas.inlineSlots().stream().filter(s -> s.call().equals("assignment") && "option".equals(s.node())).findFirst().orElseThrow();
        assertTrue(slot.label().endsWith(" ▾"), "The arrow is an actual entry on the nested literal");
        assertTrue(screen.mouseClicked(slot.x() + slot.width() - 4, slot.y() + slot.height() / 2, 0));
        assertEquals(before, workspace.snapshot());
        select(screen, "fixture.level.second");

        var updated = workspace.snapshot();
        assertEquals(number(5), FlowInlineScreenTest.run(updated).field("answer"));
        assertEquals(parked, updated.flow().orElseThrow().canvas().values().parked().getFirst());
        assertEquals(updated, ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(updated)));
        var chosenSlot = canvas.inlineSlots().stream().filter(s -> "option".equals(s.node())).findFirst().orElseThrow();
        FlowInlineScreenTest.dropReporter(screen, "inline_add_integer", chosenSlot);
        var embedded = workspace.snapshot();
        var embeddedBody = embedded.flow().orElseThrow();
        assertEquals(number(5), embeddedBody.canvas().values().forBlock("assignment").get("option"));
        assertInstanceOf(ComputationGraph.Scalar.class, embeddedBody.inline().get("assignment").nodes().get("option"));
        assertEquals(number(5), FlowInlineScreenTest.run(embedded).field("answer"), "Wrapping seeds the addend with the previous literal");
        var operand = canvas.inlineSlots().stream().filter(s -> "option".equals(s.node())).findFirst().orElseThrow();
        assertFalse(operand.label().endsWith(" ▾"), "An expression replaces the literal's dropdown, rather than being coerced");
        double x = (int)get(canvas,"x") + 271, y = (int)get(canvas,"y") + 95;
        assertTrue(screen.mouseClicked(operand.x()+3, operand.y()+3, 0));
        screen.mouseDragged(x,y,0,40,20);
        screen.mouseReleased(x,y,0);
        assertEquals(number(5), FlowInlineScreenTest.run(workspace.snapshot()).field("answer"));
        assertTrue(canvas.inlineSlots().stream().filter(s -> "option".equals(s.node())).findFirst().orElseThrow().label().endsWith(" ▾"));
        click(screen, "undo");
        assertEquals(embedded, workspace.snapshot());
        click(screen, "undo");
        assertEquals(updated, workspace.snapshot());
        click(screen, "undo");
        assertEquals(before, workspace.snapshot());

        var hit = parkedOption(canvas, 95, 82);
        assertTrue(hit.slot().label().endsWith(" ▾"));
        var field = hit.slot();
        assertTrue(screen.mouseClicked(field.x() + field.width() - 4, field.y() + field.height() / 2, 0));
        select(screen, "fixture.ready.second");
        var values = workspace.snapshot().flow().orElseThrow();
        assertEquals(expression, values.inline().get("assignment"));
        var saved = values.canvas().values().parked().getFirst();
        assertEquals(parked.position(), saved.position());
        var value = assertInstanceOf(ComputationGraph.Constant.class, saved.graph().nodes().get("option")).value();
        assertEquals(new DataValue.BooleanValue(true), value);
        assertEquals(workspace.snapshot(), ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(workspace.snapshot())));
        click(screen, "undo");
        assertEquals(before, workspace.snapshot());
    }

    private static ScratchExtension.Command command(String name, ScratchExtension.Shape shape, DataType type, DataValue a, DataValue b) {
        var options = List.of(new ScratchExtension.MenuOption("fixture." + name + ".first", a),
            new ScratchExtension.MenuOption("fixture." + name + ".second", b));
        return new ScratchExtension.Command(name, "fixture." + name, "fixture.help",
            Map.of("choice", type), Map.of("choice", a), Map.of("answer", type), Map.of("answer", "choice"),
            new AssemblySource("RET"), Map.of("choice", options), shape);
    }

    private static ComputationGraph graph(ScratchExtensions.Binding definition) {
        return new ComputationGraph(Map.of(), Map.of(
            "option", new ComputationGraph.Constant(definition.command().defaults().get("choice")),
            "value", new ComputationGraph.ProcedureValue(definition.key(), "answer", Map.of("choice", "option"))), Map.of("value", "value"));
    }

    private static FlowBlockCanvas.ParkedSlot parkedOption(FlowBlockCanvas canvas, int px, int py) throws Exception {
        int left = (int)get(canvas,"x"), top = (int)get(canvas,"y");
        for(int x = px; x < px + 190; x++) {
            var hit = canvas.parkedSlotHit(left + x, top + py + 12);
            if (hit != null && "option".equals(hit.slot().node())) return hit;
        }
        throw new AssertionError("Unattached reporter lost its editable argument");
    }

    private static void select(ComputerFlowScreen screen, String key) {
        var menuChoice = screen.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
            .filter(button -> button.getMessage().getContents() instanceof TranslatableContents text && text.getKey().equals(key))
            .findFirst().orElseThrow(() -> new AssertionError("Missing menu option " + key));
        assertTrue(menuChoice.active);
        menuChoice.onClick(0, 0);
    }

    private static DataValue.IntegerValue number(long value) { return new DataValue.IntegerValue(value); }
}
