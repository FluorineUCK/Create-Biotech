package com.nobodiiiii.createbiotech.content.biocomputing.editor;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ParserHardwareFixture;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Editable resource authoring only, never an alternate application executor. */
class TypeCheckExampleTest {
    private static final ArchiveLimits ARCHIVE = new ArchiveLimits(10000, 100000, 1000000);
    private static final ResourceLimits HARDWARE = new ResourceLimits(128, 32768, 4);
    static ProgramDocument build() {
        var nullable = new DataType.OptionalType(DataType.Scalar.INTEGER);
        var result = new DataType.RecordType(Map.of("ok", DataType.Scalar.BOOLEAN, "value", nullable));
        var inputs = Map.<String, DataType>of("reading", DataType.Scalar.ANY);
        var outputs = Map.<String, DataType>of("checked", result, "next", nullable);
        var key = new ModuleDefinition.Key("checked_increment", 1);
        var source = new AssemblySource("""
            TYPE maybe OPTIONAL INTEGER
            REG checked OUTPUT checked
            REG next OUTPUT next
            REG ok BOOLEAN
            REG optional maybe
            REG value INTEGER
            REG one INTEGER
            CONST next null
            VALUE.CHECK checked reading
            FIELD ok checked ok
            JFALSE ok done
            FIELD optional checked value
            REQUIRE_VALUE value optional
            CONST one 1
            ADD_INTEGER value value one
            MOV next value
            done:
            """);
        var program = new FlowProgram(inputs, List.of(
            new FlowProgram.Invoke("check_and_increment", key, new FlowCallBindings(Map.of("reading", "reading"), Map.of("checked", "checked", "next", "next"))),
            new FlowProgram.Procedure("definition", key, inputs, outputs, Map.of("checked", "checked", "next", "next"), FlowBlockRules.NONE, Optional.of(source)),
            new FlowProgram.EndProcedure("end"))).withAssembly();
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(FlowDocumentBody.fromProgram(program, new DataValue.RecordValue(Map.of("reading", new DataValue.IntegerValue(15))))));
        workspace.setTerminal(Optional.of(NativeLibraryResultLayouts.fields("checked", "next")));
        return workspace.snapshot();
    }
    @Test void publicExamplePreservesTheCheckedSourceAndComputesOnlyAfterSuccessfulTyping() throws Exception {
        var document = build(); document.validate(HARDWARE);
        if ("1".equals(System.getenv("GENERATE_TYPE_CHECK"))) {
            var encoded = Base64.getEncoder().encodeToString(ProgramDocumentWire.encode(document, ARCHIVE)) + "\n";
            for (String directory : List.of("src/main/resources/assets/create_biotech/biocomputing/examples", "examples/biocomputing"))
                java.nio.file.Files.writeString(java.nio.file.Path.of(directory, "value_check_integer.bcp"), encoded);
        }
        var path = java.nio.file.Path.of("src/main/resources/assets/create_biotech/biocomputing/examples/value_check_integer.bcp");
        assertTrue(java.nio.file.Files.exists(path), "Type checking must be a public editable example");
        var published = ProgramDocumentWire.decode(Base64.getDecoder().decode(java.nio.file.Files.readString(path).strip()), ARCHIVE);
        assertEquals(document, published); assertEquals(document, new ProgramWorkspace(document).snapshot());
        System.out.println("VALUE_CHECK_EXAMPLE " + published.flow().orElseThrow().program(published.archive()).usage(HARDWARE));
        for (DataValue reading : List.of(new DataValue.IntegerValue(0), new DataValue.IntegerValue(15), new DataValue.TextValue("15"),
                DataValue.NullValue.INSTANCE, new DataValue.ErrorValue("denied", "no data"))) {
            var host = new ComputerBlockHost(() -> {}); var limits = ParserHardwareFixture.withMasterParser(new ComputerHostLimits(HARDWARE, ARCHIVE));
            var body = published.flow().orElseThrow();
            assertThrows(FlowPins.Unavailable.class,()->FlowPins.validate(body.program(published.archive()),
                ParserHardwareFixture.withIsa1a(new ComputerHostLimits(HARDWARE,ARCHIVE))),"The authored ANY signature requires Master as well as Parser");
            host.installFlow(body.program(published.archive()), new DataValue.RecordValue(Map.of("reading", reading)), limits, true);
            int work = 0;
            while (host.flowSnapshot().orElseThrow().state().status() != FlowRuntime.Status.COMPLETE) {
                assertTrue(++work < 100); host.step(limits, true);
                assertNotEquals(FlowRuntime.Status.FAILED, host.flowSnapshot().orElseThrow().state().status());
            }
            var locals = host.flowSnapshot().orElseThrow().state().locals();
            assertEquals(reading instanceof DataValue.IntegerValue n ? new DataValue.IntegerValue(n.value() + 1) : DataValue.NullValue.INSTANCE, locals.field("next"));
            assertEquals(new DataValue.BooleanValue(reading instanceof DataValue.IntegerValue), ((DataValue.RecordValue)locals.field("checked")).field("ok"));
            assertEquals(reading, locals.field("reading"));
        }
    }
}
