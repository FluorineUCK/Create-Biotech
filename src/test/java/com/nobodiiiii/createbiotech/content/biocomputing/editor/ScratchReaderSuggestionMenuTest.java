package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerFlowScreenTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.FlowPaletteScreenTest.search;
import static org.junit.jupiter.api.Assertions.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationGraph;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import java.util.Map;
import java.util.Optional;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ScratchReaderSuggestionMenuTest {
    private final ComputerProgramScreenTest environment = new ComputerProgramScreenTest();
    @BeforeAll static void bootstrapClient() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void client() throws Exception { environment.client(); }
    @AfterEach void restoreClient() throws Exception { environment.restore(); }

    @Test void readerComponentMenuUsesOnlyTheSelectedCapturedTargetAndNeverRewritesAnExistingChoice() throws Exception {
        var draft = new FlowDraft(FlowDocumentBody.emptySoftwareAssembly());
        draft.setExtension("create_biotech:reader", 1L);
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(draft.snapshot()));
        var screen = FlowCallScreenTest.editor(workspace, 1000);
        set(screen, "height", 450); screen.init();
        search(screen).setValue("reader_sample");
        var canvas = (FlowBlockCanvas)get(screen, "canvas");
        dragToJoint(screen, canvas, button(screen, "extension_reader_sample"), canvas.joints().getFirst());
        var before = workspace.snapshot();
        var call = before.flow().orElseThrow().steps().getFirst();
        canvas.focus(call.sourceId());
        var node = before.flow().orElseThrow().inline().get(call.sourceId()).outputs().get("component");
        var field = canvas.inlineSlots().stream().filter(slot -> node.equals(slot.node())).findFirst().orElseThrow();
        assertTrue(screen.mouseClicked(field.x() + field.width() - 3, field.y() + 8, 0));
        assertTrue(hasMenuLabel(screen, "create_biotech.biocomputing.flow_editor.extension_menu_refresh"), "Open menu includes explicit capture");
        var menu = (ComputerProgramMenu)get(screen, "menu");
        var liveId = "example:sampled"; var blockedId = "example:denied"; var otherId = "example:other_read";
        var sample = new DataValue.IntegerValue(2);
        var types = record(Map.of(
            liveId, record(Map.of("label", new DataValue.TextValue("example.component.sampled"))),
            blockedId, record(Map.of("label", new DataValue.TextValue("example.component.denied"))),
            otherId, record(Map.of("label", new DataValue.TextValue("example.component.other")))));
        var readers = record(Map.of(
            "0,0,0", record(Map.of("components", record(Map.of(liveId, sample, blockedId, new DataValue.ErrorValue("access_denied", "fixture"))))),
            "1,0,0", record(Map.of("components", record(Map.of(otherId, sample))))));
        var capture = record(Map.of("component_types", types, "readers", readers));
        var root = record(Map.of("captured_inputs", record(Map.of("peripherals", capture))));
        set(menu, "latestControlAction", ComputerControlSession.Action.READ_PERIPHERALS);
        set(menu, "latestControlIntent", 3L);
        var revision = menu.response().orElseThrow();
        set(menu, "containerId", revision.containerId()); set(menu, "nonce", revision.nonce());
        byte[] bytes = ProgramEditorWire.encodeValue(root, revision.archiveLimits());
        menu.accept(new ProgramStatusResponse(revision.containerId(), revision.nonce(), 2, revision.revision(), "ok", "", bytes));
        screen.containerTick();
        assertFalse(hasMenuLabel(screen, "example.component.sampled"));
        menu.accept(new ProgramStatusResponse(revision.containerId(), revision.nonce(), 3, revision.revision(), "ok", "", bytes));
        screen.containerTick();
        var options = screen.children().stream().filter(com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton.class::isInstance)
            .map(com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton.class::cast).toList();
        assertFalse(hasMenuLabel(screen, "example.component.denied"));
        assertFalse(hasMenuLabel(screen, "example.component.other"));
        var sampled = options.stream().filter(option -> option.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents label
            && label.getKey().equals("example.component.sampled")).findFirst().orElseThrow();
        assertEquals(before, workspace.snapshot(), "Capturing suggestions must not edit the draft");
        sampled.onClick(0,0);
        var chosen = workspace.snapshot();
        assertEquals(new ComputationGraph.Constant(new DataValue.TextValue(liveId)),
            chosen.flow().orElseThrow().inline().get(call.sourceId()).nodes().get(node));
        assertEquals(chosen, ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(chosen)));
        var language = net.minecraft.locale.Language.getInstance();
        try {
            net.minecraft.locale.Language.inject(ComputerUiTranslationsTest.language(Map.of("example.component.sampled", "Sampled")));
            var slot = canvas.inlineSlots().stream().filter(s -> node.equals(s.node())).findFirst().orElseThrow();
            assertEquals("Sampled ▾", slot.label(), "The selected suggestion should remain readable on the block");
            var hint = screen.typeTooltip((int)(slot.x() + 5), (int)(slot.y() + 8), true);
            assertNotNull(hint);
            assertTrue(hint.getString().contains(liveId), "Shift reveals the exact component ID");
        } finally {
            net.minecraft.locale.Language.inject(language);
        }
        var current = canvas.inlineSlots().stream().filter(slot -> node.equals(slot.node())).findFirst().orElseThrow();
        assertTrue(screen.mouseClicked(current.x() + current.width() - 3, current.y() + 8, 0));
        set(menu, "latestControlIntent", 4L);
        menu.accept(new ProgramStatusResponse(revision.containerId(), revision.nonce(), 3, revision.revision(), "too_large", "old response", new byte[0]));
        screen.containerTick();
        String prefix = "create_biotech.biocomputing.flow_editor.";
        assertFalse(hasMenuLabel(screen, prefix + "extension_menu_failure_too_large"));

        menu.accept(new ProgramStatusResponse(revision.containerId(), revision.nonce(), 4, revision.revision(), "too_large", "snapshot_limit", new byte[0]));
        screen.containerTick();
        assertTrue(button(screen, "extension_menu_failure_too_large").active, "Limit failure leaves explicit retry");
        assertTrue(hasMenuLabel(screen, prefix + "extension_reader_component_redstone"), "Failure retains static presets");
        assertFalse(hasMenuLabel(screen, "example.component.sampled"));
        assertEquals(liveId + " ▾", canvas.inlineSlots().stream().filter(slot -> node.equals(slot.node())).findFirst().orElseThrow().label());
        assertEquals(chosen, workspace.snapshot());

        set(menu, "latestControlIntent", 5L);
        menu.accept(new ProgramStatusResponse(revision.containerId(), revision.nonce(), 5, revision.revision(), "invalid", "access_denied", new byte[0]));
        screen.containerTick();
        assertTrue(button(screen, "extension_menu_failure_access_denied").active);
        assertFalse(hasMenuLabel(screen, prefix + "extension_menu_failure_too_large"));

        set(menu, "latestControlAction", ComputerControlSession.Action.READ_NETWORK);
        set(menu, "latestControlIntent", 6L);
        menu.accept(new ProgramStatusResponse(revision.containerId(), revision.nonce(), 6, revision.revision(), "too_large", "unrelated", new byte[0]));
        screen.containerTick();
        assertTrue(button(screen, "extension_menu_refresh").active, "An unrelated failure is not a capture failure");

        set(menu, "latestControlAction", ComputerControlSession.Action.READ_PERIPHERALS);
        set(menu, "latestControlIntent", 7L);
        var otherOnly = record(Map.of("component_types", types, "readers", record(Map.of("1,0,0", readers.field("1,0,0")))));
        var notCaptured = record(Map.of("captured_inputs", record(Map.of("peripherals", otherOnly))));
        menu.accept(new ProgramStatusResponse(revision.containerId(), revision.nonce(), 7, revision.revision(), "ok", "",
            ProgramEditorWire.encodeValue(notCaptured, revision.archiveLimits())));
        screen.containerTick();
        assertTrue(button(screen, "extension_menu_target_not_captured").active);
        assertTrue(hasMenuLabel(screen, prefix + "extension_reader_component_redstone"));
        assertFalse(hasMenuLabel(screen, "example.component.other"));
        assertEquals(chosen, workspace.snapshot(), "No capture for this reader must not replace the selected value");

        set(menu, "latestControlIntent", 8L);
        var deniedComponents = record(Map.of(blockedId, new DataValue.ErrorValue("access_denied", "fixture")));
        var unusable = record(Map.of("component_types", types,
            "readers", record(Map.of("0,0,0", record(Map.of("components", deniedComponents))))));
        var empty = record(Map.of("captured_inputs", record(Map.of("peripherals", unusable))));
        menu.accept(new ProgramStatusResponse(revision.containerId(), revision.nonce(), 8, revision.revision(), "ok", "",
            ProgramEditorWire.encodeValue(empty, revision.archiveLimits())));
        screen.containerTick();
        assertTrue(button(screen, "extension_menu_no_suggestions").active);
        assertTrue(hasMenuLabel(screen, prefix + "extension_reader_component_redstone"));
        assertFalse(hasMenuLabel(screen, "example.component.denied"));
        assertEquals(chosen, workspace.snapshot());

        set(menu, "latestControlIntent", 9L);
        menu.accept(new ProgramStatusResponse(revision.containerId(), revision.nonce(), 9, revision.revision(), "ok", "", bytes));
        screen.containerTick();
        assertTrue(button(screen, "extension_menu_suggestions").active);
        assertTrue(hasMenuLabel(screen, "example.component.sampled"));
        screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE, 0, 0);
        click(screen, "undo"); assertEquals(before, workspace.snapshot());

        // Built-in component names need no world read and survive a fresh session/portable document.
        var graph = chosen.flow().orElseThrow().inline().get(call.sourceId());
        var nodes = new java.util.LinkedHashMap<>(graph.nodes());
        String publicId = "create_biotech:public_data";
        nodes.put(node, new ComputationGraph.Constant(new DataValue.TextValue(publicId)));
        var reopenedDraft = new FlowDraft(chosen.flow().orElseThrow());
        reopenedDraft.editInline(call.sourceId(), new ComputationGraph(graph.inputs(), nodes, graph.outputs()), FlowInlineValues.bindings(call));
        var portable = new ProgramWorkspace(chosen);
        portable.setFlow(Optional.of(reopenedDraft.snapshot()));
        var reopened = new ProgramWorkspace(ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(portable.snapshot())));
        try {
            net.minecraft.locale.Language.inject(ComputerUiTranslationsTest.language(ComputerUiTranslationsTest.load("zh_cn")));
            var editor = FlowCallScreenTest.editor(reopened, 1000);
            set(editor, "height", 450); editor.init();
            var reopenedCanvas = (FlowBlockCanvas)get(editor, "canvas");
            reopenedCanvas.focus(call.sourceId());
            var slot = reopenedCanvas.inlineSlots().stream().filter(s -> node.equals(s.node())).findFirst().orElseThrow();
            assertEquals("公开同步数据 ▾", slot.label());
            assertFalse(editor.typeTooltip((int)(slot.x() + 5), (int)(slot.y() + 8), false).getString().contains(publicId));
            assertTrue(editor.typeTooltip((int)(slot.x() + 5), (int)(slot.y() + 8), true).getString().contains(publicId));
            assertEquals(portable.snapshot(), reopened.snapshot());
        } finally {
            net.minecraft.locale.Language.inject(language);
        }
    }

    private static DataValue.RecordValue record(Map<String, DataValue> fields) { return new DataValue.RecordValue(fields); }
    private static boolean hasMenuLabel(ComputerFlowScreen screen, String key) {
        return screen.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
            .anyMatch(option -> option.getMessage().getContents() instanceof TranslatableContents label && label.getKey().equals(key));
    }
    private static void dragToJoint(ComputerFlowScreen screen, FlowBlockCanvas canvas, CreateTextButton tool, FlowBlockCanvas.Joint joint) throws Exception {
        var x=(int)get(canvas,"x")+joint.x()+8;
        var y=(int)get(canvas,"y")+joint.y()+8;
        assertTrue(screen.mouseClicked(tool.getX()+8, tool.getY()+8, 0));
        screen.mouseDragged(x,y,0,40,0);
        assertTrue(screen.mouseReleased(x,y,0));
    }
}
