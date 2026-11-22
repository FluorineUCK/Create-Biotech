package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.record;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.RecipeBrowserDocumentTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** These fixtures author portable programs, not runtime stock or menu handlers. */
class RecipeBrowserOrderDocumentsTest {
    static final String PICK="recipe_browser_pick", CHECK="recipe_browser_checkout", CONFIRM="recipe_browser_confirm",
        DISPATCH="recipe_browser_dispatch", ROUTE="recipe_browser_route";
    static final DataType REF=type(Map.of("k",TEXT,"page",INTEGER,"fingerprint",TEXT,"source",NBT));
    static final DataType ORDER=type(Map.of("s",TEXT,"d",REF,"n",INTEGER));
    static final DataType DRAFT=type(Map.of("request",ORDER,"o",ANY,"c",TEXT));
    static DataType type(Map<String,DataType> fields){return new DataType.RecordType(fields);}
    static ModuleDefinition.Key key(String id){return new ModuleDefinition.Key(id,1);}
    static FlowProgram.Procedure def(String id,Map<String,DataType> in,Map<String,DataType> out) throws Exception {
        var regs=new TreeMap<String,String>();out.keySet().forEach(s->regs.put(s,s));
        return new FlowProgram.Procedure(id+"_def",key(id),in,out,regs,FlowBlockRules.NONE,
            Optional.of(new AssemblySource(Files.readString(EXAMPLES.resolve(id+".asm")))));
    }
    static FlowProgram.Invoke call(String id,List<String> in,List<String> out){
        var a=new TreeMap<String,String>();var b=new TreeMap<String,String>();in.forEach(k->a.put(k,k));out.forEach(k->b.put(k,k));
        return new FlowProgram.Invoke("call_"+id,key(id),new FlowCallBindings(a,b));
    }
    static TerminalBinding.Path at(String root,String... fields){
        var path=new ValuePath(root,List.of());for(var f:fields)path=path.key(f);return new TerminalBinding.Path(path);
    }
    static TerminalBinding.Literal literal(String s){return new TerminalBinding.Literal(new DataValue.TextValue(s));}
    static void publish(String id,ProgramWorkspace w) throws Exception {
        var doc=w.snapshot();System.out.println(id+" "+doc.flow().orElseThrow().program(doc.archive()).usage(new ResourceLimits(2048,65536,8)));
        checkOrWrite(id,doc);
    }
    @Test void selectionTerminal() throws Exception {
        var base=published(TERMINAL);var body=base.flow().orElseThrow();
        var steps=new ArrayList<>(body.program(base.archive()).steps());
        var fields=new TreeMap<String,DataType>(Map.of("page",INTEGER,"pages",INTEGER,"pattern_id",TEXT,"type",TEXT,"priority",ANY,
            "inputs",ANY,"body",ANY,"outputs",ANY,"fingerprint",TEXT,"source",NBT));
        fields.put("batch_text",TEXT);
        steps.add(def(PICK,fields,Map.of("code",TEXT)));steps.add(new FlowProgram.EndProcedure("end_pick"));
        var w=new ProgramWorkspace(base);
        w.setFlow(Optional.of(FlowDocumentBody.fromProgram(new FlowProgram(body.inputs(),steps).withAssembly(),body.initial())));
        var terminal=base.terminal().orElseThrow();var nodes=new LinkedHashMap<>(terminal.nodes());
        nodes.put("recipe",new TerminalDefinition.Column(List.of("description","batch_help","batches")));
        nodes.put("description",new TerminalDefinition.Row(List.of("icon","id","details")));
        nodes.put("batch_help",new TerminalDefinition.Text(literal("份数 → 送核对台，不发货 / Batches → stage only")));
        nodes.put("batches",new TerminalDefinition.Input(literal("1"),new TerminalBinding.Literal(new DataValue.BooleanValue(true)),
            new TerminalTarget.Task(key(PICK)),"batch_text",at("row")));
        w.setTerminal(Optional.of(new TerminalDefinition(terminal.root(),nodes)));publish(PICK,w);
    }
    @Test void confirmationDesk() throws Exception {
        var empty=record("request",record("s","","d",record("k","","page",0,"fingerprint","","source",new DataValue.NbtValue(10,record())),
            "n",0),"o",new DataValue.ListValue(List.of()),"c","idle");
        var cells=Map.of("current",new ProgramDefinition.Cell(DRAFT,empty),"last_action",new ProgramDefinition.Cell(TEXT,new DataValue.TextValue("idle")));
        var archive=new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(),Map.of(),Map.of()),cells,List.of()),Map.of());
        var steps=List.<FlowProgram.Step>of(call(CHECK,List.of(),List.of("draft","ready")),
            new FlowProgram.Shared("display","current",new FlowInput.Local("draft"),false),new FlowProgram.If("valid",new FlowInput.Local("ready")),
            new FlowProgram.Ask("confirm_choice",new FlowInput.Local("question")),call(CONFIRM,List.of("draft"),List.of("reply")),
            new FlowProgram.Shared("display_action","last_action",new FlowInput.Local("reply"),false),new FlowProgram.EndIf("end_valid"),
            def(CHECK,Map.of(),Map.of("draft",DRAFT,"ready",BOOLEAN)),
            new FlowProgram.EndProcedure("end_receive"),def(CONFIRM,Map.of("draft",DRAFT),Map.of("reply",TEXT)),new FlowProgram.EndProcedure("end_confirm"));
        var main=new FlowProgram(Map.of("question",TEXT),steps).withAssembly();
        var w=new ProgramWorkspace(ProgramDocument.of(archive));w.setFlow(Optional.of(FlowDocumentBody.fromProgram(main,
            record("question","核对配方、份数、书页后输入 YES；其他输入取消 / Verify then type YES, otherwise cancel"))));
        var views=new LinkedHashMap<String,TerminalDefinition.Node>();
        views.put("root",new TerminalDefinition.Column(List.of("recipe","batches","locator","products","state","last")));
        views.put("recipe",new TerminalDefinition.Text(at("memory","current","request","d","k")));views.put("batches",new TerminalDefinition.Text(at("memory","current","request","n")));
        views.put("locator",new TerminalDefinition.Text(at("memory","current","request","d")));views.put("products",new TerminalDefinition.Text(at("memory","current","o")));
        views.put("state",new TerminalDefinition.Text(at("memory","current","c")));views.put("last",new TerminalDefinition.Text(at("memory","last_action")));
        w.setTerminal(Optional.of(new TerminalDefinition("root",views)));publish(CHECK,w);
        if("1".equals(System.getenv("AUTHOR_RECIPE_BROWSER")))Files.copy(EXAMPLES.resolve(CONFIRM+".asm"),DOCS.resolve(CONFIRM+".asm"),StandardCopyOption.REPLACE_EXISTING);
        else assertEquals(-1,Files.mismatch(EXAMPLES.resolve(CONFIRM+".asm"),DOCS.resolve(CONFIRM+".asm")));
    }
    @Test void rereadAndRoute() throws Exception {
        for(var id:List.of(DISPATCH,ROUTE)){
            var output=id.equals(DISPATCH)?"reply":"result";
            var result=id.equals(DISPATCH)?(DataType)ANY:type(Map.of("j",TEXT,"a",TEXT,"i",ANY));
            var main=new FlowProgram(Map.of(),List.of(call(id,List.of(),List.of(output)),def(id,Map.of(),Map.of(output,result)),
                new FlowProgram.EndProcedure("end"))).withAssembly();
            var w=new ProgramWorkspace();w.setFlow(Optional.of(FlowDocumentBody.fromProgram(main,record())));
            w.setTerminal(Optional.of(NativeLibraryResultLayouts.fields(output)));publish(id,w);
        }
    }
}
