package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerFlowScreenTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.*;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LocalReferenceTitleTest {
    private final ComputerProgramScreenTest environment=new ComputerProgramScreenTest();
    @BeforeAll static void bootstrap()throws Exception{ComputerProgramScreenTest.bootstrap();}
    @BeforeEach void client()throws Exception{environment.client(); set(net.minecraft.client.Minecraft.getInstance(), "guiSprites", allocate(FlowInputScreenTest.SilentSprites.class));}
    @AfterEach void restore()throws Exception{environment.restore();}

    @ParameterizedTest @CsvSource({"en_us,320","en_us,426","en_us,640","zh_cn,320","zh_cn,426","zh_cn,640"})
    void actualReferenceWriteCanvasShowsOperationAndTargetWithoutChangingNextInput(String locale,int width)throws Exception{
        var old=net.minecraft.locale.Language.getInstance();
        try{
            var translations=ComputerUiTranslationsTest.load(locale);
            net.minecraft.locale.Language.inject(ComputerUiTranslationsTest.language(translations));
            var workspace=new ProgramWorkspace();workspace.setFlow(Optional.of(FlowDocumentBody.emptySoftwareAssembly()));
            var editor=FlowCallScreenTest.editor(workspace,width);
            click(editor,"category_storage");click(editor,"declare_cell");boxes(editor).get(0).setValue("counter");boxes(editor).get(1).setValue("7");click(editor,"save_initial");click(editor,"back");
            click(editor,"category_variables");FlowPaletteScreenTest.search(editor).setValue("ref_address");click(editor,"add_ref_address");boxes(editor).get(0).setValue("counter");boxes(editor).get(1).setValue("value");click(editor,"apply");
            FlowPaletteScreenTest.search(editor).setValue("ref_read");click(editor,"add_ref_read");boxes(editor).get(0).setValue("value");boxes(editor).get(1).setValue("next");click(editor,"apply");
            FlowPaletteScreenTest.search(editor).setValue("ref_write");click(editor,"add_ref_write");boxes(editor).get(0).setValue("value");boxes(editor).get(1).setValue("next");bounds(editor);click(editor,"apply");
            var before=workspace.snapshot();var write=assertInstanceOf(FlowProgram.Reference.class,before.flow().orElseThrow().steps().getLast());
            assertEquals(FlowProgram.ReferenceKind.WRITE,write.kind());assertEquals(Optional.of(new FlowInput.Local("next")),write.value());
            var canvas=(FlowBlockCanvas)get(editor,"canvas");canvas.focus(write.sourceId());var labels=render(canvas);
            String expected=locale.equals("zh_cn")?"通过引用 value 更新变量":"Update through reference value";
            assertTrue(labels.contains(expected),()->"Actual drawString captions: "+labels);
            assertFalse(labels.contains(translations.get("create_biotech.biocomputing.flow_editor.inline_if")));
            assertTrue(labels.contains("next"),"Input tile remains rendered");
            assertTrue(canvas.inlineSlots().stream().anyMatch(slot->slot.call().equals(write.sourceId())&&!slot.target()&&slot.label().equals("next")));
            assertEquals(before,workspace.snapshot(),"Rendering never rewrites source or bindings");
        }finally{net.minecraft.locale.Language.inject(old);}
    }

    @ParameterizedTest @CsvSource({"en_us,320","en_us,426","en_us,640","zh_cn,320","zh_cn,426","zh_cn,640"})
    void existingIfRepeatUntilInlineCaptionsRemainUnchanged(String locale,int width)throws Exception{
        var old=net.minecraft.locale.Language.getInstance();
        try{
            var translations=ComputerUiTranslationsTest.load(locale);net.minecraft.locale.Language.inject(ComputerUiTranslationsTest.language(translations));
            for(String kind:List.of("if","repeat","until")){
                var steps=switch(kind){
                    case "if"->List.<FlowProgram.Step>of(new FlowProgram.If("condition",new FlowInput.Local("flag")),new FlowProgram.EndIf("end"));
                    case "repeat"->List.<FlowProgram.Step>of(new FlowProgram.Repeat("count",new FlowInput.Local("times")),new FlowProgram.EndRepeat("end"));
                    default->List.<FlowProgram.Step>of(new FlowProgram.Until("condition",new FlowInput.Local("flag")),new FlowProgram.EndUntil("end"));
                };
                var body=new FlowDocumentBody(Map.of("flag",DataType.Scalar.BOOLEAN,"times",DataType.Scalar.INTEGER),steps,new DataValue.RecordValue(Map.of("flag",new DataValue.BooleanValue(true),"times",new DataValue.IntegerValue(2))));
                var workspace=new ProgramWorkspace();workspace.setFlow(Optional.of(body));var editor=FlowCallScreenTest.editor(workspace,width);var before=workspace.snapshot();
                assertTrue(render((FlowBlockCanvas)get(editor,"canvas")).contains(translations.get("create_biotech.biocomputing.flow_editor.inline_"+kind)));
                assertEquals(before,workspace.snapshot());
            }
        }finally{net.minecraft.locale.Language.inject(old);}
    }

    private static List<String> render(FlowBlockCanvas canvas)throws Exception{
        var graphics=allocate(Captions.class);var labels=new ArrayList<String>();set(graphics,"labels",labels);
        canvas.render(graphics,net.minecraft.client.Minecraft.getInstance().font,ComputerFlowScreen::blockLabel,Component.literal("main"),Component.literal("parked"));
        return labels;
    }
    /** Run the real canvas renderer; suppress only final GPU submission and capture both text overloads. */
    private static final class Captions extends net.minecraft.client.gui.GuiGraphics {
        private List<String> labels;
        private static final com.mojang.blaze3d.vertex.PoseStack POSE=new com.mojang.blaze3d.vertex.PoseStack();
        private Captions(){super(null,null);}
        @Override public com.mojang.blaze3d.vertex.PoseStack pose(){return POSE;}
        @Override public void enableScissor(int x,int y,int right,int bottom){}
        // Capture captions/layout without submitting the new nine-slice atlas to a GPU.
        @Override public void blit(net.minecraft.resources.ResourceLocation texture, int x, int y, int width, int height,
                float u, float v, int sourceWidth, int sourceHeight, int textureWidth, int textureHeight) {
            assertTrue(width > 0 && height > 0);
        }
        @Override public void blitSprite(net.minecraft.resources.ResourceLocation id, int x, int y, int width, int height) {}
        @Override public void disableScissor(){}
        @Override public void fill(int x,int y,int right,int bottom,int color){}
        @Override public int drawString(net.minecraft.client.gui.Font font,Component text,int x,int y,int color,boolean shadow){labels.add(text.getString());return 0;}
        @Override public int drawString(net.minecraft.client.gui.Font font,String text,int x,int y,int color,boolean shadow){labels.add(text);return 0;}
    }
}
