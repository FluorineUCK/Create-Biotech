package com.nobodiiiii.createbiotech.content.biocomputing.network.serial;
import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class SerialEncodingTest {
    static final ArchiveLimits ARCHIVE=new ArchiveLimits(100000,1000000,10000000);
    static final SerialRecordCodec.Limits LIMITS=new SerialRecordCodec.Limits(65536,1048576,ARCHIVE);
    static SerialEncoding.State encode(DataValue value,SerialRecordCodec.Limits limits) {
        var state=SerialEncoding.begin(value,limits);
        for(int steps=0;!state.done();steps++){assertTrue(steps<1000000);state=state.advance();}
        return state;
    }
    @Test void nativeRootsRetainWidthsRawBitsArraysAndModifiedUtfAcrossActualMicrosteps() {
        var samples=new ArrayList<DataValue.NbtValue>();
        samples.add(new DataValue.NbtValue(0,DataValue.NullValue.INSTANCE));
        for(int tag=1;tag<=6;tag++)samples.add(new DataValue.NbtValue(tag,new DataValue.IntegerValue(tag==5?0x7fc00011:tag==6?0x7ff8000000000011L:-12)));
        samples.add(new DataValue.NbtValue(8,new DataValue.TextValue("\u0000你好😀\ud800")));
        for(int tag:List.of(7,11,12))samples.add(new DataValue.NbtValue(tag,new DataValue.ListValue(List.of(new DataValue.IntegerValue(-7),new DataValue.IntegerValue(42)))));
        samples.add(new DataValue.NbtValue(9,new DataValue.ListValue(List.of(samples.get(2),samples.get(2)))));
        samples.add(new DataValue.NbtValue(9,new DataValue.ListValue(List.of())));
        samples.add(new DataValue.NbtValue(10,new DataValue.RecordValue(Map.of("名字",samples.get(7),"samples",samples.get(8)))));
        for(var n:samples){var state=encode(n,LIMITS);assertNull(state.encoded().error());
            if(n.tagId()!=5 && n.tagId()!=6 && n.tagId()!=10)assertArrayEquals(NativeNbtCodec.encode(n,new NativeNbtCodec.Limits(65536,1048576,ARCHIVE)),state.encoded().bytes());
            assertEquals(n,NativeNbtCodec.decode(state.encoded().bytes(),new NativeNbtCodec.Limits(65536,1048576,ARCHIVE)));}
    }
    @Test void allTypedKindsPreserveSemanticsIncludingSignedZeroLoneSurrogatesNativeNbtAndSharedChildren() {
        var child=new DataValue.RecordValue(Map.of("nested",new DataValue.TextValue("重复")));
        var value=new DataValue.RecordValue(Map.of("null",DataValue.NullValue.INSTANCE,"boolean",new DataValue.BooleanValue(true),
            "integer",new DataValue.IntegerValue(Long.MIN_VALUE),"decimal",new DataValue.DecimalValue(-0.0),
            "text",new DataValue.TextValue("\ud800\u0000😀"),"error",new DataValue.ErrorValue("\udc00","err"),
            "list",new DataValue.ListValue(List.of(child,child)),"map",new DataValue.MapValue(Map.of("key",child)),
            "nbt",new DataValue.NbtValue(11,new DataValue.ListValue(List.of(new DataValue.IntegerValue(12))))));
        var encoded=encode(value,LIMITS);assertNull(encoded.encoded().error());
        assertEquals(value,SerialRecordCodec.decode(encoded.encoded().bytes(),LIMITS));
        assertEquals(Double.doubleToRawLongBits(-0.0),Double.doubleToRawLongBits(((DataValue.DecimalValue)SerialRecordCodec.decode(encoded.encoded().bytes(),LIMITS).field("decimal")).value()));
        var shortValue=new DataValue.RecordValue(Map.of("text",new DataValue.TextValue("x")));
        var longValue=new DataValue.RecordValue(Map.of("text",new DataValue.TextValue("x".repeat(100))));
        assertTrue(encode(longValue,LIMITS).steps()>encode(shortValue,LIMITS).steps()+100);
    }
    @Test void nativeArrayNodesAndCompoundNamesRespectExistingLimitsBeforeProducingAFrame() {
        var a=new DataValue.NbtValue(7,new DataValue.ListValue(List.of(new DataValue.IntegerValue(1),new DataValue.IntegerValue(2))));
        var limits=new SerialRecordCodec.Limits(4096,1048576,new ArchiveLimits(2,100,10000));
        assertEquals("value_limit",encode(a,limits).encoded().error());
        var n=new DataValue.NbtValue(10,new DataValue.RecordValue(Map.of("longName",new DataValue.NbtValue(1,new DataValue.IntegerValue(1)))));
        assertEquals("value_limit",encode(n,new SerialRecordCodec.Limits(4096,1048576,new ArchiveLimits(100,3,10000))).encoded().error());
        assertEquals("invalid_nbt",encode(new DataValue.NbtValue(8,new DataValue.TextValue("x".repeat(65536))),LIMITS).encoded().error());
        assertEquals("payload_too_large",encode(new DataValue.RecordValue(Map.of("text",new DataValue.TextValue("x".repeat(100)))),new SerialRecordCodec.Limits(20,1048576,ARCHIVE)).encoded().error());
    }

    @Test void exactNativeDepthAndRawSignedZeroArePreservedWithoutHostObjectConversion() {
        var scalar=new DataValue.NbtValue(1,new DataValue.IntegerValue(1));
        DataValue.NbtValue deep=scalar;
        for(int i=0;i<128;i++)deep=new DataValue.NbtValue(9,new DataValue.ListValue(List.of(deep)));
        assertEquals("value_limit",encode(deep,LIMITS).encoded().error());
        for(int tag:List.of(5,6))for(long bits:List.of(0L,tag==5?0x80000000L:Long.MIN_VALUE)){
            var n=new DataValue.NbtValue(tag,new DataValue.IntegerValue(tag==5?(int)bits:bits));var state=encode(n,LIMITS);
            assertNull(state.encoded().error());
            // TX preserves the sign bit. The existing Minecraft-tag based legacy RX
            // normalizes both floating zero encodings; this is not a lossless RX claim.
            assertEquals(bits==0?0:0x80,state.encoded().bytes()[3]&255);
            assertEquals(new DataValue.NbtValue(tag,new DataValue.IntegerValue(0)),NativeNbtCodec.decode(state.encoded().bytes(),new NativeNbtCodec.Limits(65536,1048576,ARCHIVE)));
            if(bits==0)assertArrayEquals(NativeNbtCodec.encode(n,new NativeNbtCodec.Limits(65536,1048576,ARCHIVE)),state.encoded().bytes());
        }
        for(var n:List.of(new DataValue.NbtValue(5,new DataValue.IntegerValue(Float.floatToRawIntBits(1.5f))),new DataValue.NbtValue(6,new DataValue.IntegerValue(Double.doubleToRawLongBits(3.25)))))
            assertArrayEquals(NativeNbtCodec.encode(n,new NativeNbtCodec.Limits(65536,1048576,ARCHIVE)),encode(n,LIMITS).encoded().bytes());
    }
}
