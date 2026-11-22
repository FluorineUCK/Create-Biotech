package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.nio.file.Files;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.RecipeBrowserDocumentTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Three explicit roles on stock pins. The assembler, not a Java callback, moves the ROM page. */
class RecipeStreamDocumentsTest {
    private static void publish(String id,String source,int isa,Map<String,DataType> inputs,DataValue.RecordValue initial,
                                Map<String,DataType> outputs,Map<String,String> returns)throws Exception {
        var key=new ModuleDefinition.Key(id,1);var args=new LinkedHashMap<String,String>();inputs.keySet().forEach(k->args.put(k,k));
        var bindings=new LinkedHashMap<String,String>();outputs.keySet().forEach(k->bindings.put(k,k));
        var program=new FlowProgram(inputs,List.of(new FlowProgram.Invoke("run",key,new FlowCallBindings(args,bindings)),
            new FlowProgram.Procedure("definition",key,inputs,outputs,returns,FlowBlockRules.NONE,Optional.of(new AssemblySource(source))),
            new FlowProgram.EndProcedure("end"))).withAssembly();
        var workspace=new ProgramWorkspace();workspace.setFlow(Optional.of(FlowDocumentBody.fromProgram(program,initial)));
        if(!outputs.isEmpty())workspace.setTerminal(Optional.of(NativeLibraryResultLayouts.fields(outputs.keySet().iterator().next())));
        var path=EXAMPLES.resolve(id+".asm");
        if("1".equals(System.getenv("AUTHOR_RECIPE_BROWSER")))Files.writeString(path,source);
        else assertEquals(source,Files.readString(path));
        if(id.startsWith("recipe_book_"))System.out.println(id+" measured "+program.usage(new ResourceLimits(4096,65536,8)));
        checkOrWrite(id,workspace.snapshot());
    }
    @Test void wholeBookQueryIsEditableAndFitsStockHardware()throws Exception {
        publish("recipe_book_query",Files.readString(DOCS.resolve("recipe_book_query.asm")),15,
            Map.of(),record(),Map.of(),Map.of());
        publish("recipe_book_gate",Files.readString(DOCS.resolve("recipe_book_gate.asm")),14,
            Map.of("poll_ticks",DataType.Scalar.INTEGER),record("poll_ticks",1),Map.of(),Map.of());
    }
    @Test void wholeBookReaderAndDecoderFitStockHardware()throws Exception {
        var initial=LibrarySegmentedWriteDocuments.build("read_coordinator").flow().orElseThrow().initial();
        var rows=new ArrayList<>(((DataValue.ListValue)initial.field("commands")).values());
        rows.replaceAll(v->((DataValue.RecordValue)v).field("o").equals(new DataValue.TextValue("read_begin"))
            ?row("recipe_page_begin",0,"accepted",false):v);
        publish("recipe_book_read",Files.readString(DOCS.resolve("recipe_book_read.asm")),14,
            Map.of("commands",ROWS),record("commands",new DataValue.ListValue(rows)),
            Map.of("result",new DataType.RecordType(Map.of("n",DataType.Scalar.INTEGER,"c",DataType.Scalar.TEXT))),Map.of("result","result"));
        assertEquals("recipe_page_begin",((DataValue.TextValue)((DataValue.RecordValue)rows.get(7)).field("o")).value());
        assertEquals("download",((DataValue.TextValue)((DataValue.RecordValue)rows.get(9)).field("o")).value());
    }
    @Test void repeatedBookDecoderFitsStockHardware()throws Exception {
        String id="recipe_book_decode";var path=EXAMPLES.resolve(id+".asm");var source=Files.readString(DOCS.resolve(id+".asm"));
        if("1".equals(System.getenv("AUTHOR_RECIPE_BROWSER")))Files.writeString(path,source);else assertEquals(source,Files.readString(path));
        var integer=DataType.Scalar.INTEGER;
        checkOrWrite(id,LibrarySegmentedWriteDocuments.managed(Map.of("poll_ticks",integer,"capacity",integer,"workspace",integer),
            record("poll_ticks",1,"capacity",8192,"workspace",8192),source,14));
    }
    @Test void boundedRomReaderRolesUseThePublicSegmentDriver()throws Exception {
        String coordinator=Files.readString(DOCS.resolve("library_segmented_read_coordinator.asm"))
            .replace("REG context Context","TYPE Envelope RECORD value ANY\nREG outgoing Envelope\nREG high INTEGER\nREG context Context")
            .replace("CONST i 0","CONST i 0\nSIO.WRITE p0 i")
            .replace("CONST flag true","FIELD data retained value\nRECORD.MAKE outgoing value data\nCONST high 15\nSIO.WRITE p0 high\nXBUS.WRITE x1 outgoing\nCONST high 0\nSIO.WRITE p0 high\nCONST flag true");
        var template=LibrarySegmentedWriteDocuments.build("read_coordinator").flow().orElseThrow().initial();
        var rows=new ArrayList<>(((DataValue.ListValue)template.field("commands")).values());
        int opens=0;
        for(int i=0;i<rows.size();i++){
            var r=(DataValue.RecordValue)rows.get(i);
            if(r.field("o").equals(new DataValue.TextValue("read_begin"))){rows.set(i,row("recipe_page_begin",0,"accepted",false));opens++;}
        }
        assertEquals(1,opens);
        publish("recipe_stream_read",coordinator,14,Map.of("commands",ROWS),record("commands",new DataValue.ListValue(rows)),
            Map.of("result",OUTCOME),Map.of("result","result"));
        // A single download: after handing the decoded value back it never re-registers a reader on x0.
        publish("recipe_stream_decode",Files.readString(DOCS.resolve("library_segmented_read_decode.asm")),14,
            Map.of("capacity",DataType.Scalar.INTEGER,"workspace",DataType.Scalar.INTEGER),record("capacity",8192,"workspace",8192),Map.of(),Map.of());
        // Do not join the shared XBus read during the download transaction. Only p0=15 arms this input.
        var templateQuery=Files.readString(EXAMPLES.resolve("recipe_browser_query.asm")).replace("\r\n","\n");
        var query="; One positioned page, received only after the reader closes its stream.\n"
            +templateQuery.substring(templateQuery.indexOf("TYPE Product"),templateQuery.indexOf("LESS_INTEGER ok page_count zero"))
            +"gate:\nSIO.READ status p0\nCONST i 15\nEQUAL_INTEGER ok status i\nJFALSE ok wait_gate\n"
            +"XBUS.READ env x0\nFIELD raw env value\nDROP env\nJMP await_query\nwait_gate:\nSLEEP poll_ticks\nJMP gate\n"
            +templateQuery.substring(templateQuery.indexOf("await_query:")).replace("LIST.GET raw pages i\n","").replace("page_count","one");
        query=query.replace("REG pages Pages\n","").replace("TYPE Pages LIST Page\n","");
        publish("recipe_stream_query",query,15,Map.of("poll_ticks",DataType.Scalar.INTEGER),
            record("poll_ticks",1),Map.of("error",DataType.Scalar.TEXT),Map.of("error","err"));
    }
}
