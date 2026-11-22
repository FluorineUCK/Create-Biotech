package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.get;
import static org.junit.jupiter.api.Assertions.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.device.localtext.NarratorScratchProtocolFixture;
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

class ScratchNarratorOutputExtensionTest {
    private static final String EXT = "create_biotech:narrator_output";
    private static final BlockPos ADAPTER = new BlockPos(2, 0, 0);
    private static final DataValue.RecordValue ADDRESS = new DataValue.RecordValue(Map.of("x", number(2), "y", number(0), "z", number(0)));
    private final ComputerProgramScreenTest environment = new ComputerProgramScreenTest();
    @BeforeAll static void bootstrap() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void start() throws Exception { environment.client(); }
    @AfterEach void stop() throws Exception { environment.restore(); }

    @Test void aPlayerCanDragTheNarratorBlockAndSetLiteralTextWithoutInstalling() throws Exception {
        assertTrue(ScratchExtensions.binding(key("speak")).isPresent(), "The Scratch narrator output extension is not registered");
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(FlowDocumentBody.emptySoftwareAssembly()));
        var screen = FlowCallScreenTest.editor(workspace, 640);
        click(screen, "category_sensing");
        click(screen, "sensing_output");
        ((EditBox)get(screen, "catalogSearch")).setValue("narrator_output speak");
        assertTrue(button(screen, "extension_narrator_output_speak").isEmpty());

        var draft = new FlowDraft(workspace.flow().orElseThrow());
        draft.setExtension(EXT, 1L);
        workspace.setFlow(Optional.of(draft.snapshot()));
        var before = workspace.snapshot();
        screen = FlowCallScreenTest.editor(workspace, 640);
        click(screen, "category_sensing");
        click(screen, "sensing_output");
        ((EditBox)get(screen, "catalogSearch")).setValue("narrator_output speak");
        var block = button(screen, "extension_narrator_output_speak").orElseThrow();
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
        assertEquals(key("speak"), call.procedure());
        var operands = placed.flow().orElseThrow().inline().get(call.sourceId());
        assertEquals(Set.of("x","y","z","device","session","revision","text"), operands.outputs().keySet());
        var slot = viewport.inlineSlots().stream().filter(s -> operands.outputs().get("text").equals(s.node())).findFirst().orElseThrow();
        FlowInlineScreenTest.setLiteral(screen,slot,"线路在线");
        var authored = workspace.snapshot();
        assertEquals(new ComputationGraph.Constant(text("线路在线")),
            authored.flow().orElseThrow().inline().get(call.sourceId()).nodes().get(slot.node()));
        assertEquals(authored,ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(authored)));
        assertDoesNotThrow(() -> PreparedProgram.prepare(authored,StockApplicationDriverDocuments.hardware()));
        click(screen,"undo"); assertEquals(placed,workspace.snapshot());
        click(screen,"undo"); assertEquals(before,workspace.snapshot());
    }

    @Test void queryAndSpeakUseOneRealFrameAndRetainNarrationAuthorizationAndLimits() {
        var hardware = StockApplicationDriverDocuments.hardware();
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
        var status = send("status", ADDRESS.fields(), computer, adapter, pulse);
        assertEquals(text("status"), status.field("operation"));
        assertEquals(new DataValue.RecordValue(Map.of()), status.field("arguments"));
        var grants = NarratorScratchProtocolFixture.start(sender, ADDRESS, status);
        var inputs = new LinkedHashMap<>(ADDRESS.fields());
        inputs.putAll(grants.token());
        inputs.put("text",text("北站准备就绪 · Speaker ready"));
        var message = send("speak", inputs, computer, adapter, pulse);
        assertEquals(text("speak"), message.field("operation"));
        assertNotEquals(status.field("request"),message.field("request"));
        NarratorScratchProtocolFixture.verify(grants, ADDRESS, message, "北站准备就绪 · Speaker ready");
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
        assertEquals(text("narrator_output_command"),frame.field("kind"));
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
