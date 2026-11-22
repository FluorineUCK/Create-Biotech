package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.get;
import static org.junit.jupiter.api.Assertions.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;

import com.nobodiiiii.createbiotech.content.biocomputing.device.localtext.ScratchSpeakerProtocolFixture;
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

class ScratchSpeakerMediaExtensionTest {
    private static final String EXT = "create_biotech:speaker_media";
    private static final BlockPos ADAPTER = new BlockPos(2, 0, 0);
    private static final DataValue.RecordValue ADDRESS = new DataValue.RecordValue(Map.of("x", number(2), "y", number(0), "z", number(0)));
    private final ComputerProgramScreenTest environment = new ComputerProgramScreenTest();
    @BeforeAll static void bootstrap() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void start() throws Exception { environment.client(); }
    @AfterEach void stop() throws Exception { environment.restore(); }

    @Test void aPlayerCanDropAnExistingTypedReferenceIntoThePlayBlock() throws Exception {
        assertTrue(ScratchExtensions.binding(key("play")).isPresent(), "The Scratch speaker extension is not registered");
        var value = ScratchSpeakerProtocolFixture.reference();
        var seed = FlowDocumentBody.emptySoftwareAssembly();
        var draft = new FlowDraft(new FlowDocumentBody(Map.of("saved_media", DataType.typeOf(value)), seed.steps(),
            new DataValue.RecordValue(Map.of("saved_media", value)), seed.canvas(), seed.inline(), seed.execution(), seed.compilation()));
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(draft.snapshot()));
        var screen = FlowCallScreenTest.editor(workspace, 640);
        click(screen,"category_sensing"); click(screen,"sensing_output");
        ((EditBox)get(screen,"catalogSearch")).setValue("speaker_media play");
        assertTrue(button(screen,"extension_speaker_media_play").isEmpty(), "Extension is opt-in");
        draft.setExtension(EXT,1L);
        workspace.setFlow(Optional.of(draft.snapshot()));
        screen = FlowCallScreenTest.editor(workspace,640);
        click(screen,"category_sensing"); click(screen,"sensing_output");
        ((EditBox)get(screen,"catalogSearch")).setValue("speaker_media play");
        var palette = button(screen,"extension_speaker_media_play").orElseThrow();
        var canvas = (FlowBlockCanvas)get(screen,"canvas");
        var joint = canvas.joints().getFirst();
        double x = (int)get(canvas,"x") + joint.x()+8, y = (int)get(canvas,"y")+joint.y()+10;
        drag(screen,palette,x,y);
        var placed = workspace.snapshot();
        var call = assertInstanceOf(FlowProgram.Invoke.class, placed.flow().orElseThrow().steps().getFirst());
        assertEquals(key("play"), call.procedure());
        var operands = placed.flow().orElseThrow().inline().get(call.sourceId());
        assertEquals(Set.of("x","y","z","device","session","revision","reference"),operands.outputs().keySet());
        ((EditBox)get(screen,"catalogSearch")).setValue("saved_media");
        var reference = screen.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
            .filter(b -> b.getMessage().getContents() instanceof TranslatableContents t
                && Arrays.stream(t.getArgs()).anyMatch(arg -> arg.toString().equals("saved_media"))).findFirst().orElseThrow();
        var slot = canvas.inlineSlots().stream().filter(s -> operands.outputs().get("reference").equals(s.node())).findFirst().orElseThrow();
        var dropX = slot.x()+8; var dropY = slot.y()+slot.height()/2;
        assertTrue(canvas.contains(dropX,dropY), () -> "Record slot is outside reach: " + slot);
        assertEquals(slot, canvas.inlineHit(dropX,dropY));
        drag(screen,reference,dropX,dropY);
        var attached = workspace.snapshot();
        var attachedCall = assertInstanceOf(FlowProgram.Invoke.class, attached.flow().orElseThrow().steps().getFirst());
        var attachedGraph = attached.flow().orElseThrow().inline().get(attachedCall.sourceId());
        var input = assertInstanceOf(ComputationGraph.Input.class, attachedGraph.nodes().get(attachedGraph.outputs().get("reference")));
        assertEquals("saved_media", attachedCall.operands().orElseThrow().inputs().get(input.name()),
            "Use the existing typed value, not a pasted UUID");
        assertEquals(attached,ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(attached)));
        assertDoesNotThrow(() -> PreparedProgram.prepare(attached,StockApplicationDriverDocuments.hardware()));
        click(screen,"undo"); assertEquals(placed,workspace.snapshot());
    }

    private static void drag(ComputerFlowScreen screen, CreateTextButton source, double x, double y) {
        assertTrue(screen.mouseClicked(source.getX()+8,source.getY()+8,0));
        screen.mouseDragged(x,y,0,60,30);
        assertTrue(screen.mouseReleased(x,y,0));
    }

    @Test void queryPlayAndStopUseExistingSpeakerFrames() {
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
        var query = send("status", ADDRESS.fields(), computer, adapter, pulse);
        var grant = ScratchSpeakerProtocolFixture.inspect(sender, ADDRESS, query);
        var stop = new LinkedHashMap<>(ADDRESS.fields()); stop.putAll(grant.token());
        var play = new LinkedHashMap<>(stop); play.put("reference",ScratchSpeakerProtocolFixture.reference());
        var playFrame = send("play", play, computer, adapter, pulse);
        var stopFrame = send("stop", stop, computer, adapter, pulse);
        assertEquals(3,Set.of(query.field("request"),playFrame.field("request"),stopFrame.field("request")).size());
        ScratchSpeakerProtocolFixture.verify(grant, ADDRESS, playFrame, stopFrame);
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
        assertEquals(text("speaker_media_command"),frame.field("kind"));
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
