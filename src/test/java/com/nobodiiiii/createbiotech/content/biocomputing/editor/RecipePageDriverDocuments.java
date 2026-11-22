package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.*;
import static org.junit.jupiter.api.Assertions.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Authoring fixture only: the shipped program performs all framing and arithmetic in ordinary ISA. */
public final class RecipePageDriverDocuments {
    private RecipePageDriverDocuments() {}
    private static final String SOURCE = "TYPE envelope RECORD value ANY\nREG forwarded envelope\n" + A
        .replace("REG success BOOLEAN\n", "").replace("REG error ANY\n", "").replace("REG read_result ANY\n", "")
        .replace("CONST success false\n", "").replace("CONST error null\n", "").replace("CONST read_result null\n", "")
        .replace("CONST read \"read\"", "CONST read \"recipe_page\"")
        .replace("FIELD read_result reply value", "FIELD data reply value\nRECORD.MAKE forwarded value data\nXBUS.WRITE x1 forwarded")
        .replace("MOV success last", "").replace("MOV error reply", "");

    private static final String CALCULATE = """
        TYPE R FORMAT recipe 1
        TYPE maybe_recipe OPTIONAL R
        TYPE recipe_check RECORD ok BOOLEAN value maybe_recipe
        TYPE maybe_integer OPTIONAL INTEGER
        TYPE integer_check RECORD ok BOOLEAN value maybe_integer
        TYPE calculation_type RECORD recipe R ingredient ANY batches INTEGER count INTEGER
        TYPE envelope RECORD value ANY
        REG incoming envelope
        REG page ANY
        REG recipe R
        REG selected_recipe maybe_recipe
        REG validated recipe_check
        REG ingredients ANY
        REG ingredient ANY
        REG raw_count ANY
        REG count INTEGER
        REG selected maybe_integer
        REG checked integer_check
        REG flag BOOLEAN
        REG result ANY
        REG calculated calculation_type
        REG status INTEGER
        REG ready INTEGER
        CONST ready 15
        poll:
        XBUS.STATUS status x0
        EQUAL_INTEGER flag status ready
        JFALSE flag yield
        JMP receive
        yield:
        SLEEP poll_ticks
        JMP poll
        receive:
        XBUS.READ incoming x0
        FIELD page incoming value
        FMT.CHECK validated page recipe 1
        MOV result validated
        FIELD flag validated ok
        JFALSE flag done
        FIELD selected_recipe validated value
        REQUIRE_VALUE recipe selected_recipe
        FMT.PROJECT page recipe recipe 1
        FIELD ingredients page inputs
        LIST.GET ingredient ingredients ingredient_index
        FIELD raw_count ingredient count
        VALUE.CHECK checked raw_count
        MOV result checked
        FIELD flag checked ok
        JFALSE flag done
        FIELD selected checked value
        REQUIRE_VALUE count selected
        MULTIPLY_INTEGER count count batches
        RECORD.MAKE calculated recipe recipe ingredient ingredient batches batches count count
        MOV result calculated
        done:
        """;

    public static ProgramDocument build(String role) {
        if (role.equals("d")) return calculator();
        if (!role.equals("a")) throw new IllegalArgumentException(role);
        var inputs = new LinkedHashMap<String, DataType>();
        inputs.put("target", TARGET); inputs.put("commands", ROWS);
        var initial = record("target", record("x", 0, "y", 0, "z", 0),
            "commands", new DataValue.ListValue(List.of(row("status", null, "idle", false),
                row("seek", record("x", 1, "y", 0, "z", 0), "accepted", false),
                row("result", null, "positioned", false), row("ack", null, "acknowledged", false),
                row("select", 0, "accepted", false), row("result", null, "selected", false),
                row("ack", null, "acknowledged", false), row("recipe_page", 0, "accepted", false),
                row("result", null, "recipe_page", false), row("ack", null, "acknowledged", true))));
        var bindings = new LinkedHashMap<String, String>(); inputs.keySet().forEach(name -> bindings.put(name, name));
        var key = new ModuleDefinition.Key("recipe_page", 1);
        var steps = List.<FlowProgram.Step>of(
            new FlowProgram.Invoke("read_and_calculate", key, new FlowCallBindings(bindings, Map.of("result", "result"))),
            new FlowProgram.Procedure("recipe_page_definition", key, inputs, Map.of("result", ANY), Map.of("result", "reply"),
                FlowBlockRules.NONE, Optional.of(new AssemblySource(SOURCE))),
            new FlowProgram.EndProcedure("recipe_page_end"));
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(FlowDocumentBody.fromProgram(new FlowProgram(inputs, steps).withAssembly(), initial)));
        workspace.setTerminal(Optional.of(NativeLibraryResultLayouts.fields("result")));
        return workspace.snapshot();
    }

    private static ProgramDocument calculator() {
        var inputs = Map.<String, DataType>of("batches", INTEGER, "ingredient_index", INTEGER,"poll_ticks",INTEGER);
        var key = new ModuleDefinition.Key("recipe_amount", 3);
        var steps = List.<FlowProgram.Step>of(
            new FlowProgram.Invoke("calculate", key, new FlowCallBindings(Map.of("batches", "batches", "ingredient_index", "ingredient_index","poll_ticks","poll_ticks"), Map.of("result", "result"))),
            new FlowProgram.Procedure("amount_definition", key, inputs, Map.of("result", ANY), Map.of("result", "result"),
                FlowBlockRules.NONE, Optional.of(new AssemblySource(CALCULATE))),
            new FlowProgram.EndProcedure("amount_end"));
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(FlowDocumentBody.fromProgram(new FlowProgram(inputs, steps).withAssembly(), record("batches", 3, "ingredient_index", 0,"poll_ticks",20))));
        workspace.setTerminal(Optional.of(NativeLibraryResultLayouts.fields("result")));
        return workspace.snapshot();
    }

    @Test void publishedRecipeReadIsEditableBudgetedAssemblyAndExactReadableExport() throws Exception {
      for (String role : List.of("a", "d")) {
        var document = build(role);
        System.out.println("RECIPE_PAGE_DRIVER " + role + " " + document.flow().orElseThrow().program(document.archive())
            .usage(new ResourceLimits(4096, 65536, 8)));
        document.validate(RedstoneDriverDocumentTest.HARDWARE);
        assertEquals(FlowProgram.Execution.REGISTER_V1, document.flow().orElseThrow().execution());
        assertEquals(document, new ProgramWorkspace(document).snapshot());
        assertTrue(document.archive().modules().isEmpty());
        String bytes = Base64.getEncoder().encodeToString(ProgramDocumentWire.encode(document, RedstoneDriverDocumentTest.ARCHIVE)) + "\n";
        String first = null;
        for (String directory : List.of("src/main/resources/assets/create_biotech/biocomputing/examples", "examples/biocomputing")) {
            var file = Path.of(directory, "recipe_page_" + role + ".bcp");
            if (role.equals(System.getenv("GENERATE_RECIPE_PAGE_DRIVER"))) Files.writeString(file, bytes);
            String actual = Files.readString(file);
            assertEquals(document, ProgramDocumentWire.decode(Base64.getDecoder().decode(actual.strip()), RedstoneDriverDocumentTest.ARCHIVE));
            if (first != null) assertEquals(first, actual); first = actual;
        }
        var export = Path.of("examples/biocomputing/recipe-page-" + role + ".asm");
        String source = role.equals("a") ? SOURCE : CALCULATE;
        if (role.equals(System.getenv("GENERATE_RECIPE_PAGE_DRIVER"))) Files.writeString(export, source);
        assertEquals(source, Files.readString(export));
      }
    }
}
