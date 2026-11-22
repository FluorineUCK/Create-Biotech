package com.nobodiiiii.createbiotech.content.biocomputing.editor;
import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.*;
import java.util.*;
import java.util.function.BiConsumer;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
class TerminalKeyWidgetsTest {
    private final ComputerProgramScreenTest fixture=new ComputerProgramScreenTest();
    @org.junit.jupiter.api.BeforeAll static void bootstrap() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @org.junit.jupiter.api.BeforeEach void client() throws Exception {
        fixture.client();
        var options = allocate(net.minecraft.client.Options.class);
        set(options, "keyInventory", new net.minecraft.client.KeyMapping("test.terminal.inventory", GLFW.GLFW_KEY_E, "test.terminal"));
        set(options, "keyDrop", new net.minecraft.client.KeyMapping("test.terminal.drop", GLFW.GLFW_KEY_Q, "test.terminal"));
        set(options, "keyPickItem", new net.minecraft.client.KeyMapping("test.terminal.pick", GLFW.GLFW_KEY_F, "test.terminal"));
        set(net.minecraft.client.Minecraft.getInstance(), "options", options);
    }
    @org.junit.jupiter.api.AfterEach void restore() throws Exception { fixture.restore(); }
    @org.junit.jupiter.api.Test
    void acknowledgementRefreshesPublicationWithoutReplayingHeldKeyAndPauseDisarms() throws Exception {
        var parent=screen();var menu=(ComputerProgramMenu)get(parent,"menu");var nonce=UUID.randomUUID();
        var limits=new com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits(10000,1000000,10000000);
        set(menu,"nonce",nonce);set(menu,"response",Optional.of(new ProgramEditorResponse(0,nonce,1,7,"ready","",
            new ResourceLimits(1000,1000000,1),limits,new byte[0])));
        set(net.minecraft.client.Minecraft.getInstance(),"windowActive",true);
        var viewer=new ComputerTerminalScreen(menu,(net.minecraft.world.entity.player.Inventory)get(parent,"editorInventory"),Component.empty(),parent);
        var actions=new ArrayList<ComputerControlSession.Action>();var keys=new ArrayList<TerminalKeyPress>();
        java.util.function.Consumer<ComputerControlSession.Action> send=action->{
            actions.add(action);try{set(menu,"latestControlIntent",(long)actions.size());set(menu,"latestControlAction",action);}catch(Exception failure){throw new AssertionError(failure);}
        };
        var samples=new ArrayList<Map.Entry<Long,Long>>();
        set(viewer,"keyStateRequest",(BiConsumer<Long,Long>)((view,bits)->samples.add(Map.entry(view,bits))));
        set(viewer,"request",send);set(viewer,"keyRequest",(BiConsumer<Long,String>)((view,key)->{keys.add(new TerminalKeyPress(view,key));send.accept(ComputerControlSession.Action.TERMINAL_KEY);}));
        TerminalControlsTest.init(viewer,320);viewer.containerTick();
        var observation=new LinkedHashMap<String,DataValue>();observation.put("terminal",DataValue.NullValue.INSTANCE);
        observation.put("memory",new DataValue.RecordValue(Map.of()));observation.put("output",DataValue.NullValue.INSTANCE);
        observation.put("tasks",new DataValue.ListValue(List.of()));observation.put("phase",new DataValue.TextValue("COMPLETE"));
        observation.put("paused",new DataValue.BooleanValue(false));observation.put("keyboard",new DataValue.BooleanValue(true));
        menu.accept(new ProgramStatusResponse(0,nonce,1,7,"ok","",ProgramEditorWire.encodeValue(new DataValue.RecordValue(observation),limits)));viewer.containerTick();
        TerminalControlsTest.click(viewer,"keyboard_off");viewer.keyPressed(GLFW.GLFW_KEY_A,0,0);
        assertEquals(List.of(new TerminalKeyPress(1,"a")),keys);
        assertEquals(List.of(Map.entry(1L,TerminalKey.bit("a"))),samples);
        menu.accept(new ProgramStatusResponse(0,nonce,2,7,"ok","",ProgramEditorWire.encodeValue(new DataValue.RecordValue(Map.of("terminal_key",new DataValue.TextValue("ok"))),limits)));viewer.containerTick();
        assertEquals(List.of(ComputerControlSession.Action.READ_TERMINAL,ComputerControlSession.Action.TERMINAL_KEY,ComputerControlSession.Action.READ_TERMINAL),actions);
        assertTrue((boolean)get(viewer,"requestPending"));
        viewer.keyPressed(GLFW.GLFW_KEY_A,0,0);assertEquals(1,keys.size());
        menu.accept(new ProgramStatusResponse(0,nonce,3,7,"ok","",ProgramEditorWire.encodeValue(new DataValue.RecordValue(observation),limits)));viewer.containerTick();
        assertFalse((boolean)get(viewer,"requestPending"));assertEquals(3L,get(viewer,"publishedView"));
        viewer.keyPressed(GLFW.GLFW_KEY_A,0,0);assertEquals(1,keys.size());
        viewer.keyReleased(GLFW.GLFW_KEY_A,0,0);viewer.keyPressed(GLFW.GLFW_KEY_A,0,0);
        assertEquals(List.of(new TerminalKeyPress(1,"a"),new TerminalKeyPress(3,"a")),keys);
        menu.accept(new ProgramStatusResponse(0,nonce,4,7,"busy","paused",new byte[0]));viewer.containerTick();
        observation.put("paused",new DataValue.BooleanValue(true));
        menu.accept(new ProgramStatusResponse(0,nonce,5,7,"ok","",ProgramEditorWire.encodeValue(new DataValue.RecordValue(observation),limits)));viewer.containerTick();
        assertFalse((boolean)get(viewer,"keyboardArmed"));assertTrue(((Set<?>)get(viewer,"keysDown")).isEmpty());
        assertFalse(TerminalControlsTest.button(viewer,"keyboard_off").active);
        assertEquals(0L,samples.getLast().getValue());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={320,426,640})
    void terminalRequiresArmingIgnoresHeldRepeatsAndLeavesTextInputLocal(int width) throws Exception {
        var parent=screen();var menu=(ComputerProgramMenu)get(parent,"menu");
        var viewer=new ComputerTerminalScreen(menu,(net.minecraft.world.entity.player.Inventory)get(parent,"editorInventory"),Component.empty(),parent);
        var roots=new DataValue.RecordValue(Map.of("memory",new DataValue.RecordValue(Map.of()),"output",DataValue.NullValue.INSTANCE,"tasks",new DataValue.ListValue(List.of())));
        set(viewer,"snapshot",new TerminalStatusView(Optional.empty(),roots,"COMPLETE",false,true));
        set(viewer,"publishedView",7L);set(viewer,"requested",true);
        var sent=new ArrayList<TerminalKeyPress>();
        set(viewer,"keyRequest",(BiConsumer<Long,String>)((view,key)->sent.add(new TerminalKeyPress(view,key))));
        TerminalControlsTest.init(viewer,width);TerminalControlsTest.bounds(viewer);
        var noticeBounds=ComputerTerminalScreen.class.getDeclaredMethod("noticeBounds");noticeBounds.setAccessible(true);
        var notice=(net.minecraft.client.renderer.Rect2i)noticeBounds.invoke(viewer);
        assertTrue(notice.getWidth()>0);
        for(var child:viewer.children()) if(child instanceof net.minecraft.client.gui.components.AbstractWidget widget)
            assertFalse(notice.getX()<widget.getX()+widget.getWidth() && notice.getX()+notice.getWidth()>widget.getX()
                && notice.getY()<widget.getY()+widget.getHeight() && notice.getY()+notice.getHeight()>widget.getY(),
                "Status text must not paint over "+widget.getMessage().getString());
        viewer.keyPressed(GLFW.GLFW_KEY_A,0,0);assertTrue(sent.isEmpty());
        TerminalControlsTest.click(viewer,"keyboard_off");
        assertTrue(viewer.keyPressed(GLFW.GLFW_KEY_A,0,0));assertEquals(List.of(new TerminalKeyPress(7,"a")),sent);
        assertTrue(viewer.keyPressed(GLFW.GLFW_KEY_A,0,0));assertEquals(1,sent.size());
        assertTrue((boolean)get(viewer,"requestPending"));assertTrue((boolean)get(viewer,"clickLocked"));
        viewer.keyReleased(GLFW.GLFW_KEY_A,0,0);
        set(viewer,"requestPending",false);set(viewer,"clickLocked",false);set(viewer,"publishedView",8L);
        var field=new EditBox(net.minecraft.client.Minecraft.getInstance().font,10,90,80,20,Component.empty());
        viewer.setFocused(field);field.setFocused(true);viewer.keyPressed(GLFW.GLFW_KEY_B,0,0);viewer.charTyped('b',0);
        assertEquals(1,sent.size());assertEquals("b",field.getValue());
        viewer.setFocused(null);viewer.keyPressed(GLFW.GLFW_KEY_B,0,0);
        assertEquals(List.of(new TerminalKeyPress(7,"a"),new TerminalKeyPress(8,"b")),sent);
        viewer.removed();assertFalse((boolean)get(viewer,"keyboardArmed"));assertTrue(((Set<?>)get(viewer,"keysDown")).isEmpty());
    }
}
