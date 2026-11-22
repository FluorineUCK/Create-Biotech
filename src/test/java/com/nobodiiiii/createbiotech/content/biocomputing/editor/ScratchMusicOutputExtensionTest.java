package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.get;
import static org.junit.jupiter.api.Assertions.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.device.music.MusicOutputProtocol;
import com.nobodiiiii.createbiotech.content.biocomputing.device.music.MusicOutputState;
import com.nobodiiiii.createbiotech.content.biocomputing.extension.ScratchExtensions;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import java.util.*;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.*;

class ScratchMusicOutputExtensionTest {
    private static final String EXT = "create_biotech:note_output";
    private static final BlockPos ADAPTER = new BlockPos(2, 0, 0);
    private static final DataValue.RecordValue ADDRESS = new DataValue.RecordValue(Map.of("x", number(2), "y", number(0), "z", number(0)));
    private final ComputerProgramScreenTest environment = new ComputerProgramScreenTest();
    @BeforeAll static void bootstrap() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void start() throws Exception { environment.client(); }
    @AfterEach void stop() throws Exception { environment.restore(); }

    @Test void aPlayerCanDragTheNoteBlockAndSetItsPitchWithoutInstalling() throws Exception {
        assertTrue(ScratchExtensions.binding(key("play")).isPresent(), "The Scratch note output extension is not registered");
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(FlowDocumentBody.emptySoftwareAssembly()));
        var screen = FlowCallScreenTest.editor(workspace, 640);
        click(screen, "category_sensing");
        click(screen, "sensing_output");
        ((EditBox)get(screen, "catalogSearch")).setValue("note_output play");
        assertTrue(button(screen, "extension_note_output_play").isEmpty());

