package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class EditorValueFormatTest {
    @Test void editorWireDeduplicationRemapsNativeBodyReferencesWithoutMergingDifferentTags() {
        var value = EditorValueFormat.parse(EditorValueFormat.Kind.NBT, "{b:1b,s:1s,i:1,l:1L,nested:{b:1b},list:[1,1,1]}");
        var limits = new com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits(10000, 100000, 1000000);
        assertEquals(value, ProgramEditorWire.decodeValue(ProgramEditorWire.encodeValue(value, limits), limits));
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of("packet", DataType.Scalar.NBT), List.of(
            new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.SerialSend("send", "s0", "packet", "sent")),
            new DataValue.RecordValue(Map.of("packet", value)))));
        var document = workspace.snapshot();
        assertEquals(document, ProgramDocumentWire.decode(ProgramDocumentWire.encode(document, limits), limits));
    }
    @Test void nativeLiteralKeepsItsTagKindsInsteadOfBecomingAJsonDictionary() {
        var value = EditorValueFormat.parse(EditorValueFormat.Kind.NBT, "{b:1b,s:1s,i:1,l:1L,f:1.25f,d:1.25d,a:[B;1b,-2b],ids:[I;1,2],big:[L;3L]}");
        assertInstanceOf(DataValue.NbtValue.class, value);
        var constant = NodeParameter.parse(ComputerNodePalette.Kind.NBT, EditorValueFormat.format(value));
        assertEquals(new ComputationGraph.Constant(value), constant);
        assertEquals(ComputerNodePalette.Kind.NBT, NodeParameter.kind(constant));
        assertEquals(value, EditorValueFormat.parse(EditorValueFormat.Kind.NBT, NodeParameter.text(constant)));
        assertEquals(value, EditorValueFormat.parse(EditorValueFormat.Kind.NBT, EditorValueFormat.format(value)));
        assertThrows(IllegalArgumentException.class, () -> EditorValueFormat.parse(EditorValueFormat.Kind.NBT, "{} trailing"));
        assertThrows(IllegalArgumentException.class, () -> EditorValueFormat.parse(EditorValueFormat.Kind.NBT, "[".repeat(200) + "0" + "]".repeat(200)));
    }
    @Test void structuredConstantsRetainNamesNumbersAndNullWithoutWorldReferences() {
        var value=EditorValueFormat.parse(EditorValueFormat.Kind.JSON,"{\"samples\":[1,2],\"chosen\":null}");
        assertEquals(new DataValue.RecordValue(Map.of("samples",new DataValue.ListValue(List.of(new DataValue.IntegerValue(1),new DataValue.IntegerValue(2))),"chosen",DataValue.NullValue.INSTANCE)),value);
        assertEquals(value,EditorValueFormat.parse(EditorValueFormat.Kind.JSON,EditorValueFormat.format(value)));
        assertThrows(IllegalArgumentException.class,()->EditorValueFormat.parse(EditorValueFormat.Kind.INTEGER,"1.5"));
        assertThrows(IllegalArgumentException.class,()->EditorValueFormat.parse(EditorValueFormat.Kind.BOOLEAN,"maybe"));
    }
    @Test void excessiveDepthAndDuplicateKeysAreNotSilentlyCoerced() {
        assertThrows(IllegalArgumentException.class,()->EditorValueFormat.parse(EditorValueFormat.Kind.JSON,"[".repeat(80)+"0"+"]".repeat(80)));
        assertThrows(IllegalArgumentException.class,()->EditorValueFormat.parse(EditorValueFormat.Kind.JSON,"{\"a\":1,\"a\":2}"));
    }
}
