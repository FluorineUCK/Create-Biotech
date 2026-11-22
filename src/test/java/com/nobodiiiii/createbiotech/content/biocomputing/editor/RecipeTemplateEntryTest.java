package com.nobodiiiii.createbiotech.content.biocomputing.editor;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RecipeTemplateEntryTest {
    @Test void dataPaletteExposesEditableExplicitValidationAndProjectionCustomBlocks(){
        var entries=FlowBlockCatalog.search(Map.of(),FlowBlockPalette.Category.DATA,"");
        var templates=entries.stream().filter(e->e.id().startsWith("template:recipe_")).toList();
        assertEquals(2,templates.size(),"Player Data palette must expose both recipe format custom blocks");
        for(var entry:templates){
            assertNotNull(entry.procedure());assertFalse(entry.taskStart());assertFalse(entry.taskJoin());
            assertEquals(com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource.VERSION,entry.procedure().assembly().orElseThrow().version());
            assertTrue(entry.procedure().assembly().orElseThrow().text().contains("FMT."));
        }
    }
    @Test void aRealCanvasDropImportsBothPiecesAtomicallyAndExplicitBindingsRoundtripToAnOwner(){
        var raw=FoundationRecipeTest.projection(true);
        var body=new FlowDocumentBody(Map.of("raw",DataType.Scalar.ANY),List.of(),new DataValue.RecordValue(Map.of("raw",raw)),FlowBlockLayout.DEFAULT,Map.of(),FlowProgram.Execution.REGISTER_V1);
        var draft=new FlowDraft(body);var canvas=new FlowBlockCanvas(draft,()->{});canvas.bounds(0,0,800,500);
        var entry=FlowBlockCatalog.search(Map.of(),FlowBlockPalette.Category.DATA,"recipe_validate").getFirst();
        canvas.takeTemplate(entry.procedure(),-100,-100);assertEquals(body,draft.snapshot());
        canvas.release(24,50,0);
        var imported=draft.snapshot();var call=assertInstanceOf(FlowProgram.Invoke.class,imported.steps().getFirst());
        assertTrue(call.bindings().arguments().isEmpty());assertEquals(1,FlowProcedureTools.definitions(imported).size());
        draft.undo();assertEquals(body,draft.snapshot());assertFalse(draft.canUndo());draft.redo();assertEquals(imported,draft.snapshot());
        draft.replace(call.sourceId(),new FlowProgram.Invoke(call.sourceId(),call.procedure(),new FlowCallBindings(Map.of("source","raw"),Map.of("result","checked"))));
        var workspace=new ProgramWorkspace();workspace.setFlow(Optional.of(draft.snapshot()));var document=workspace.snapshot();
        var decoded=ProgramDocumentWire.decode(ProgramDocumentWire.encode(document,FoundationRecipeTest.LIMITS),FoundationRecipeTest.LIMITS);assertEquals(document,decoded);
        var limits=com.nobodiiiii.createbiotech.content.biocomputing.hardware.ParserHardwareFixture.withMasterParser(
            new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits(new ResourceLimits(256,65536,1),FoundationRecipeTest.LIMITS));
        var owner=new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost(()->{});
        owner.installFlow(decoded.flow().orElseThrow().program(decoded.archive()),decoded.flow().orElseThrow().initial(),limits,true);
        int steps=0;while(owner.flowSnapshot().orElseThrow().state().status()!=FlowRuntime.Status.COMPLETE){
            assertTrue(++steps<2000);owner.step(limits,true);
            assertNotEquals(FlowRuntime.Status.FAILED,owner.flowSnapshot().orElseThrow().state().status(),owner.flowSnapshot().orElseThrow().state().diagnostic().toString());
            if(steps==30){var next=new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost(()->{});var save=owner.write();next.read(save);next.tick(limits,true);assertTrue(next.loaded(),next.diagnostic().toString());assertEquals(save,next.write());owner=next;}
        }
        var checked=(DataValue.RecordValue)owner.flowSnapshot().orElseThrow().state().locals().field("checked");
        assertInstanceOf(DataValue.RecipeValue.class,checked.field("value"));assertEquals(raw,((DataValue.RecipeValue)checked.field("value")).projection());
    }
    @Test void cancelInvalidConflictAndRepeatedImportsNeverLeakOrOverwriteDefinitions(){
        var draft=new FlowDraft(FlowDocumentBody.emptyAssembly());var initial=draft.snapshot();
        var template=FlowProcedureTemplates.all().getFirst();var canvas=new FlowBlockCanvas(draft,()->{});canvas.bounds(0,0,800,500);
        canvas.takeTemplate(template,-10,-10);canvas.cancel();canvas.release(24,50,0);assertEquals(initial,draft.snapshot());assertFalse(draft.canUndo());
        canvas.takeTemplate(template,-10,-10);canvas.release(-100,-100,0);assertEquals(initial,draft.snapshot());
        assertThrows(IllegalArgumentException.class,()->draft.placeTemplate(template,new FlowDraft.Socket(null,7),new ProgramDocument.Position(1,1)));assertEquals(initial,draft.snapshot());
        draft.placeTemplate(template,new FlowDraft.Socket(null,0),new ProgramDocument.Position(1,1));
        draft.placeTemplate(template,new FlowDraft.Socket(null,1),new ProgramDocument.Position(1,1));
        assertEquals(1,FlowProcedureTools.definitions(draft.snapshot()).size());assertEquals(2,draft.snapshot().steps().size());
        var old=FlowProcedureTools.definitions(draft.snapshot()).getFirst();
        var changed=new FlowProgram.Procedure(old.sourceId(),old.key(),old.inputs(),old.outputs(),old.returns(),old.rules(),
            Optional.of(new com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource(old.assembly().orElseThrow().text()+"\n; user edit")));
        draft.replace(old.sourceId(),changed);var before=draft.snapshot();
        assertThrows(IllegalArgumentException.class,()->draft.placeTemplate(template,new FlowDraft.Socket(null,0),new ProgramDocument.Position(1,1)));assertEquals(before,draft.snapshot());
        var legacy=new FlowDraft(FlowDocumentBody.empty());legacy.placeTemplate(template,null,new ProgramDocument.Position(10,10));assertEquals(FlowProgram.Execution.LEGACY_GRAPH,legacy.snapshot().execution());
    }
    @Test void fixedSignatureChoicesNeverUseFakeSamplesAndArbitrarySchemasRemainRetained(){
        for(var type:List.of(DataType.Scalar.ANY,DataType.Semantic.RECIPE,ProcedureSignatureTypes.OPTIONAL_RECIPE,ProcedureSignatureTypes.RECIPE_CHECK,DataType.Semantic.RECIPE_V2,ProcedureSignatureTypes.OPTIONAL_RECIPE_V2,ProcedureSignatureTypes.RECIPE_CHECK_V2)){
            var choice=ProcedureSignatureTypes.choice(type);assertFalse(choice.literal());assertEquals(type,ProcedureSignatureTypes.fixed(choice));
        }
        var arbitrary=new DataType.MapType(new DataType.ListType(DataType.Scalar.INTEGER));
        assertEquals(ProcedureSignatureTypes.Choice.RETAINED,ProcedureSignatureTypes.choice(arbitrary));
    }
    @Test void projectionTemplateRemainsOrdinaryEditableSourceAndRunsOnMasterWithoutParser(){
        var template=FlowProcedureTemplates.all().get(1);var raw=FoundationRecipeTest.projection(false);
        var real=new DataValue.RecipeValue("recipe",1,raw);
        var draft=new FlowDraft(new FlowDocumentBody(Map.of("real",DataType.Semantic.RECIPE),List.of(),new DataValue.RecordValue(Map.of("real",real)),FlowBlockLayout.DEFAULT,Map.of(),FlowProgram.Execution.REGISTER_V1));
        var id=draft.placeTemplate(template,new FlowDraft.Socket(null,0),new ProgramDocument.Position(1,1));
        var call=(FlowProgram.Invoke)draft.find(id).orElseThrow();draft.replace(id,new FlowProgram.Invoke(id,call.procedure(),new FlowCallBindings(Map.of("recipe","real"),Map.of("result","projected"))));
        var workspace=new ProgramWorkspace();workspace.setFlow(Optional.of(draft.snapshot()));var document=ProgramDocumentWire.decode(ProgramDocumentWire.encode(workspace.snapshot(),FoundationRecipeTest.LIMITS),FoundationRecipeTest.LIMITS);
        var base=new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits(new ResourceLimits(256,65536,1),FoundationRecipeTest.LIMITS);
        var master=new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits(base.hardware(),base.archive(),base.mailbox(),base.pins(),base.serial(),1,false,true);
        var basic=new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits(base.hardware(),base.archive(),base.mailbox(),base.pins(),base.serial(),1,true,false);
        var program=document.flow().orElseThrow().program(document.archive());
        assertThrows(FlowPins.Unavailable.class,()->FlowPins.validate(program,basic));assertDoesNotThrow(()->FlowPins.validate(program,master));
        var owner=new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost(()->{});
        owner.installFlow(program,document.flow().orElseThrow().initial(),master,true);
        for(int i=0;owner.flowSnapshot().orElseThrow().state().status()!=FlowRuntime.Status.COMPLETE;i++){assertTrue(i<100);owner.step(master,true);}
        assertEquals(raw,owner.flowSnapshot().orElseThrow().state().locals().field("projected"));assertEquals(real,owner.flowSnapshot().orElseThrow().state().locals().field("real"));
    }
    @Test void publicEditorWirePreservesNestedRecipeAndNativeSharedReferencesWithoutSpoofing(){
        var raw=FoundationRecipeTest.projection(false);var recipe=new DataValue.RecipeValue("recipe",1,raw);
        var nativeData=new DataValue.NbtValue(10,new DataValue.RecordValue(Map.of("negative_zero",new DataValue.NbtValue(6,new DataValue.IntegerValue(Long.MIN_VALUE)))));
        var nested=new DataValue.RecordValue(Map.of("recipe",recipe,"native",nativeData,"raw",raw,
            "shared",new DataValue.ListValue(List.of(recipe,nativeData,recipe,nativeData))));
        var restored=(DataValue.RecordValue)ProgramEditorWire.decodeValue(ProgramEditorWire.encodeValue(nested,FoundationRecipeTest.LIMITS),FoundationRecipeTest.LIMITS);
        assertEquals(nested,restored);assertInstanceOf(DataValue.RecipeValue.class,restored.field("recipe"));
        assertInstanceOf(DataValue.RecordValue.class,restored.field("raw"));assertEquals(nativeData,restored.field("native"));
        assertEquals(raw,((DataValue.RecipeValue)restored.field("recipe")).projection());
    }
}
