package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.get;
import static org.junit.jupiter.api.Assertions.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.device.display.*;
import com.nobodiiiii.createbiotech.content.biocomputing.extension.ScratchExtensions;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import java.util.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.*;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.*;

class ScratchTextOutputExtensionTest {
    private static final String EXTENSION = "create_biotech:text_output";
    private static final BlockPos DISPLAY = new BlockPos(2, 0, 0);
    private static final DataValue.RecordValue TARGET = new DataValue.RecordValue(Map.of("x", number(2), "y", number(0), "z", number(0)));
    private static final TextOutputState.Limits PAGE = new TextOutputState.Limits(16, 2048);
    private static final String DISPLAY_TEXT = "北站 A · ready";

    private final ComputerProgramScreenTest client = new ComputerProgramScreenTest();
    @BeforeAll static void bootstrap() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void setupClient() throws Exception { client.client(); }
    @AfterEach void closeClient() throws Exception { client.restore(); }

    @Test void explicitOutputCategoryCanPlaceAQualifiedFixedBlockAndUndoWithoutSending() throws Exception {
        assertTrue(ScratchExtensions.binding(key("status")).isPresent(), "Text display extension is not registered");
        var draft = new FlowDraft(FlowDocumentBody.emptySoftwareAssembly());
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(draft.snapshot()));
        var screen = FlowCallScreenTest.editor(workspace, 640);
        click(screen, "category_sensing"); click(screen, "sensing_output");
        assertTrue(visibleExtensions(screen).isEmpty(), "Default-disabled: no new devices or actions");
        draft.setExtension(EXTENSION, 1L);
        workspace.setFlow(Optional.of(draft.snapshot()));
        screen = FlowCallScreenTest.editor(workspace, 640);
        click(screen, "category_sensing"); click(screen, "sensing_output");
        assertEquals(Set.of("extension:" + EXTENSION + ":status", "extension:" + EXTENSION + ":replace_line"), visibleExtensions(screen));
        ((net.minecraft.client.gui.components.EditBox)get(screen,"catalogSearch")).setValue("text_output status");
        var state = workspace.snapshot();
        var status = find(screen, "extension_text_output_status");
        assertNotNull(status);
        var canvas = (FlowBlockCanvas)get(screen, "canvas");
        var socket = canvas.joints().getFirst();
        var x = (int)get(canvas, "x") + socket.x() + 8;
        var y = (int)get(canvas, "y") + socket.y() + 10;
        assertTrue(screen.mouseClicked(status.getX()+8,status.getY()+8,0));
        assertTrue(screen.mouseDragged(x,y,0,60,30));
        assertEquals(state, workspace.snapshot());
        assertTrue(screen.mouseReleased(x,y,0));
        var document = workspace.snapshot();
        var call = assertInstanceOf(FlowProgram.Invoke.class, document.flow().orElseThrow().steps().getFirst());
        assertEquals(key("status"),call.procedure());
        assertEquals(Set.of("x","y","z"),document.flow().orElseThrow().inline().get(call.sourceId()).outputs().keySet());
        assertEquals(document,ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(document)));
        assertDoesNotThrow(() -> PreparedProgram.prepare(document, StockApplicationDriverDocuments.hardware()));
        var xNode = document.flow().orElseThrow().inline().get(call.sourceId()).outputs().get("x");
        var xSlot = canvas.inlineSlots().stream().filter(slot -> call.sourceId().equals(slot.call()) && xNode.equals(slot.node()))
            .findFirst().orElseThrow();
        FlowInlineScreenTest.setLiteral(screen, xSlot, "2");
        var authored = workspace.snapshot();
        var link = line(StockApplicationDriverDocuments.hardware());
        var frame = transferDocument(authored, link.tx(), link.rx(), link.tick());
        var closed = TextOutputState.closed(UUID.randomUUID(), UUID.randomUUID());
        var granted = TextOutputState.grant(closed, Optional.of(link.physical()), UUID.randomUUID());
        var response = TextOutputProtocol.plan(granted, link.physical(), TARGET, frame, PAGE).orElseThrow();
        assertEquals(granted, response.next(), "A status request authored in Scratch must be read-only");
        assertEquals("queued", link.rx().send("s0", response.reply()));
        for (int tick = 0; tick < 600 && link.tx().status("s0").receive().frames() == 0; tick++) link.tick().run();
        assertEquals(1, link.tx().status("s0").receive().frames(), () -> "Reply did not reach Computer: " + link.rx().status("s0"));
        var received = link.tx().prepareReceive("s0");
        var reply = assertInstanceOf(DataValue.RecordValue.class, received.result().field("value"));
        assertEquals(frame.field("request"), reply.field("request"));
        assertEquals(text("ok"), reply.field("code"));
        assertTrue(received.publish());
        click(screen,"undo");
        assertEquals(document, workspace.snapshot(), "First undo restores the edited address, not the entire call");
        click(screen,"undo");
        assertEquals(state,workspace.snapshot());
    }

    @Test void statusAndSingleLineReplacementCompileForStockParserAndPreserveDeviceAuthorization() {
        var link = line(StockApplicationDriverDocuments.hardware());
        var tx = link.tx(); var rx = link.rx(); var physical = link.physical(); var line = link.tick();
        var closed = TextOutputState.closed(UUID.randomUUID(),UUID.randomUUID());
        var granted = TextOutputState.grant(closed, Optional.of(physical), UUID.randomUUID());
        var query = transfer("status", TARGET.fields(), tx, rx, line);
        assertEquals(text("status"),query.field("operation"));
        assertEquals(new DataValue.RecordValue(Map.of()),query.field("arguments"));

        var noGrant = TextOutputProtocol.plan(closed, physical, TARGET, query, PAGE).orElseThrow();
        assertEquals(text("closed"), noGrant.reply().field("code"));
        assertFalse(noGrant.reply().fields().containsKey("state"));
        var status = TextOutputProtocol.plan(granted, physical, TARGET, query, PAGE).orElseThrow();
        assertEquals(text("ok"),status.reply().field("code"));
        assertEquals(query.field("request"),status.reply().field("request"));
        var token = assertInstanceOf(DataValue.RecordValue.class,status.reply().field("state"));
        var inputs = new LinkedHashMap<>(TARGET.fields());
        for(var port:List.of("device","session","revision")) inputs.put(port,token.field(port));
        inputs.put("line",text(DISPLAY_TEXT));
        var replacement = transfer("replace_line", inputs, tx, rx, line);
        assertEquals(text("replace"),replacement.field("operation"));
        assertNotEquals(query.field("request"),replacement.field("request"));
        var denied = TextOutputProtocol.plan(granted, UUID.randomUUID(), TARGET,replacement,PAGE).orElseThrow();
        assertEquals(text("denied"),denied.reply().field("code"));
        assertEquals(granted,denied.next());
        var rotated = TextOutputState.restart(granted, UUID.randomUUID());
        var stale = TextOutputProtocol.plan(rotated,physical,TARGET,replacement,PAGE).orElseThrow();
        assertEquals(text("stale_session"),stale.reply().field("code"));
        assertEquals(rotated,stale.next());
        var accepted = TextOutputProtocol.plan(granted,physical,TARGET,replacement,PAGE).orElseThrow();
        assertEquals(text("accepted"),accepted.reply().field("code"));
        assertEquals(replacement.field("request"),accepted.reply().field("request"));
        assertEquals(List.of(DISPLAY_TEXT),accepted.next().lines());
        assertEquals(text("changed"),TextOutputProtocol.plan(accepted.next(),physical,TARGET,replacement,PAGE).orElseThrow().reply().field("code"));
        assertTrue(TextOutputProtocol.plan(granted,physical,new DataValue.RecordValue(Map.of()),replacement,PAGE).isEmpty());
    }

    @Test void manyArgumentSlotsWrapAndCheckEachDeclaredType() throws Exception {
        var draft = new FlowDraft(FlowDocumentBody.emptySoftwareAssembly());
        draft.setExtension(EXTENSION, 1L);
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(draft.snapshot()));
        var screen = FlowCallScreenTest.editor(workspace, 640);
        click(screen, "category_sensing"); click(screen, "sensing_output");
        var search = (net.minecraft.client.gui.components.EditBox)get(screen, "catalogSearch");
        search.setValue("text_output replace_line");
        var block = find(screen, "extension_text_output_replace_line");
        assertNotNull(block);
        var canvas = (FlowBlockCanvas)get(screen, "canvas");
        var joint = canvas.joints().getFirst();
        var x = (int)get(canvas, "x") + joint.x() + 8;
        var y = (int)get(canvas, "y") + joint.y() + 10;
        assertTrue(screen.mouseClicked(block.getX() + 8, block.getY() + 8, 0));
        assertTrue(screen.mouseDragged(x, y, 0, 60, 30));
        assertTrue(screen.mouseReleased(x, y, 0));
        var draftWithCall = workspace.snapshot();
        var call = assertInstanceOf(FlowProgram.Invoke.class, draftWithCall.flow().orElseThrow().steps().getFirst());
        var outputs = draftWithCall.flow().orElseThrow().inline().get(call.sourceId()).outputs();
        assertEquals(Set.of("x","y","z","device","session","revision","line"), outputs.keySet());
        var visible = canvas.inlineSlots().stream().filter(slot -> call.sourceId().equals(slot.call())).toList();
        assertEquals(new HashSet<>(outputs.values()), visible.stream().map(FlowBlockCanvas.Slot::node).collect(java.util.stream.Collectors.toSet()));
        assertTrue(visible.stream().map(FlowBlockCanvas.Slot::y).distinct().count() > 1, "Arguments wrap instead of hiding outside the block");
        for (var slot : visible) assertEquals(slot, canvas.inlineHit(slot.x()+4, slot.y()+4), "Each painted field is reachable");
        assertDoesNotThrow(() -> PreparedProgram.prepare(draftWithCall, StockApplicationDriverDocuments.hardware()));

        search = (net.minecraft.client.gui.components.EditBox)get(screen, "catalogSearch");
        search.setValue("add_integer");
        dropValue(screen, "inline_add_integer", visible.stream().filter(slot -> slot.node().equals(outputs.get("revision"))).findFirst().orElseThrow());
        var withNumeric = workspace.snapshot();
        assertFalse(withNumeric.flow().orElseThrow().inline().get(call.sourceId()).nodes().get(outputs.get("revision")) instanceof ComputationGraph.Constant);
        search = (net.minecraft.client.gui.components.EditBox)get(screen, "catalogSearch");
        search.setValue("text_concat");
        var xSlot = canvas.inlineSlots().stream().filter(slot -> slot.node().equals(outputs.get("x"))).findFirst().orElseThrow();
        dropValue(screen, "inline_text_concat", xSlot);
        assertEquals(withNumeric, workspace.snapshot(), "A wrong output type cannot replace an INTEGER argument");
        click(screen,"undo"); assertEquals(draftWithCall, workspace.snapshot());
    }

    private static void dropValue(ComputerFlowScreen screen, String prototype, FlowBlockCanvas.Slot target) {
        var button = find(screen, prototype);
        assertNotNull(button, prototype);
        assertTrue(screen.mouseClicked(button.getX() + 8, button.getY() + 8, 0));
        screen.mouseDragged(target.x() + 8, target.y() + target.height()/2, 0, 55, 35);
        assertTrue(screen.mouseReleased(target.x() + 8, target.y() + target.height()/2, 0));
    }
    private record Link(SerialDevice tx, SerialDevice rx, UUID physical, Runnable tick) {}

    private static Link line(com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits hardware) {
        var codec = new SerialRecordCodec.Limits(4096, 1048576, hardware.archive());
        var tx = new SerialDevice(hardware::serial, () -> true, () -> codec, () -> {});
        var rx = new SerialDevice(hardware::serial, () -> true, () -> codec, () -> {});
        tx.configure("s0", Direction.EAST, true);
        rx.configure("s0", Direction.WEST, true);
        var physical = tx.attachments().getFirst().identity();
        var devices = Map.of(BlockPos.ZERO, tx, DISPLAY, rx);
        var net = new SerialNetwork();
        var topology = new CableDiscovery.World() {
            public boolean loaded(BlockPos pos) { return true; }
            public CableDiscovery.Cell cell(BlockPos pos) {
                return new CableDiscovery.Cell(pos.equals(new BlockPos(1,0,0)) ? CableDiscovery.Kind.CABLE
                    : devices.containsKey(pos) ? CableDiscovery.Kind.ENDPOINT : CableDiscovery.Kind.EMPTY,null);
            }
        };
        Runnable pulse = () -> net.tick(topology,devices,new CommunicationNetworkLimits(64,4),new SharedSerialChannel.Limits(2,4096,128,8));
        pulse.run();
        return new Link(tx, rx, physical, pulse);
    }

    private static DataValue.RecordValue transfer(String opcode, Map<String, DataValue> defaults,
            SerialDevice tx, SerialDevice rx, Runnable line) {
        var hardware = StockApplicationDriverDocuments.hardware();
        var draft = new FlowDraft(FlowDocumentBody.emptySoftwareAssembly());
        draft.setExtension(EXTENSION,1L);
        var binding = ScratchExtensions.binding(key(opcode)).orElseThrow();
        var call = draft.placeTemplate(binding.template("fixed"), new FlowDraft.Socket(null, 0), new ProgramDocument.Position(16, 16));
        var graph = draft.snapshot().inline().get(call);
        var nodes = new LinkedHashMap<>(graph.nodes());
        defaults.forEach((name, value) -> nodes.put(Objects.requireNonNull(graph.outputs().get(name)), new ComputationGraph.Constant(value)));
        draft.editInline(call, new ComputationGraph(graph.inputs(), nodes, graph.outputs()), new FlowCallBindings(Map.of(), Map.of()));
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(draft.snapshot()));
        return transferDocument(workspace.snapshot(), tx, rx, line);
    }

    private static DataValue.RecordValue transferDocument(ProgramDocument saved, SerialDevice tx, SerialDevice rx, Runnable line) {
        var hardware = StockApplicationDriverDocuments.hardware();
        var document = ProgramDocumentWire.decode(ProgramDocumentWire.encode(saved,hardware.archive()),hardware.archive());
        assertEquals(saved,document);
        var host = new ComputerBlockHost(()->{}, pin->{}, tx);
        var beforeInstall = tx.status("s0");
        host.installProgram(PreparedProgram.prepare(document,hardware),ComputerRuntime.MemoryPolicy.RESET,hardware,true);
        assertEquals(beforeInstall,tx.status("s0"),"Installing alone never emits a device command");
        host.setPaused(false,hardware,true);
        for(int tick=0;tick<600 && (host.flowSnapshot().orElseThrow().state().status()!=FlowRuntime.Status.COMPLETE
                || rx.status("s0").receive().frames()==0);tick++){
            host.tick(hardware,true);
            line.run();
        }
        var state=host.flowSnapshot().orElseThrow().state();
        assertEquals(FlowRuntime.Status.COMPLETE,state.status(),()->state.diagnostic().toString());
        assertEquals("transmitted",tx.status("s0").transmit());
        assertEquals(1,rx.status("s0").receive().frames());
        var delivered = rx.prepareReceive("s0");
        assertEquals(tx.attachments().getFirst().key(),delivered.source().orElseThrow());
        var data = assertInstanceOf(DataValue.RecordValue.class,delivered.result().field("value"));
        assertTrue(delivered.publish());
        assertEquals(text("text_output_command"),data.field("kind"));
        assertEquals(number(1),data.field("version"));
        assertEquals(TARGET,data.field("target"));
        assertFalse(((DataValue.TextValue)data.field("request")).value().isBlank());
        for(int tick=0;tick<40;tick++){host.tick(hardware,true);line.run();}
        assertEquals(0,rx.status("s0").receive().frames(),"One command sends exactly one frame and does not retry");
        return data;
    }

    private static ModuleDefinition.Key key(String opcode) { return new ModuleDefinition.Key("cb_extension:"+EXTENSION+":"+opcode,1); }
    private static DataValue.TextValue text(String s) { return new DataValue.TextValue(s); }
    private static DataValue.IntegerValue number(long n) { return new DataValue.IntegerValue(n); }
    @SuppressWarnings("unchecked")
    private static Set<String> visibleExtensions(ComputerFlowScreen screen) throws Exception {
        var catalog = (List<FlowBlockCatalog.Entry>)get(screen, "catalogEntries");
        return catalog.stream().map(FlowBlockCatalog.Entry::id).filter(id -> id.startsWith("extension:")).map(id -> id.replace("extension:cb_extension:", "extension:"))
            .collect(java.util.stream.Collectors.toSet());
    }
    private static CreateTextButton find(Screen screen,String key) {
        return screen.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
            .filter(w -> w.visible && w.getMessage().getContents() instanceof TranslatableContents c && c.getKey().endsWith("."+key))
            .findFirst().orElse(null);
    }
    private static void click(ComputerFlowScreen screen,String key) {
        var button = find(screen,key); assertNotNull(button,key); assertTrue(button.active,key);
        screen.mouseClicked(button.getX()+6,button.getY()+6,0);
        screen.mouseReleased(button.getX()+6,button.getY()+6,0);
    }
}

