package com.nobodiiiii.createbiotech.content.biocomputing.network.serial;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import java.io.*;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;

class SerialDecodingTest {
    static final ArchiveLimits VALUES=new ArchiveLimits(100000,1000000,10000000);
    static final SerialRecordCodec.Limits LIMITS=new SerialRecordCodec.Limits(16384,1048576,VALUES);
    static byte[] incrementalEncode(DataValue packet){var state=SerialEncoding.begin(packet,LIMITS);for(int i=0;!state.done();i++){assertTrue(i<100000);state=state.advance();}assertNull(state.encoded().error());return state.encoded().bytes();}
    static SerialDecoding.State decoded(byte[] bytes,boolean nativeNbt,SerialRecordCodec.Limits limits){
        var queue=new SerialReceiveBuffer(new SerialReceiveBuffer.Limits(Math.max(1,bytes.length),2,Math.max(2,bytes.length*2L)));queue.offer(bytes);
        var state=SerialDecoding.begin(queue.head().orElseThrow(),nativeNbt,limits);long initial=state.storageUnits(),peak=initial;
        for(int i=0;!state.done();i++){assertTrue(i<100000);int before=state.consumedBytes();state=state.advance();assertTrue(state.consumedBytes()-before<=8);peak=Math.max(peak,state.storageUnits());assertEquals(1,queue.status().frames());}
        if(state.decoded().error()==null){assertEquals(bytes.length,state.consumedBytes());if(bytes.length>8)assertTrue(peak>initial);}
        return state;
    }
    static DataValue.NbtValue n(int tag,long value){return new DataValue.NbtValue(tag,new DataValue.IntegerValue(value));}
    static DataValue.NbtValue nativePacket(){var fields=new LinkedHashMap<String,DataValue>();fields.put("b",n(1,-128));fields.put("s",n(2,-32768));fields.put("i",n(3,Integer.MIN_VALUE));fields.put("l",n(4,Long.MIN_VALUE));
        fields.put("f-0",n(5,Integer.MIN_VALUE));fields.put("d-0",n(6,Long.MIN_VALUE));fields.put("fNaN",n(5,0x7fc00123));fields.put("dNaN",n(6,0x7ff8000000000123L));fields.put("Infinity",n(6,0x7ff0000000000000L));
        fields.put("UTF-\0-😀-\ud800",new DataValue.NbtValue(8,new DataValue.TextValue("native-\0-你好-😀-\ud800")));
        for(int tag:List.of(7,11,12))fields.put("array"+tag,new DataValue.NbtValue(tag,new DataValue.ListValue(List.of(new DataValue.IntegerValue(-1),new DataValue.IntegerValue(tag==7?127:tag==11?Integer.MAX_VALUE:Long.MAX_VALUE)))));
        fields.put("list",new DataValue.NbtValue(9,new DataValue.ListValue(List.of(n(2,1),n(2,-2)))));
        fields.put("empty-list",new DataValue.NbtValue(9,new DataValue.ListValue(List.of())));
        fields.put("nested",new DataValue.NbtValue(10,new DataValue.RecordValue(Map.of("value",n(3,37)))));
        return new DataValue.NbtValue(10,new DataValue.RecordValue(fields));
    }
    @Test void oldAndNewTypedRecordFramesPreserveEveryVariantAndBackwardsSharedReferences(){
        var shared=new DataValue.ListValue(List.of(new DataValue.TextValue("长文本-\0-😀-\ud800"),new DataValue.IntegerValue(37)));
        var fields=new LinkedHashMap<String,DataValue>();fields.put("null",DataValue.NullValue.INSTANCE);fields.put("boolean",new DataValue.BooleanValue(true));fields.put("integer",new DataValue.IntegerValue(Long.MIN_VALUE));fields.put("decimal",new DataValue.DecimalValue(-0.0));fields.put("error",new DataValue.ErrorValue("E\ud800","message\0😀"));fields.put("a",shared);fields.put("b",shared);fields.put("map",new DataValue.MapValue(Map.of("x",new DataValue.IntegerValue(4))));
        for(boolean v3:List.of(false,true)){if(v3)fields.put("native",nativePacket());var packet=new DataValue.RecordValue(fields);
            for(byte[] bytes:List.of(SerialRecordCodec.encode(packet,LIMITS),incrementalEncode(packet))){var state=decoded(bytes,false,LIMITS);assertNull(state.decoded().error());assertEquals(packet,state.decoded().value());assertTrue(state.steps()>bytes.length/8);}
        }
    }
    @Test void nativeAllTagsArraysUtfAndRawNegativeZeroNanPayloadBitsAreNeverCanonicalized(){
        for(var packet:List.of(nativePacket(),new DataValue.NbtValue(0,DataValue.NullValue.INSTANCE),n(5,Integer.MIN_VALUE),n(6,Long.MIN_VALUE),n(5,0x7fc00123),n(6,0x7ff8000000000123L))){
            var state=decoded(incrementalEncode(packet),true,LIMITS);assertNull(state.decoded().error());assertEquals(packet,state.decoded().value());assertTrue(state.parsedNodes()>0);
        }
        // Existing old encoder is an oracle only for tags without its signed-zero normalization.
        var packet=new DataValue.NbtValue(10,new DataValue.RecordValue(Map.of("text",new DataValue.NbtValue(8,new DataValue.TextValue("old-你好")),"integer",n(3,37))));
        assertEquals(packet,decoded(NativeNbtCodec.encode(packet,new NativeNbtCodec.Limits(16384,1048576,VALUES)),true,LIMITS).decoded().value());
    }
    @Test void malformedTrailingWrongFormatAndConfiguredBudgetsReturnFailuresWithoutClaim(){
        byte[] record=SerialRecordCodec.encode(new DataValue.RecordValue(Map.of("a",new DataValue.TextValue("limit"))),LIMITS);
        assertEquals("invalid_record",decoded(new byte[]{3,0,0,0,0,0,1},false,LIMITS).decoded().error());
        assertEquals("invalid_nbt",decoded(new byte[]{5,0,0,0,0},true,LIMITS).decoded().error());
        assertEquals("invalid_record",decoded(Arrays.copyOf(record,record.length+1),false,LIMITS).decoded().error());
        assertEquals("payload_too_large",decoded(record,false,new SerialRecordCodec.Limits(4,1048576,VALUES)).decoded().error());
        assertEquals("decode_limit",decoded(record,false,new SerialRecordCodec.Limits(16384,10,VALUES)).decoded().error());
        assertEquals("value_limit",decoded(record,false,new SerialRecordCodec.Limits(16384,1048576,new ArchiveLimits(1000,1,10000))).decoded().error());
        byte[] nativeText=incrementalEncode(new DataValue.NbtValue(8,new DataValue.TextValue("long text")));
        assertEquals("value_limit",decoded(nativeText,true,new SerialRecordCodec.Limits(16384,1048576,new ArchiveLimits(1000,1,10000))).decoded().error());
    }
    static byte[] wire(CompoundTag tag)throws IOException{var output=new ByteArrayOutputStream();NbtIo.write(tag,new DataOutputStream(output));return output.toByteArray();}
    @Test void longUnknownKindHasConstantBoundedDispatchAfterGenuinePaidUtfDecode()throws IOException{
        var row=new CompoundTag();row.putString("Kind","K".repeat(3000));var rows=new ListTag();rows.add(row);var root=new CompoundTag();root.putInt("Version",2);root.putInt("Root",0);root.put("Values",rows);
        var state=decoded(wire(root),false,LIMITS);assertEquals("invalid_record",state.decoded().error());assertTrue(state.steps()>6000,"UTF decode and immutable String seal are genuinely paid");assertTrue(state.maxKindDispatchChars()<=7,"Closed Kind validation must never scan the long unknown String again");
    }
    @Test void invalidForwardReferencesDuplicateRecordMembersAndFutureValueVersionStayBranchable()throws IOException{
        var archive=ValueNbtCodec.encode(new DataValue.RecordValue(Map.of("a",new DataValue.IntegerValue(1))),VALUES);
        var future=archive.copy();future.putInt("Version",5);assertEquals("unsupported_version",decoded(wire(future),false,LIMITS).decoded().error());
        var rows=archive.getList("Values",10);var root=rows.getCompound(rows.size()-1);var refs=root.getList("Fields",10);refs.getCompound(0).putInt("Value",rows.size()-1);
        assertEquals("invalid_record",decoded(wire(archive),false,LIMITS).decoded().error());
        refs.getCompound(0).putInt("Value",0);refs.add(refs.getCompound(0).copy());assertEquals("invalid_record",decoded(wire(archive),false,LIMITS).decoded().error());
    }
}
