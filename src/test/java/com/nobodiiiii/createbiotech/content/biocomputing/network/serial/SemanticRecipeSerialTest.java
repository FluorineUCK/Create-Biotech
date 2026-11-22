package com.nobodiiiii.createbiotech.content.biocomputing.network.serial;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import net.minecraft.nbt.*;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SemanticRecipeSerialTest {
    static final SerialRecordCodec.Limits LIMITS=new SerialRecordCodec.Limits(65536,16000000,FoundationRecipeTest.LIMITS);
    static SerialDecoding.State decode(byte[] bytes) {
        var queue=new SerialReceiveBuffer(new SerialReceiveBuffer.Limits(bytes.length,2,bytes.length*2L));queue.offer(bytes);
        var state=SerialDecoding.begin(queue.head().orElseThrow(),false,LIMITS);
        for(int i=0;!state.done();i++){assertTrue(i<1000000);int before=state.consumedBytes();state=state.advance();assertTrue(state.consumedBytes()-before<=8);}
        assertEquals(1,queue.status().frames());return state;
    }
    static byte[] wire(CompoundTag archive)throws IOException { var b=new ByteArrayOutputStream();NbtIo.write(archive,new DataOutputStream(b));return b.toByteArray(); }
    @Test void bothCodecPathsPreserveNominalNestedValuesAndNativeData() {
        for(var raw:List.of(FoundationRecipeTest.projection(false),FoundationRecipeTest.projection(true),FoundationRecipeTest.shapedProjection())) {
            var shaped=((DataValue.RecordValue)raw.field("body")).fields().containsKey("crafting_slots");
            var recipe=new DataValue.RecipeValue("recipe",shaped?2:1,raw);
            var packet=new DataValue.RecordValue(Map.of("raw",raw,"nominal",new DataValue.ListValue(List.of(recipe,recipe))));
            var tx=SerialEncoding.begin(packet,LIMITS);while(!tx.done())tx=tx.advance();
            assertNull(tx.encoded().error());
            for(byte[] bytes:List.of(tx.encoded().bytes(),SerialRecordCodec.encode(packet,LIMITS))) {
                var rx=decode(bytes);assertNull(rx.decoded().error());assertEquals(packet,rx.decoded().value());
                assertEquals(packet,SerialRecordCodec.decode(bytes,LIMITS));
            }
        }
    }
    @Test void rejectsUnknownSemanticFormatVersionAndDowngradedEnvelope()throws IOException {
        var packet=new DataValue.RecordValue(Map.of("recipe",new DataValue.RecipeValue("recipe",1,FoundationRecipeTest.projection(false))));
        var archive=ValueNbtCodec.encode(packet,FoundationRecipeTest.LIMITS);
        var rows=archive.getList("Values",10);
        CompoundTag semantic=null;for(Tag entry:rows)if(((CompoundTag)entry).getString("Kind").equals("recipe"))semantic=(CompoundTag)entry;
        assertNotNull(semantic);
        semantic.putString("Fmt","unknown");
        assertEquals("invalid_record",decode(wire(archive)).decoded().error());
        assertThrows(ArchiveException.class,()->ValueNbtCodec.decode(archive,FoundationRecipeTest.LIMITS));
        semantic.putString("Fmt","recipe");semantic.putInt("SemanticVersion",3);
        assertEquals("invalid_record",decode(wire(archive)).decoded().error());
        semantic.putInt("SemanticVersion",1);archive.putInt("Version",3);
        assertEquals("invalid_record",decode(wire(archive)).decoded().error());
    }
}
