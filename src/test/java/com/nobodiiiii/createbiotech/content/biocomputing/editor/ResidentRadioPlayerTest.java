package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ResidentRadioPlayerTest {
    @Test void tuningNeverAcceptsRemoteTargetsOrNarrowsAnOversizedBand() {
        var sample=new RadioControlData.Tune(2,true);
        assertEquals(sample,RadioControlData.Tune.decode(sample.encode()));
        assertThrows(IllegalArgumentException.class,()->RadioControlData.Tune.decode(new DataValue.RecordValue(
            Map.of("preset",new DataValue.IntegerValue(1L<<32),"armed",new DataValue.BooleanValue(true)))));
        var fields=new java.util.HashMap<>(sample.encode().fields());fields.put("target",new DataValue.TextValue("other computer"));
        assertThrows(IllegalArgumentException.class,()->RadioControlData.Tune.decode(new DataValue.RecordValue(fields)));
        var id=java.util.UUID.randomUUID();var limits=new ArchiveLimits(1000,10000,100000);
        var bytes=ProgramEditorWire.encodeValue(sample.encode(),limits);
        assertDoesNotThrow(()->new ProgramControlRequest(1,id,1,0,ComputerControlSession.Action.CONFIGURE_RADIO,bytes));
        assertThrows(IllegalArgumentException.class,()->new ProgramControlRequest(1,id,2,0,ComputerControlSession.Action.READ_RADIO,bytes));
    }

    @Test void playersCanPreviewBothUnmodifiedRadioProgramsFromTheShippedCatalog() {
        var loader = (java.util.function.Function<net.minecraft.resources.ResourceLocation,java.io.InputStream>) id ->
            getClass().getResourceAsStream("/assets/" + id.getNamespace() + "/" + id.getPath());
        var catalog = ProgramExampleCatalog.load(loader);
        var limits = new ArchiveLimits(262144,2097152,16777216);
        for (var id : java.util.List.of("radio_probe_send", "radio_probe_receive")) {
            var entry = catalog.stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow();
            var flow = ProgramExampleCatalog.preview(entry,loader,limits).flow().orElseThrow();
            assertEquals(1,flow.steps().size());
            if (id.endsWith("send")) {
                assertEquals("rf0",assertInstanceOf(FlowProgram.SerialSend.class,flow.steps().getFirst()).port());
                assertInstanceOf(DataValue.NbtValue.class, flow.initial().field("payload"));
            } else assertEquals("rf0",assertInstanceOf(FlowProgram.SerialReceive.class,flow.steps().getFirst()).port());
        }
    }
}
