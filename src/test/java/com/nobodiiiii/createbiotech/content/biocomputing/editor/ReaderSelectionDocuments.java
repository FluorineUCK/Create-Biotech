package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.record;
import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.*;
import java.nio.file.*;
import java.util.*;

/** Maintainer authoring only; the installed public source is the entire application. */
public final class ReaderSelectionDocuments {
    private ReaderSelectionDocuments() {}
    public static final ModuleDefinition.Key CHOOSE=new ModuleDefinition.Key("reader_select",2);
    private static final ModuleDefinition.Key RECEIVE_PANEL = new ModuleDefinition.Key("reader_selection_receive_panel", 1);
    private static final ModuleDefinition.Key EXCHANGE = new ModuleDefinition.Key("reader_select_exchange", 2);
    private static final ModuleDefinition.Key BUSY = new ModuleDefinition.Key("reader_select_busy", 1);
    private static final String BUSY_CELL = "choice_in_flight";
    private static final DataValue.BooleanValue TRUE = new DataValue.BooleanValue(true);
    private static final DataValue.BooleanValue FALSE = new DataValue.BooleanValue(false);
    public static final DataType.RecordType REQUEST=new DataType.RecordType(Map.of("i",new DataType.OptionalType(TEXT),"t",new DataType.OptionalType(TEXT),"n",INTEGER,"c",NBT,"p",new DataType.OptionalType(MaterialSelectionDocuments.CHOICE)));
    public static final DataType.RecordType REPLY=new DataType.RecordType(Map.of("c",TEXT,"i",new DataType.OptionalType(MaterialSelectionDocuments.EXACT)));
    public static final DataType.RecordType ROW=new DataType.RecordType(Map.of(
        "m", MaterialSelectionDocuments.MATERIAL, "p", MaterialSelectionDocuments.CHOICE, "w", INTEGER));
    public static final DataType.RecordType PANEL=new DataType.RecordType(Map.of("r",new DataType.ListType(ROW),"e",ANY));
    public static final String TYPES="""
        TYPE MaybeText OPTIONAL TEXT
        TYPE Material RECORD item MaybeText tag MaybeText count INTEGER components NBT
        TYPE Tags LIST TEXT
        TYPE Choice RECORD slot INTEGER item TEXT tags Tags
        TYPE MaybeChoice OPTIONAL Choice
        TYPE Choices LIST Choice
        TYPE Request RECORD i MaybeText t MaybeText n INTEGER c NBT p MaybeChoice
        TYPE Row RECORD m Material p Choice w INTEGER
        TYPE Rows LIST Row
        TYPE Panel RECORD r Rows e ANY
        TYPE Exact RECORD item TEXT count INTEGER components NBT
        TYPE MaybeExact OPTIONAL Exact
        TYPE Reply RECORD c TEXT i MaybeExact
        """;
    public static String source(String role) {
        if(role.equals("emitter")) {
            var document=ReaderRangeDocuments.build();
            return document.flow().orElseThrow().program(document.archive()).steps().stream().filter(FlowProgram.Procedure.class::isInstance).map(FlowProgram.Procedure.class::cast).findFirst().orElseThrow().assembly().orElseThrow().text()+"\nXBUS.WRITE x1 outcome\n";
        }
        if(role.equals("terminal"))return TYPES+"""
            ; Called by reader_select@2 after its visible Flow transaction acquires choice_in_flight.
            ; Adapt the original material and chosen descriptor only after explicit selection.
            ; The wrapper releases the cell only after this procedure receives S's reply. No order or retry.
            REG item MaybeText
            REG tag MaybeText
            REG count INTEGER
            REG patch NBT
            REG request Request
            REG reply Reply
            REG fifteen INTEGER
            REG status INTEGER
            REG ready BOOLEAN
            FIELD item m item
            FIELD tag m tag
            FIELD count m count
            FIELD patch m components
            RECORD.MAKE request i item t tag n count c patch p p
            XBUS.WRITE x1 request
            CONST fifteen 15
            wait:
            XBUS.STATUS status x1
            EQUAL_INTEGER ready status fifteen
            JFALSE ready pause
            XBUS.READ reply x1
            RET
            pause:
            SLEEP w
            JMP wait
            """;
        if(!role.equals("preparer"))throw new IllegalArgumentException(role);
        return TYPES+"""
            ; One declared material, one real page. No implicit first-item choice or stock claim.
            TYPE Target RECORD x INTEGER y INTEGER z INTEGER
            TYPE Range RECORD offset INTEGER count INTEGER total INTEGER next INTEGER
            TYPE Components RECORD create_biotech:inventory_item_tags Choices
            TYPE Page RECORD game_time INTEGER target Target components Components range Range
            TYPE MaybePage OPTIONAL Page
            TYPE Checked RECORD ok BOOLEAN value MaybePage
            TYPE Outcome RECORD ok BOOLEAN value ANY
            REG outcome Outcome
            REG reply ANY
            REG expected TEXT
            REG code ANY
            REG value ANY
            REG checked Checked
            REG optional MaybePage
            REG page Page
            REG components Components
            REG choices Choices
            REG choice Choice
            REG row Row
            REG rows Rows
            REG panel Panel
            REG ok BOOLEAN
            REG more BOOLEAN
            REG i INTEGER
            REG one INTEGER
            REG n INTEGER
            REG startup INTEGER
            CONST i 0
            wait_for_emitter:
            XBUS.STATUS startup x0
            EQUAL_INTEGER ok startup i
            JFALSE ok receive_page
            SLEEP poll_ticks
            JMP wait_for_emitter
            receive_page:
            XBUS.READ outcome x0
            CONST expected "ok"
            CONST one 1
            LIST.MAKE rows
            FIELD reply outcome value
            FIELD ok outcome ok
            JFALSE ok done
            FIELD code reply code
            VALUE.EQUALS ok code expected
            JFALSE ok done
            FIELD value reply value
            VALUE.CHECK checked value
            FIELD optional checked value
            REQUIRE_VALUE page optional
            FIELD components page components
            FIELD choices components create_biotech:inventory_item_tags
            LIST.LENGTH n choices
            loop:
            LESS_INTEGER more i n
            JFALSE more done
            LIST.GET choice choices i
            RECORD.MAKE row m material p choice w poll_ticks
            LIST.APPEND rows rows row
            ADD_INTEGER i i one
            JMP loop
            done:
            RECORD.MAKE panel r rows e reply
            XBUS.WRITE x1 panel
            """;
    }
    public static ProgramDocument build(String role) {
        var workspace=role.equals("terminal") ? terminalWorkspace() : new ProgramWorkspace();var inputs=new LinkedHashMap<String,DataType>();var initial=new LinkedHashMap<String,DataValue>();var steps=new ArrayList<FlowProgram.Step>();
        if(role.equals("terminal")) {
            steps.add(new FlowProgram.Invoke("receive_panel", RECEIVE_PANEL, new FlowCallBindings(Map.of(), Map.of("panel", "panel"))));
            steps.add(new FlowProgram.Procedure("panel_definition", RECEIVE_PANEL, Map.of(), Map.of("panel", PANEL),
                Map.of("panel", "panel"), FlowBlockRules.NONE, Optional.of(new AssemblySource(panelSource()))));
            steps.add(new FlowProgram.EndProcedure("panel_end"));
            steps.addAll(choiceSteps());
            steps.add(new FlowProgram.Procedure("exchange_definition", EXCHANGE, ROW.fields(),
                Map.of("result", REPLY), Map.of("result", "reply"), FlowBlockRules.NONE, Optional.of(new AssemblySource(source(role)))));
            steps.add(new FlowProgram.EndProcedure("exchange_end"));
            steps.add(new FlowProgram.Procedure("busy_definition", BUSY, Map.of(), Map.of("result", REPLY),
                Map.of("result", "reply"), FlowBlockRules.NONE, Optional.of(new AssemblySource(TYPES + """
                    REG reply Reply
                    CONST reply {"c":"busy","i":null}
                    """))));
            steps.add(new FlowProgram.EndProcedure("busy_end"));
            workspace.setTerminal(Optional.of(terminal()));
        } else {
            if(role.equals("emitter")) {
                var old=ReaderRangeDocuments.build().flow().orElseThrow();inputs.putAll(old.inputs());initial.putAll(old.initial().fields());
            } else {
                inputs.put("material",MaterialSelectionDocuments.MATERIAL);inputs.put("poll_ticks",INTEGER);
                initial.put("material",record("item","minecraft:stone","tag",DataValue.NullValue.INSTANCE,"count",1,"components",new DataValue.NbtValue(10,record())));initial.put("poll_ticks",new DataValue.IntegerValue(20));
            }
            var key=new ModuleDefinition.Key("reader_selection_"+role,role.equals("preparer") ? 2 : 1);var arguments=new LinkedHashMap<String,String>();inputs.keySet().forEach(n->arguments.put(n,n));
            steps.add(new FlowProgram.Invoke("run",key,new FlowCallBindings(arguments,Map.of())));
            steps.add(new FlowProgram.Procedure("implementation",key,inputs,Map.of(),Map.of(),FlowBlockRules.NONE,Optional.of(new AssemblySource(source(role)))));
            steps.add(new FlowProgram.EndProcedure("end"));
        }
        workspace.setFlow(Optional.of(FlowDocumentBody.fromProgram(new FlowProgram(inputs,steps).withAssembly(),new DataValue.RecordValue(initial))));
        return workspace.snapshot();
    }
    private static String panelSource() {
        return TYPES + """
            ; Observe write occupancy without owning a read pin while P is stopped.
            REG panel Panel
            REG status INTEGER
            REG idle BOOLEAN
            REG zero INTEGER
            REG delay INTEGER
            CONST zero 0
            CONST delay 1
            wait_for_preparer:
            XBUS.STATUS status x0
            EQUAL_INTEGER idle status zero
            JFALSE idle receive_panel
            SLEEP delay
            JMP wait_for_preparer
            receive_panel:
            XBUS.READ panel x0
            """;
    }
    private static ProgramWorkspace terminalWorkspace() {
        var empty = new ComputationGraph(Map.of(), Map.of(), Map.of());
        var acquire = new ComputationGraph(Map.of(), Map.of("set", new ComputationGraph.Constant(TRUE)), Map.of("set_busy", "set"));
        var commit = new ComputationGraph(Map.of("commit", RegisterMemory.RESULT_TYPE), Map.of(
            "input", new ComputationGraph.Input("commit"), "ok", new ComputationGraph.Field("input", "ok")), Map.of("acquired", "ok"));
        var expressions = Map.of("set_busy", new ProgramDefinition.Entry(acquire, List.of(), List.of()),
            "commit_ok", new ProgramDefinition.Entry(commit, List.of(), List.of()));
        var archive = new ProgramArchive(new ProgramDefinition(empty,
            Map.of(BUSY_CELL, new ProgramDefinition.Cell(BOOLEAN, FALSE)), List.of(), List.of(), expressions), Map.of());
        return new ProgramWorkspace(new ProgramDocument(archive, Map.of(
            ProgramDocument.entryGraphId("commit_ok"), Map.of(
                "input", new ProgramDocument.Position(20, 20), "ok", new ProgramDocument.Position(170, 20)),
            ProgramDocument.entryGraphId("set_busy"), Map.of("set", new ProgramDocument.Position(20, 20)))));
    }
    private static List<FlowProgram.Step> choiceSteps() {
        // Old S has no correlation field. A committed local lock must precede any use of its pin.
        // If the wire procedure faults, there is deliberately no automatic release or retry.
        var reject = new FlowCallBindings(Map.of(), Map.of("result", "result"));
        return List.of(new FlowProgram.Procedure("choose_definition", CHOOSE, ROW.fields(),
                Map.of("result", REPLY), Map.of("result", "result")),
            new FlowProgram.Memory("begin_choice", FlowProgram.MemoryKind.BEGIN, "", ""),
            new FlowProgram.Memory("read_choice_lock", FlowProgram.MemoryKind.READ, BUSY_CELL, "occupied"),
            new FlowProgram.If("choice_in_progress", "occupied"),
            new FlowProgram.Memory("rollback_busy", FlowProgram.MemoryKind.ROLLBACK, "", ""),
            new FlowProgram.Invoke("busy_result", BUSY, reject),
            new FlowProgram.Otherwise("can_try_choice"),
            new FlowProgram.Evaluate("new_lock", "set_busy"),
            new FlowProgram.Memory("take_choice_lock", FlowProgram.MemoryKind.WRITE, BUSY_CELL, "set_busy"),
            new FlowProgram.Memory("commit_choice_lock", FlowProgram.MemoryKind.COMMIT, "", "commit"),
            new FlowProgram.If("own_choice_lock", new FlowInput.Expression("commit_ok", "acquired", Map.of("commit", "commit"))),
            new FlowProgram.Invoke("choose_over_wire", EXCHANGE,
                new FlowCallBindings(Map.of("m", "m", "p", "p", "w", "w"), Map.of("result", "result"))),
            new FlowProgram.Shared("release_choice_lock", BUSY_CELL, new FlowInput.Literal(FALSE), false),
            new FlowProgram.Otherwise("choice_lock_conflict"),
            new FlowProgram.Invoke("conflict_result", BUSY, reject),
            new FlowProgram.EndIf("end_commit_result"),
            new FlowProgram.EndIf("end_lock"),
            new FlowProgram.EndProcedure("choose_end"));
    }
    private static TerminalBinding.Path path(String root,String... fields){var p=new ValuePath(root,List.of());for(String f:fields)p=p.key(f);return new TerminalBinding.Path(p);}
    private static TerminalBinding.Literal literal(String value){return new TerminalBinding.Literal(new DataValue.TextValue(value));}
    public static TerminalDefinition terminal(){
        var nodes=new LinkedHashMap<String,TerminalDefinition.Node>();
        nodes.put("root",new TerminalDefinition.Column(List.of("hint","reply_code","raw_hint","raw","no_rows",
            "gate_hint","gate","rows","tasks")));
        nodes.put("hint",new TerminalDefinition.Text(literal("Explicit choice only — no reservation or order")));
        nodes.put("reply_code",new TerminalDefinition.Text(path("output","panel","e","code")));
        nodes.put("raw_hint",new TerminalDefinition.Text(literal("Reader reply (open to inspect)")));
        nodes.put("raw",new TerminalDefinition.Text(path("output","panel","e")));
        nodes.put("no_rows",new TerminalDefinition.Text(literal("No candidate rows = nothing to select")));
        nodes.put("gate_hint",new TerminalDefinition.Text(literal("Choice in flight (see Tasks below)")));
        nodes.put("gate",new TerminalDefinition.Text(path("memory",BUSY_CELL)));
        nodes.put("rows",new TerminalDefinition.Repeat(path("output","panel","r"),"choice"));
        nodes.put("choice",new TerminalDefinition.Column(List.of("material_hint","material_item","material_tag","material_count","material_components",
            "slot_hint","slot","candidate_item","tags","select")));
        nodes.put("material_hint",new TerminalDefinition.Text(literal("Material requirement: item, tag, count, components")));
        nodes.put("material_item",new TerminalDefinition.Text(path("row","m","item")));
        nodes.put("material_tag",new TerminalDefinition.Text(path("row","m","tag")));
        nodes.put("material_count",new TerminalDefinition.Text(path("row","m","count")));
        nodes.put("material_components",new TerminalDefinition.Text(path("row","m","components")));
        nodes.put("slot_hint",new TerminalDefinition.Text(literal("Reader candidate: slot, item, tags only")));
        nodes.put("slot",new TerminalDefinition.Text(path("row","p","slot")));
        nodes.put("candidate_item",new TerminalDefinition.Text(path("row","p","item")));
        nodes.put("tags",new TerminalDefinition.Text(path("row","p","tags")));
        nodes.put("select",new TerminalDefinition.Button(literal("Choose this slot"),new TerminalBinding.Literal(TRUE),new TerminalTarget.Task(CHOOSE),path("row")));
        nodes.put("tasks",new TerminalDefinition.Repeat(path("tasks"),"task"));nodes.put("task",new TerminalDefinition.Column(List.of("phase","result","failure")));
        for(String field:List.of("phase","result","failure"))nodes.put(field,new TerminalDefinition.Text(path("row",field)));
        return new TerminalDefinition("root",nodes);
    }
    public static ProgramDocument published(String role) throws Exception {
        return ProgramDocumentWire.decode(Base64.getDecoder().decode(packaged(role, "bcp").strip()), ReaderDriverDocumentTest.ARCHIVE);
    }
    private static String packaged(String role, String extension) throws Exception {
        try (var input = ReaderSelectionDocuments.class.getResourceAsStream(
            "/assets/create_biotech/biocomputing/examples/reader_selection_" + role + "." + extension)) {
            return new String(Objects.requireNonNull(input, role).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    @org.junit.jupiter.api.Test void completePackagedImagesFitDefaultHardwareAndMatchEditableSource() throws Exception {
        var images = new LinkedHashMap<String, ProgramDocument>();
        for (String role : List.of("emitter", "preparer", "terminal")) {
            var document = build(role);
            String generate = System.getenv("READER_SELECTION_GENERATE");
            if (generate != null && !generate.isBlank()) {
                Path dir = Path.of(generate);
                Files.writeString(dir.resolve("reader_selection_" + role + ".asm"), source(role));
                Files.writeString(dir.resolve("reader_selection_" + role + ".bcp"),
                    Base64.getEncoder().encodeToString(ProgramDocumentWire.encode(document, ReaderDriverDocumentTest.ARCHIVE)) + "\n");
            } else {
                assertEquals(source(role), packaged(role, "asm"), role);
                assertEquals(document, published(role), role);
            }
            images.put(role, document);
        }
        images.put("reader_b", ReaderDriverDocumentTest.load("b"));
        images.put("reader_c", ReaderDriverDocumentTest.load("c"));
        images.put("selection_service", MaterialSelectionDocuments.published("service"));
        var words = Map.of("emitter", 246L, "reader_b", 256L, "reader_c", 220L, "preparer", 212L, "terminal", 240L, "selection_service", 250L);
        for (var entry : images.entrySet()) {
            var document = entry.getValue();
            var program = document.flow().orElseThrow().program(document.archive());
            var usage = program.usage(ReaderDriverDocumentTest.HARDWARE);
            assertEquals(words.get(entry.getKey()).longValue(), usage.programUnits(), entry.getKey());
            document.validate(ReaderDriverDocumentTest.HARDWARE);
            assertEquals(document, ProgramDocumentWire.decode(ProgramDocumentWire.encode(document, ReaderDriverDocumentTest.ARCHIVE), ReaderDriverDocumentTest.ARCHIVE));
            System.out.println("READER_SELECTION_FULL role=" + entry.getKey() + " " + usage + " families=" + FlowXBus.image(program).instructionFamilies());
        }
    }
}
