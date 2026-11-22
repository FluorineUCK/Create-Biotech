package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.PreparedProgram;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;
import static org.junit.jupiter.api.Assertions.*;

/** Author the ordinary editable example; no production code calls this builder. */
public class RecipeBrowserDocumentTest {
    public static final String ID = "recipe_browser_query";
    public static final String TERMINAL = "recipe_browser_terminal";
    static final Path EXAMPLES = Path.of("src/main/resources/assets/create_biotech/biocomputing/examples");
    static final Path DOCS = Path.of("examples/biocomputing");

    public static ProgramDocument published() throws Exception {
        return published(ID);
    }
    public static ProgramDocument published(String id) throws Exception {
        try (var in = RecipeBrowserDocumentTest.class.getResourceAsStream(
                "/assets/create_biotech/biocomputing/examples/" + id + ".bcp")) {
            assertNotNull(in);
            return ProgramDocumentWire.decode(Base64.getDecoder().decode(new String(in.readAllBytes(),
                StandardCharsets.UTF_8).strip()), FoundationRecipeTest.LIMITS);
        }
    }

    @Test void packagedQueryIsEditableAndFitsAMasterCleric() throws Exception {
        var src = Files.readString(EXAMPLES.resolve(ID + ".asm"));
        var key = new ModuleDefinition.Key(ID, 1);
        Map<String,DataType> inputs = Map.of("poll_ticks", INTEGER, "page_count", INTEGER);
        var call = new FlowCallBindings(Map.of("poll_ticks","poll_ticks","page_count","page_count"),Map.of("error","error"));
        var main = new FlowProgram(inputs, List.of(new FlowProgram.Invoke("serve_pages",key,call),
            new FlowProgram.Procedure("query_definition",key,inputs,Map.of("error",TEXT),Map.of("error","err"),
                FlowBlockRules.NONE,Optional.of(new AssemblySource(src))), new FlowProgram.EndProcedure("end_query"))).withAssembly();
        var w = new ProgramWorkspace();
        w.setFlow(Optional.of(FlowDocumentBody.fromProgram(main, NativeLibraryReadDocuments.record("poll_ticks",1,"page_count",2))));
        w.setTerminal(Optional.of(NativeLibraryResultLayouts.fields("error")));
        var doc = w.snapshot();
        System.out.println("Browser station usage: " + main.usage(new ResourceLimits(2048,65536,8)));
        checkOrWrite(ID, doc);
    }

    @Test void terminalAsksOnTheMainTaskAndPublishesOnlyACompleteSearch() throws Exception {
        var key = new ModuleDefinition.Key(TERMINAL, 1);
        var panel = new DataType.RecordType(Map.of("q",TEXT,"c",TEXT,"rows",new DataType.ListType(ANY)));
        var empty = NativeLibraryReadDocuments.record("q","","c","idle","rows",new DataValue.ListValue(List.of()));
        var archive = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(),Map.of(),Map.of()),
            Map.of("last_search",new ProgramDefinition.Cell(panel,empty)),List.of()),Map.of());
        var workspace = new ProgramWorkspace(ProgramDocument.of(archive));
        var source = Files.readString(EXAMPLES.resolve(TERMINAL+".asm"));
        var main = new FlowProgram(Map.of("question",TEXT),List.of(
            new FlowProgram.Forever("browse"),
            new FlowProgram.Ask("ask_keyword",new FlowInput.Local("question")),
            new FlowProgram.Invoke("find_pages",key,new FlowCallBindings(Map.of(),Map.of("result","result"))),
            new FlowProgram.Shared("publish_search","last_search",new FlowInput.Local("result"),false),
            new FlowProgram.EndForever("next_keyword"),
            new FlowProgram.Procedure("search_definition",key,Map.of(),Map.of("result",panel),Map.of("result","panel"),
                FlowBlockRules.NONE,Optional.of(new AssemblySource(source))),new FlowProgram.EndProcedure("end_search"))).withAssembly();
        workspace.setFlow(Optional.of(FlowDocumentBody.fromProgram(main,NativeLibraryReadDocuments.record("question","搜索配方或产物 / Search (blank = all)"))));

        var root = new ValuePath("memory",List.of()).key("last_search");
        var row = new ValuePath("row",List.of());
        var nodes = new LinkedHashMap<String,TerminalDefinition.Node>();
        nodes.put("root",new TerminalDefinition.Column(List.of("help","keyword","status","rows")));
        nodes.put("help",new TerminalDefinition.Text(new TerminalBinding.Literal(new DataValue.TextValue("上次结果 / Last result · 点击记录查看书页 / Click a record for details"))));
        nodes.put("keyword",new TerminalDefinition.Text(new TerminalBinding.Path(root.key("q"))));
        nodes.put("status",new TerminalDefinition.Text(new TerminalBinding.Path(root.key("c"))));
        nodes.put("rows",new TerminalDefinition.Repeat(new TerminalBinding.Path(root.key("rows")),"recipe"));
        nodes.put("recipe",new TerminalDefinition.Row(List.of("icon","id","details")));
        nodes.put("icon",new TerminalDefinition.Item(new TerminalBinding.Path(row.key("outputs").index(0))));
        nodes.put("id",new TerminalDefinition.Text(new TerminalBinding.Path(row.key("pattern_id"))));
        nodes.put("details",new TerminalDefinition.Text(new TerminalBinding.Path(row)));
        workspace.setTerminal(Optional.of(new TerminalDefinition("root",nodes)));
        checkOrWrite(TERMINAL,workspace.snapshot());
    }

    static void checkOrWrite(String id, ProgramDocument doc) throws Exception {
        PreparedProgram.prepare(doc,StockApplicationDriverDocuments.hardware());
        if ("1".equals(System.getenv("AUTHOR_RECIPE_BROWSER"))) {
            var bcp = Base64.getEncoder().encodeToString(ProgramDocumentWire.encode(doc,FoundationRecipeTest.LIMITS))+"\n";
            Files.writeString(EXAMPLES.resolve(id+".bcp"),bcp);
            Files.writeString(DOCS.resolve(id+".bcp"),bcp);
            Files.copy(EXAMPLES.resolve(id+".asm"), DOCS.resolve(id+".asm"), StandardCopyOption.REPLACE_EXISTING);
        } else {
            assertEquals(doc,published(id));
            assertEquals(-1,Files.mismatch(EXAMPLES.resolve(id+".asm"), DOCS.resolve(id+".asm")));
            assertEquals(-1,Files.mismatch(EXAMPLES.resolve(id+".bcp"), DOCS.resolve(id+".bcp")));
        }
    }
}