        var draft = new FlowDraft(workspace.flow().orElseThrow());
        draft.setExtension(EXT, 1L);
        workspace.setFlow(Optional.of(draft.snapshot()));
        var before = workspace.snapshot();
        screen = FlowCallScreenTest.editor(workspace, 640);
        click(screen, "category_sensing");
        click(screen, "sensing_output");
        ((EditBox)get(screen, "catalogSearch")).setValue("note_output play");
        var block = button(screen, "extension_note_output_play").orElseThrow();
        var viewport = (FlowBlockCanvas)get(screen, "canvas");
        var root = viewport.joints().getFirst();
        var x = (int)get(viewport, "x") + root.x() + 8;
        var y = (int)get(viewport, "y") + root.y() + 10;
        assertTrue(screen.mouseClicked(block.getX() + 8, block.getY() + 8, 0));
        assertTrue(screen.mouseDragged(x,y,0,60,30));
        assertEquals(before, workspace.snapshot());
        assertTrue(screen.mouseReleased(x,y,0));
        var placed = workspace.snapshot();
        var call = assertInstanceOf(FlowProgram.Invoke.class, placed.flow().orElseThrow().steps().getFirst());
        assertEquals(key("play"), call.procedure());
        var operands = placed.flow().orElseThrow().inline().get(call.sourceId());
        assertEquals(Set.of("x","y","z","device","session","revision","instrument","note"), operands.outputs().keySet());
        var slot = viewport.inlineSlots().stream().filter(s -> operands.outputs().get("note").equals(s.node())).findFirst().orElseThrow();
        FlowInlineScreenTest.setLiteral(screen,slot,"19");
        var authored = workspace.snapshot();
        assertEquals(new ComputationGraph.Constant(number(19)),
            authored.flow().orElseThrow().inline().get(call.sourceId()).nodes().get(slot.node()));
        assertEquals(authored,ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(authored)));
        var hardware = StockApplicationDriverDocuments.hardware();
        assertDoesNotThrow(() -> PreparedProgram.prepare(authored, hardware));
        var withoutParser = new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits(
            hardware.hardware(), hardware.archive(), hardware.mailbox(), hardware.pins(), hardware.serial(), hardware.taskSlots(), false, hardware.isa1b());
        var parserFailure = assertThrows(FlowPins.Unavailable.class, () -> PreparedProgram.prepare(authored, withoutParser));
        assertTrue(parserFailure.getMessage().contains("ISA-1a"), parserFailure.getMessage());
        var withoutSerial = new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits(
            hardware.hardware(), hardware.archive(), hardware.mailbox(), hardware.pins(),
            com.nobodiiiii.createbiotech.content.biocomputing.hardware.SerialHardware.NONE, hardware.taskSlots(), hardware.isa1a(), hardware.isa1b());
        assertThrows(IllegalArgumentException.class, () -> PreparedProgram.prepare(authored, withoutSerial));
        assertEquals(authored, workspace.snapshot(), "Rejected installation cannot change the editable block");
        click(screen,"undo"); assertEquals(placed,workspace.snapshot());
        click(screen,"undo"); assertEquals(before,workspace.snapshot());
    }

    @Test void explicitStatusAndPlaySendOneFrameEachAndRespectTheRealMusicProtocol() {
        var hardware=StockApplicationDriverDocuments.hardware();
        var settings = new SerialRecordCodec.Limits(4096, 1048576, hardware.archive());
        var computer = new SerialDevice(hardware::serial, () -> true, () -> settings, () -> {});
        var adapter = new SerialDevice(hardware::serial, () -> true, () -> settings, () -> {});
        computer.configure("s0", Direction.EAST, true);
        adapter.configure("s0", Direction.WEST, true);
        var sender = computer.attachments().getFirst().identity();
        var devices = Map.of(BlockPos.ZERO, computer, ADAPTER, adapter);
        var network = new SerialNetwork();
        var topology = new CableDiscovery.World() {
            public boolean loaded(BlockPos pos) { return true; }
            public CableDiscovery.Cell cell(BlockPos pos) {
                return new CableDiscovery.Cell(pos.equals(new BlockPos(1,0,0)) ? CableDiscovery.Kind.CABLE
                    : devices.containsKey(pos) ? CableDiscovery.Kind.ENDPOINT : CableDiscovery.Kind.EMPTY,null);
            }
        };
        Runnable pulse = () -> network.tick(topology, devices, new CommunicationNetworkLimits(64,4),
            new SharedSerialChannel.Limits(2,4096,128,8));
        pulse.run();
        var empty = MusicOutputState.closed(UUID.randomUUID(),UUID.randomUUID());
        var granted = MusicOutputState.grant(empty,Optional.of(sender),UUID.randomUUID());
        var query = send("status", ADDRESS.fields(), computer, adapter, pulse);
        assertEquals(text("status"),query.field("operation"));
        assertEquals(new DataValue.RecordValue(Map.of()),query.field("arguments"));
        assertEquals(text("closed"),plan(empty,sender,query).reply().field("code"));
        var snapshot = plan(granted,sender,query);
        assertEquals(granted,snapshot.next());
        assertEquals(text("ok"),snapshot.reply().field("code"));
        assertEquals(query.field("request"),snapshot.reply().field("request"));
        var token = assertInstanceOf(DataValue.RecordValue.class,snapshot.reply().field("state"));
        var inputs = new LinkedHashMap<>(ADDRESS.fields());
        for(var field:List.of("device","session","revision")) inputs.put(field,token.field(field));
        inputs.put("instrument",text("flute")); inputs.put("note",number(19));
        var note = send("play",inputs,computer,adapter,pulse);
        assertEquals(text("play"),note.field("operation"));
        assertNotEquals(query.field("request"),note.field("request"));
        assertEquals(text("denied"),plan(granted,UUID.randomUUID(),note).reply().field("code"));
        var accepted = plan(granted,sender,note);
        assertEquals(text("accepted"),accepted.reply().field("code"));
        assertEquals(note.field("request"),accepted.reply().field("request"));
        assertEquals(MusicOutputState.Note.parse("flute",19),accepted.next().pending().orElseThrow());
        assertEquals("accepted",accepted.next().last(),"Queue admission is not playback");
        assertEquals(text("changed"),plan(accepted.next(),sender,note).reply().field("code"));
        var reloaded = MusicOutputState.load(MusicOutputState.save(accepted.next()),UUID.randomUUID());
        assertTrue(reloaded.pending().isEmpty()); assertEquals("interrupted",reloaded.last());
        assertEquals(text("stale_session"),plan(reloaded,sender,note).reply().field("code"));
        inputs.put("note",number(25));
        var invalid = plan(granted,sender,send("play",inputs,computer,adapter,pulse));
        assertEquals(granted,invalid.next()); assertEquals(text("invalid_note"),invalid.reply().field("code"));
    }

    private static MusicOutputProtocol.Plan plan(MusicOutputState.State before, UUID sender, DataValue.RecordValue frame) {
        return MusicOutputProtocol.plan(before,sender,ADDRESS,frame).orElseThrow();
    }

    private static DataValue.RecordValue send(String opcode, Map<String,DataValue> inputs,
            SerialDevice computer, SerialDevice adapter, Runnable pulse) {
        var hardware = StockApplicationDriverDocuments.hardware();
        var draft = new FlowDraft(FlowDocumentBody.emptySoftwareAssembly());
        draft.setExtension(EXT,1L);
        var binding=ScratchExtensions.binding(key(opcode)).orElseThrow();
        var call=draft.placeTemplate(binding.template("fixed"),new FlowDraft.Socket(null,0),new ProgramDocument.Position(16,16));
        var graph=draft.snapshot().inline().get(call);
        var nodes=new LinkedHashMap<>(graph.nodes());
        inputs.forEach((name,value)->nodes.put(Objects.requireNonNull(graph.outputs().get(name)),new ComputationGraph.Constant(value)));
        draft.editInline(call,new ComputationGraph(graph.inputs(),nodes,graph.outputs()),new FlowCallBindings(Map.of(),Map.of()));
        var workspace=new ProgramWorkspace();workspace.setFlow(Optional.of(draft.snapshot()));
        var doc=workspace.snapshot();
        var saved=ProgramDocumentWire.decode(ProgramDocumentWire.encode(doc,hardware.archive()),hardware.archive());
        var host=new ComputerBlockHost(()->{}, pin->{}, computer);
        var previous=computer.status("s0");
        host.installProgram(PreparedProgram.prepare(saved,hardware),ComputerRuntime.MemoryPolicy.RESET,hardware,true);
        assertEquals(previous,computer.status("s0"));
        host.setPaused(false,hardware,true);
        for(int tick=0;tick<600 && (host.flowSnapshot().orElseThrow().state().status()!=FlowRuntime.Status.COMPLETE
                || adapter.status("s0").receive().frames()==0);tick++) {host.tick(hardware,true);pulse.run();}
        assertEquals(FlowRuntime.Status.COMPLETE,host.flowSnapshot().orElseThrow().state().status());
        assertEquals(1,adapter.status("s0").receive().frames());
        var received=adapter.prepareReceive("s0");
        assertEquals(computer.attachments().getFirst().key(),received.source().orElseThrow());
        var frame=assertInstanceOf(DataValue.RecordValue.class,received.result().field("value"));
        assertTrue(received.publish());
        assertEquals(text("music_output_command"),frame.field("kind"));
        assertEquals(number(1),frame.field("version")); assertEquals(ADDRESS,frame.field("target"));
        assertFalse(assertInstanceOf(DataValue.TextValue.class,frame.field("request")).value().isBlank());
        for (int i=0;i<40;i++) {host.tick(hardware,true);pulse.run();}
        assertEquals(0,adapter.status("s0").receive().frames(),"No hidden repeat or retry");
        return frame;
    }

    private static ModuleDefinition.Key key(String operation){return new ModuleDefinition.Key("cb_extension:"+EXT+":"+operation,1);}
    private static DataValue.TextValue text(String value){return new DataValue.TextValue(value);}
    private static DataValue.IntegerValue number(long value){return new DataValue.IntegerValue(value);}
    private static Optional<CreateTextButton> button(ComputerFlowScreen screen,String suffix){
        return screen.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
            .filter(b->b.visible && b.getMessage().getContents() instanceof TranslatableContents t && t.getKey().endsWith("."+suffix)).findFirst();
    }
    private static void click(ComputerFlowScreen screen,String suffix){
        var b=button(screen,suffix).orElseThrow(); assertTrue(b.active,suffix);
        screen.mouseClicked(b.getX()+6,b.getY()+6,0); screen.mouseReleased(b.getX()+6,b.getY()+6,0);
    }
}
