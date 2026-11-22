package com.nobodiiiii.createbiotech.content.biocomputing.network.serial;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.SerialHardware;
import java.io.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Fixed-size private continuation credential, not a wire protocol, result/history or player operand.
 * Only bounded closed metadata is authenticated; no player value/payload traversal. */
public final class SerialReceiveProof {
    private SerialReceiveProof() {}
    private static final Set<String> FAILURES=Set.of("","stale","configuration_changed","hardware_changed","unavailable","missing_port","closed","resource_limit","invalid_save","unsupported_version");
    public record Cursor(UUID device,UUID face,UUID head,UUID flow,String image,int pc,long origin,long paid,
            int length,int consumed,long witness,boolean nativeNbt,String pin,SerialRecordCodec.Limits limits,
            DataValue.RecordValue configuration,String failure) {
        public Cursor {
            Objects.requireNonNull(device);Objects.requireNonNull(face);Objects.requireNonNull(head);Objects.requireNonNull(flow);Objects.requireNonNull(limits);
            if(image==null||!image.matches("[0-9a-f]{64}")||pc<0||origin<0||paid<1||length<1||consumed<0||consumed>length
                ||paid>4096L*(length+(long)limits.values().maxNodes())||pin==null||pin.length()>64||pin.isBlank()||failure==null||failure.length()>21||!FAILURES.contains(failure))throw new IllegalArgumentException("Invalid receive credential fields");
            exact(configuration,"configured","side","enabled","gateway","epoch","hardware");
            if(!bool(configuration,"configured")||number(configuration,"side")<0||number(configuration,"side")>=6||number(configuration,"epoch")<0)throw new IllegalArgumentException("Invalid receive configuration");
            bool(configuration,"enabled");if(text(configuration,"gateway").length()>512)throw new IllegalArgumentException("Receive gateway metadata exceeds structural bound");
            var h=hardware(configuration);exact(h,"bits","frame","frames","bytes","epoch");if(number(h,"epoch")<0)throw new IllegalArgumentException("Invalid hardware epoch");
            new SerialHardware.Port(number(h,"bits"),Math.toIntExact(number(h,"frame")),Math.toIntExact(number(h,"frames")),number(h,"bytes"));
        }
        byte[] bytes() {
            try {var bytes=new ByteArrayOutputStream(1024);var out=new DataOutputStream(bytes);out.writeInt(1);
                for(UUID id:List.of(device,face,head,flow)){out.writeLong(id.getMostSignificantBits());out.writeLong(id.getLeastSignificantBits());}
                out.writeUTF(image);out.writeInt(pc);out.writeLong(origin);out.writeLong(paid);out.writeInt(length);out.writeInt(consumed);out.writeLong(witness);out.writeBoolean(nativeNbt);out.writeUTF(pin);
                out.writeInt(limits.maxEncodedBytes());out.writeLong(limits.maxDecodeHeapBytes());out.writeInt(limits.values().maxNodes());out.writeLong(limits.values().maxStringChars());out.writeLong(limits.values().maxValueUnits());
                out.writeLong(number(configuration,"side"));out.writeBoolean(bool(configuration,"enabled"));out.writeUTF(text(configuration,"gateway"));out.writeLong(number(configuration,"epoch"));
                var h=hardware(configuration);for(String key:List.of("bits","frame","frames","bytes","epoch"))out.writeLong(number(h,key));out.writeUTF(failure);
                return bytes.toByteArray();
            }catch(IOException impossible){throw new AssertionError(impossible);}
        }
    }
    static String sign(byte[] key,Cursor cursor) {
        try{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal(cursor.bytes()));}
        catch(java.security.GeneralSecurityException unavailable){throw new IllegalStateException(unavailable);}
    }
    static boolean verify(byte[] key,Cursor cursor,String certificate){return certificate!=null&&certificate.matches("[0-9a-f]{64}")&&java.security.MessageDigest.isEqual(HexFormat.of().parseHex(certificate),HexFormat.of().parseHex(sign(key,cursor)));}
    private static void exact(DataValue.RecordValue value,String... keys){if(value==null||!value.fields().keySet().equals(Set.of(keys)))throw new IllegalArgumentException("Receive credential shape");}
    private static long number(DataValue.RecordValue value,String name){if(!(value.fields().get(name) instanceof DataValue.IntegerValue n))throw new IllegalArgumentException("Receive credential number");return n.value();}
    private static boolean bool(DataValue.RecordValue value,String name){if(!(value.fields().get(name) instanceof DataValue.BooleanValue b))throw new IllegalArgumentException("Receive credential flag");return b.value();}
    private static String text(DataValue.RecordValue value,String name){if(!(value.fields().get(name) instanceof DataValue.TextValue t))throw new IllegalArgumentException("Receive credential text");return t.value();}
    private static DataValue.RecordValue hardware(DataValue.RecordValue config){if(!(config.fields().get("hardware") instanceof DataValue.RecordValue h))throw new IllegalArgumentException("Receive hardware credential");return h;}
}
