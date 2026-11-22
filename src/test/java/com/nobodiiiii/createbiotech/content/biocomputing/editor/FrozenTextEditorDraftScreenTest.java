package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationGraph;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ValueNbtCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import org.junit.jupiter.api.*;

class FrozenTextEditorDraftScreenTest {
    private final ComputerProgramScreenTest environment = new ComputerProgramScreenTest();
    @BeforeAll static void bootstrap() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void start() throws Exception { environment.client(); }
    @AfterEach void stop() throws Exception { environment.restore(); }

    @Test void playerCanEditTheRecoveredLineAndReopenASavedDraftWithoutInstalling() throws Exception {
        var limits = StockApplicationDriverDocuments.hardware().archive();
        var original = NbtIo.readCompressed(getClass().getResourceAsStream("/old-text-editor-player.nbt"),
            NbtAccounter.create(16 * 1024 * 1024));
        var installed = ProgramDocumentCodec.decode(ValueNbtCodec.decode(
            original.getCompound("BiocomputingEditor").getCompound("Active"), limits));

        var draft = new SavedProgramDraft();
        var first = open(installed, draft.snapshot(limits));
        assertEquals(new DataValue.TextValue("READY"), line(first.workspace().snapshot()));
        editLine(first.blocks(), first.workspace().snapshot(), "EDITED READY");
        var edited = first.workspace().snapshot();
        assertEquals(new DataValue.TextValue("EDITED READY"), line(edited));
        assertOnlyLineChanged(installed, edited);

        ComputerFlowScreenTest.click(first.blocks(), "save_draft");
        assertEquals(2, first.requests().size());
        var save = first.requests().getLast();
        assertTrue(save.save());
        assertEquals(0, save.revision());
        assertEquals(edited, ProgramDocumentWire.decode(save.document(), limits));
        assertTrue(draft.save(save.revision(), save.document(), limits));
        first.menu().accept(new ProgramDraftResponse(save.containerId(), save.nonce(), save.intent(), 1, "saved", "", new byte[0]));
        first.blocks().containerTick();
        assertEquals(edited, get(first.hub(), "savedBaseline"));
        assertEquals(first.installIntent(), get(first.menu(), "latestIntent"));
        assertEquals(installed, ProgramDocumentWire.decode(first.menu().response().orElseThrow().archive(), limits));

        var cold = new SavedProgramDraft();
        cold.read(draft.write());
        var reopened = open(installed, cold.snapshot(limits));
        assertEquals(edited, reopened.workspace().snapshot());
        assertEquals(new DataValue.TextValue("READY"), line(installed), "The installed source is still unchanged");
        editLine(reopened.blocks(), reopened.workspace().snapshot(), "TEMPORARY");
        ComputerFlowScreenTest.click(reopened.blocks(), "undo");
        assertEquals(edited, reopened.workspace().snapshot());
        assertEquals(reopened.installIntent(), get(reopened.menu(), "latestIntent"));
        assertEquals(1, reopened.requests().size(), "Opening and undo must not save or install");
        assertEquals(1, cold.snapshot(limits).revision());
    }

    private static Session open(ProgramDocument installed, SavedProgramDraft.Snapshot saved) throws Exception {
        var base = screen();
        var menu = ComputerFlowScreenTest.readyMenu(base);
        var old = menu.response().orElseThrow();
        var hw = StockApplicationDriverDocuments.hardware();
        var current = new ProgramEditorResponse(menu.containerId, old.nonce(), old.intent(), old.revision(), "ready", "",
            hw.hardware(), hw.archive(), ProgramDocumentWire.encode(installed, hw.archive()));
        set(menu, "response", Optional.of(current));
        set(menu, "nonce", current.nonce());
        set(menu, "responseVersion", 1L);
        var requests = new ArrayList<ProgramDraftRequest>();
        menu.requestSavedDraft(requests::add);
        var read = requests.getFirst();
        assertFalse(read.save());

        var hub = new ComputerScratchScreen(menu, (Inventory)get(base, "editorInventory"), Component.empty());
        Screen[] displayed = {hub};
        set(hub, "navigator", (Consumer<Screen>) screen -> displayed[0] = screen);
        set(hub, "draftSender", (Consumer<ProgramDraftRequest>) requests::add);
        ComputerScratchNavigationTest.initialize(hub);
        set(hub, "requested", true);
        menu.accept(new ProgramDraftResponse(menu.containerId, current.nonce(), read.intent(), saved.revision(),
            "ready", "", saved.document()));
        hub.containerTick();
        var blocks = assertInstanceOf(ComputerFlowScreen.class, displayed[0]);
        ComputerFlowScreenTest.initialize(blocks, 640);
        return new Session(hub, menu, blocks, (ProgramWorkspace)get(hub, "workspace"), requests, get(menu, "latestIntent"));
    }

    private static ComputationGraph fields(ProgramDocument document) {
        return document.flow().orElseThrow().inline().get("block_1");
    }

    private static DataValue line(ProgramDocument document) {
        var graph = fields(document);
        return assertInstanceOf(ComputationGraph.Constant.class, graph.nodes().get(graph.outputs().get("line"))).value();
    }

    private static void editLine(ComputerFlowScreen screen, ProgramDocument document, String text) throws Exception {
        var canvas = (FlowBlockCanvas)get(screen, "canvas");
        var lineNode = fields(document).outputs().get("line");
        var slot = canvas.inlineSlots().stream()
            .filter(s -> s.call().equals("block_1") && s.node().equals(lineNode)).findFirst().orElseThrow();
        assertEquals(slot, canvas.inlineHit(slot.x()+8, slot.y()+slot.height()/2));
        FlowInlineScreenTest.setLiteral(screen, slot, text);
    }

    private static void assertOnlyLineChanged(ProgramDocument original, ProgramDocument edited) {
        var before = fields(original);
        var after = fields(edited);
        assertEquals(before.outputs(), after.outputs());
        for (String name : List.of("x", "y", "z", "device", "session", "revision")) {
            assertEquals(before.nodes().get(before.outputs().get(name)), after.nodes().get(after.outputs().get(name)), name);
        }
        assertEquals(original.flow().orElseThrow().inline().get("block_2"), edited.flow().orElseThrow().inline().get("block_2"));
        assertEquals(original.flow().orElseThrow().steps(), edited.flow().orElseThrow().steps());
    }

    private record Session(ComputerScratchScreen hub, ComputerProgramMenu menu, ComputerFlowScreen blocks,
                           ProgramWorkspace workspace, List<ProgramDraftRequest> requests, Object installIntent) {}
}
