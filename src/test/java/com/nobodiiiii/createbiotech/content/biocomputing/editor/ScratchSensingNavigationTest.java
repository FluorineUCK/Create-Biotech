package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerFlowScreenTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.foundation.gui.CanvasViewport;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.Screen;
import org.junit.jupiter.api.*;

class ScratchSensingNavigationTest {
    private final ComputerProgramScreenTest client = new ComputerProgramScreenTest();
    @BeforeAll static void bootstrap() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void openClient() throws Exception { client.client(); }
    @AfterEach void closeClient() throws Exception { client.restore(); }


    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={320,426,640})
    void outputPreviewReturnsToSameEditorButExplicitImportReopensTheNewDraft(int width) throws Exception {
        var fixture=screen();var menu=ComputerFlowScreenTest.readyMenu(fixture);set(menu,"controlResponse",Optional.empty());
        var hub=new ComputerScratchScreen(menu,(net.minecraft.world.entity.player.Inventory)get(fixture,"editorInventory"),net.minecraft.network.chat.Component.empty());
        var nav=new AtomicReference<Screen>();set(hub,"navigator",(Consumer<Screen>)nav::set);
        ComputerScratchNavigationTest.initialize(hub);
        set(hub,"requested",true);set(menu,"responseVersion",1L);hub.containerTick();
        var workspace=new ProgramWorkspace();var draft=new FlowDraft(FlowDocumentBody.emptyAssembly());
        draft.initial(new DataValue.RecordValue(Map.of("ticks",new DataValue.IntegerValue(0))));workspace.setFlow(Optional.of(draft.snapshot()));
        hub.importDocument(workspace.snapshot());
        click(hub,"flow");var editor=assertInstanceOf(ComputerFlowScreen.class,nav.get());
        set(editor,"navigator",(Consumer<Screen>)nav::set);FlowProcedureScreenTest.initializeForm(editor,width);
        ComputerFlowScreenTest.click(editor,"add_sleep");ComputerFlowScreenTest.boxes(editor).getFirst().setValue("ticks");click(editor,"apply");var before=((ProgramWorkspace)get(hub,"workspace")).snapshot();
        for(String old:List.of("category_readers","category_communication","category_output"))
            assertFalse(editor.children().stream().filter(net.minecraft.client.gui.components.AbstractWidget.class::isInstance)
                .map(net.minecraft.client.gui.components.AbstractWidget.class::cast).anyMatch(b->b.getMessage().getContents()
                    instanceof net.minecraft.network.chat.contents.TranslatableContents t && t.getKey().endsWith("."+old)),old);
        click(editor,"category_sensing");click(editor,"sensing_output");click(editor,"sensing_examples");
        var page=assertInstanceOf(ComputerProgramDocumentScreen.class,nav.get());
        set(page,"exampleResourceOpener",(java.util.function.Function<net.minecraft.resources.ResourceLocation,java.io.InputStream>)id->
            getClass().getClassLoader().getResourceAsStream("assets/"+id.getNamespace()+"/"+id.getPath()));
        initializePage(page,width);
        assertEquals(Optional.of(FlowBlockCatalog.SensingSection.OUTPUT),get(page,"sensingFilter"));
        var search=page.children().stream().filter(net.minecraft.client.gui.components.EditBox.class::isInstance)
            .map(net.minecraft.client.gui.components.EditBox.class::cast).findFirst().orElseThrow();
        search.setValue("narrator_output_a");click(page,"example.narrator_output.title");
        assertEquals(before,((ProgramWorkspace)get(hub,"workspace")).snapshot());
        page.onClose();assertSame(editor,nav.get());
        assertTrue(button(editor,"undo").active,"Preview keeps the original editor's undo history");
        click(editor,"sensing_examples");
        page=assertInstanceOf(ComputerProgramDocumentScreen.class,nav.get());
        set(page,"exampleResourceOpener",(java.util.function.Function<net.minecraft.resources.ResourceLocation,java.io.InputStream>)id->
            getClass().getClassLoader().getResourceAsStream("assets/"+id.getNamespace()+"/"+id.getPath()));
        initializePage(page,width);
        page.children().stream().filter(net.minecraft.client.gui.components.EditBox.class::isInstance)
            .map(net.minecraft.client.gui.components.EditBox.class::cast).findFirst().orElseThrow().setValue("narrator_output_a");
        click(page,"example.narrator_output.title");click(page,"example_replace");var imported=((ProgramWorkspace)get(hub,"workspace")).snapshot();
        assertNotEquals(before,imported);page.onClose();
        var reopened=assertInstanceOf(ComputerFlowScreen.class,nav.get());assertNotSame(editor,reopened);
        assertEquals(imported,((ProgramWorkspace)get(reopened,"workspace")).snapshot());
        assertTrue(menu.controlResponse().isEmpty(),"No run/reset/install action is issued by browsing or importing");
    }

    private static com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton button(Screen screen,String key) {
        return screen.children().stream().filter(com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton.class::isInstance)
            .map(com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton.class::cast)
            .filter(b->b.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t
                && t.getKey().endsWith("."+key)).findFirst().orElseThrow(()->new AssertionError("Missing control "+key));
    }
    private static void click(Screen screen,String key) { var b=button(screen,key);assertTrue(b.active);b.onClick(0,0); }

    private static void initializePage(ComputerProgramDocumentScreen page,int width) throws Exception {
        set(page,"width",width);set(page,"height",260);set(page,"minecraft",net.minecraft.client.Minecraft.getInstance());
        set(page,"font",new Metrics() {
            @Override public List<net.minecraft.util.FormattedCharSequence> split(net.minecraft.network.chat.FormattedText text,int width) {
                var lines=new ArrayList<net.minecraft.util.FormattedCharSequence>();var value=text.getString();int count=Math.max(1,width/6);
                for(int start=0;start<value.length();start+=count) lines.add(net.minecraft.util.FormattedCharSequence.forward(
                    value.substring(start,Math.min(value.length(),start+count)),net.minecraft.network.chat.Style.EMPTY));
                return lines;
            }
        });page.init();
    }
}
