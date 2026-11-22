package com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;

/** Frozen software-any-abi-1 representation, checked only at compiler-marked complete slots.
 * Execution conversions are ordinary generated ISA, never methods in this class. */
public final class SoftwareAnyAbi {
    private SoftwareAnyAbi() {}
    public static final DataType.RecordType TYPE=new DataType.RecordType(Map.of("kind",DataType.Scalar.INTEGER,"payload",DataType.Scalar.NBT));
    public static boolean canonical(DataValue value) {
        if(!(value instanceof DataValue.RecordValue record)||!record.fields().keySet().equals(TYPE.fields().keySet())
                ||!(record.field("kind") instanceof DataValue.IntegerValue kind)||!(record.field("payload") instanceof DataValue.NbtValue payload))return false;
        long k=kind.value();
        return k==0?payload.tagId()==0:k==1?payload.tagId()==4:k==2?payload.tagId()==1&&payload.value() instanceof DataValue.IntegerValue bit&&(bit.value()==0||bit.value()==1):k==3?payload.tagId()==8:k==4;
    }
}
