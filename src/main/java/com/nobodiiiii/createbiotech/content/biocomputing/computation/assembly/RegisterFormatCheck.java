package com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;

/** Private immutable paid operation witness. Never reconstructed from a serialized success flag. */
final class RegisterFormatCheck {
    private final DataValue source;
    private final int version;
    private final RecipeValidation proof;
    private final boolean started, failed;
    private RegisterFormatCheck(int version,DataValue source,RecipeValidation proof,boolean started,boolean failed) {
        this.version=version;this.source=source;this.proof=proof;this.started=started;this.failed=failed;
    }
    static RegisterFormatCheck start(DataValue source,int version) { return new RegisterFormatCheck(version,Objects.requireNonNull(source),null,false,false); }
    RegisterFormatCheck advance() {
        if(done())throw new IllegalStateException("Format check already complete");
        try {
            if(!started) {
                if(source instanceof DataValue.RecipeValue typed && typed.version()!=version) return new RegisterFormatCheck(version,source,null,true,true);
                var raw=source instanceof DataValue.RecipeValue r?r.projection():source;
                if(!(raw instanceof DataValue.RecordValue record))return new RegisterFormatCheck(version,source,null,true,true);
                return new RegisterFormatCheck(version,source,RecipeValidation.begin("recipe",version,record),true,false);
            }
            return new RegisterFormatCheck(version,source,proof.advance(),true,false);
        } catch(IllegalArgumentException invalidProjection) {
            return new RegisterFormatCheck(version,source,null,true,true);
        }
    }
    boolean done() { return started && (failed || proof.done()); }
    long proofUnits() { return proof==null?0:proof.storageUnits(); }
    /** Root expansion adds at most five six-unit checks; all other advances add at most one. */
    long nextProofBound() {
        if(proof!=null)return Math.addExact(proofUnits(),5L*6);
        var raw=source instanceof DataValue.RecipeValue r?r.projection():source;
        return raw instanceof DataValue.RecordValue?Math.addExact(raw.storageUnits(),4L+6):0;
    }
    long progressUnits(RegisterState state) {
        // Actual serialized fields and key lengths, including source and frame descriptors.
        long units=1;
        for(var entry:Map.of("format",7L,"semantic_version",1L,"source",source.storageUnits(),
            "image",1L+state.image().length(),"pc",1L,"started",1L,"failed",1L,
            "frames",proof==null?1L:proof.controlsStorageUnits()).entrySet())
            units=Math.addExact(units,Math.addExact(1L+entry.getKey().length(),entry.getValue()));
        return units;
    }
    DataValue.RecordValue progress(RegisterState state) {
        return new DataValue.RecordValue(Map.of("format",new DataValue.TextValue("recipe"),"semantic_version",new DataValue.IntegerValue(version),
            "source",source,"image",new DataValue.TextValue(state.image()),"pc",new DataValue.IntegerValue(state.pc()),
            "started",new DataValue.BooleanValue(started),"failed",new DataValue.BooleanValue(failed),
            "frames",proof==null?new DataValue.ListValue(List.of()):proof.controls()));
    }
    long resultUnits() {
        return 11L + (failed?1:2L+DataValue.RecipeValue.FORMAT.length()+proof.projection().storageUnits());
    }
    DataValue.RecordValue result() {
        if(!done())throw new IllegalStateException("Incomplete format check");
        return new DataValue.RecordValue(Map.of("ok",new DataValue.BooleanValue(!failed),
            "value",failed?DataValue.NullValue.INSTANCE:proof.finish()));
    }
    static long workBound(DataValue source) {
        try{return Math.addExact(8,Math.multiplyExact(8,source.storageUnits()));}
        catch(ArithmeticException enormous){return Long.MAX_VALUE;}
    }
    static long ownedUnits(DataValue.RecordValue progress) {
        var source=progress.field("source");var frames=(DataValue.ListValue)progress.field("frames");
        boolean hasProof=((DataValue.BooleanValue)progress.field("started")).value()&&!((DataValue.BooleanValue)progress.field("failed")).value();
        DataValue raw=source instanceof DataValue.RecipeValue r?r.projection():source;
        return Math.addExact(progress.storageUnits(),hasProof?Math.addExact(4L+6L*frames.values().size(),raw.storageUnits()):0);
    }
}
