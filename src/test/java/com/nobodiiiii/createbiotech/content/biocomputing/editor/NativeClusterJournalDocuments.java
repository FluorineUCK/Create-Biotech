package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.*;

/** Test-source authoring only. Players get ordinary editable documents, not a Java application. */
public final class NativeClusterJournalDocuments {
    private NativeClusterJournalDocuments() {}
    private static final String CONTEXT_TYPES = "TYPE row_type RECORD o TEXT d ANY e TEXT l BOOLEAN\nTYPE context_type RECORD r row_type s ANY d ANY\nTYPE result_type RECORD ok BOOLEAN value ANY\n";

    public static ProgramDocument build(String role) {
        var document = NativeClusterSerialDocuments.build("worker");
        var base = document.flow().orElseThrow();
        var program = base.program(document.archive());
        var implementation = program.steps().stream().filter(FlowProgram.Procedure.class::isInstance)
            .map(FlowProgram.Procedure.class::cast).findFirst().orElseThrow();
        Map<String, DataType> inputs;
        DataValue.RecordValue initial;
        String source, pin;
        switch (role) {
            case "worker" -> {
                inputs = base.inputs(); initial = base.initial(); pin = "x0";
                source = implementation.assembly().orElseThrow().text()
                    .replace("TYPE reply_type RECORD r ANY t TEXT v INTEGER", "TYPE reply_type RECORD r ANY t TEXT v ANY")
                    .replace("REG length INTEGER", "TYPE reading_type RECORD v INTEGER\nTYPE saved_type RECORD ok BOOLEAN value ANY\nREG length INTEGER\nREG reading reading_type\nREG saved saved_type")
                    .replace("RECORD.MAKE reply r request t target v length", "RECORD.MAKE reading v length\nXBUS.WRITE x1 reading\nXBUS.READ saved x1\nRECORD.MAKE reply r request t target v saved");
            }
            case "preparer" -> {
                inputs = Map.of("interval", INTEGER, "volume", TEXT, "entry", TEXT);
                initial = record("interval", 20, "volume", "plant", "entry", "reading"); pin = "x0";
                source = """
                    TYPE result_type RECORD ok BOOLEAN value ANY
                    TYPE reading_type RECORD v INTEGER
                    TYPE mutation_type RECORD op TEXT volume TEXT entry TEXT expected ANY kind TEXT value ANY
                    REG replacement INTEGER
                    REG reading reading_type
                    REG result result_type
                    REG flag BOOLEAN
                    REG stat ANY
                    REG version ANY
                    REG mutation mutation_type
                    REG op TEXT
                    REG kind TEXT
                    XBUS.READ reading x0
                    FIELD replacement reading v
                    XBUS.WRITE x1 reading
                    XBUS.READ result x1
                    FIELD flag result ok
                    JFALSE flag done
                    FIELD stat result value
                    FIELD version stat version
                    CONST op "put"
                    CONST kind "DATA"
                    RECORD.MAKE mutation op op volume volume entry entry expected version kind kind value replacement
                    XBUS.WRITE x1 mutation
                    XBUS.READ result x1
                    done:
                    XBUS.WRITE x0 result
                    """;
            }
            case "coordinator" -> {
                inputs = Map.of("interval", INTEGER, "commands", ROWS);
                initial = record("interval", 20,
                    "commands", NativeLibraryWriteDocuments.build("a").flow().orElseThrow().initial().field("commands")); pin = "x1";
                source = CONTEXT_TYPES + """
                    TYPE reading_type RECORD v INTEGER
                    TYPE mutation_type RECORD op TEXT volume TEXT entry TEXT expected ANY kind TEXT value ANY
                    REG kick reading_type
                    REG mutation mutation_type
                    REG context context_type
                    REG tx result_type
                    REG row row_type
                    REG i INTEGER
                    REG one INTEGER
                    REG seq ANY
                    REG op TEXT
                    REG write TEXT
                    REG result TEXT
                    REG data ANY
                    REG reply ANY
                    REG retained ANY
                    REG flag BOOLEAN
                    XBUS.READ kick x1
                    CONST i 0
                    CONST one 1
                    CONST seq 0
                    CONST write "write"
                    CONST result "result"
                    CONST retained null
                    next:
                    LIST.GET row commands i
                    FIELD op row o
                    FIELD data row d
                    VALUE.EQUALS flag op write
                    JFALSE flag dispatch
                    RECORD.MAKE tx ok flag value retained
                    XBUS.WRITE x1 tx
                    XBUS.READ mutation x1
                    MOV data mutation
                    dispatch:
                    RECORD.MAKE context r row s seq d data
                    XBUS.WRITE x0 context
                    XBUS.READ tx x0
                    FIELD flag tx ok
                    JFALSE flag done
                    FIELD reply tx value
                    FIELD seq reply sequence
                    VALUE.EQUALS flag op result
                    JFALSE flag advance
                    FIELD retained reply value
                    advance:
                    ADD_INTEGER i i one
                    FIELD flag row l
                    JFALSE flag next
                    RECORD.MAKE tx ok flag value retained
                    done:
                    XBUS.WRITE x1 tx
                    """;
            }
            case "adapter" -> {
                inputs = Map.of("interval", INTEGER, "target", TARGET);
                initial = record("interval", 20, "target", record("x", 0, "y", 0, "z", 0)); pin = "x0";
                source = PACKET_TYPE + CONTEXT_TYPES + """
                    REG context context_type
                    REG row row_type
                    REG seq ANY
                    REG data ANY
                    REG op TEXT
                    REG expected TEXT
                    REG kind TEXT
                    REG request TEXT
                    REG packet packet_type
                    REG tx result_type
                    REG reply ANY
                    REG code ANY
                    REG result TEXT
                    REG busy TEXT
                    REG flag BOOLEAN
                    XBUS.READ context x0
                    FIELD row context r
                    FIELD seq context s
                    FIELD data context d
                    FIELD op row o
                    FIELD expected row e
                    CONST kind "library_command"
                    CONST result "result"
                    CONST busy "busy"
                    poll:
                    CORRELATION request
                    RECORD.MAKE packet kind kind target target request request operation op sequence seq data data
                    XBUS.WRITE x1 packet
                    XBUS.READ tx x1
                    FIELD flag tx ok
                    JFALSE flag done
                    FIELD reply tx value
                    FIELD code reply code
                    VALUE.EQUALS flag code expected
                    JFALSE flag unexpected
                    JMP reply
                    unexpected:
                    VALUE.EQUALS flag op result
                    JFALSE flag reply
                    VALUE.EQUALS flag code busy
                    JFALSE flag reply
                    JMP poll
                    reply:
                    RECORD.MAKE tx ok flag value reply
                    done:
                    XBUS.WRITE x0 tx
                    """;
            }
            default -> throw new IllegalArgumentException(role);
        }
        var bindings = new LinkedHashMap<String, String>(); inputs.keySet().forEach(n -> bindings.put(n, n));
        var steps = new ArrayList<FlowProgram.Step>();
        for (var step : program.steps()) {
            if (step instanceof FlowProgram.Procedure p)
                steps.add(new FlowProgram.Procedure(p.sourceId(), p.key(), inputs, Map.of(), Map.of(), p.rules(), Optional.of(new AssemblySource(source))));
            else if (step instanceof FlowProgram.TaskStart t)
                steps.add(new FlowProgram.TaskStart(t.sourceId(), t.procedure(), bindings, t.handle()));
            else if (step instanceof FlowProgram.XBusStatus s)
                steps.add(new FlowProgram.XBusStatus(s.sourceId(), pin, s.local()));
            else steps.add(step);
        }
        var body = FlowDocumentBody.fromProgram(new FlowProgram(inputs, steps).withAssembly(), initial);
        body = new FlowDocumentBody(body.inputs(), body.steps(), body.initial(), body.canvas(), base.inline(), body.execution());
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(body));
        return workspace.snapshot();
    }
}
