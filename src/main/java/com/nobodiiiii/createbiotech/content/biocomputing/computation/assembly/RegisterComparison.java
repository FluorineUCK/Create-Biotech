package com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;
import java.util.function.LongConsumer;

/** Source-free wire cursor and a private generic comparison witness. One advance is actual typed work. */
public final class RegisterComparison {
    private final long paid;
    private final DataValue.RecordValue progress;
    private ValueEqualityTask.Frozen witness;
    private ValueTypeTask typing;
    private RegisterFormatCheck formatCheck;
    private boolean presenceBound;
    private static final long PRESENCE_UNITS=cursor(0,0,-1).storageUnits();
    private Map<Integer, DataValue> sources;
    private String image;
    private int pc;
    public RegisterComparison(long paid, DataValue.RecordValue progress) {
        if (paid < 1 || paid == Long.MAX_VALUE) invalid();
        this.paid = paid; this.progress = Objects.requireNonNull(progress);
    }
    public long paidWork() { return paid; }
    public long ownedUnits() {
        if (progress.fields().containsKey("format")) return RegisterFormatCheck.ownedUnits(progress);
        if (progress.fields().containsKey("typing")) return ValueTypeTask.checkpointUnits(progress);
        return progress.fields().containsKey("index")?progress.storageUnits():ValueEqualityTask.checkpointUnits(progress);
    }
    private ValueEqualityTask task(RegisterState state, RegisterProgram.ValueEquals op) {
        checkBinding(state);
        return ValueEqualityTask.resume(state.registers().get(op.left()), state.registers().get(op.right()), witness);
    }
    private void checkBinding(RegisterState state) {
        if (witness == null && !presenceBound && typing == null && formatCheck == null || sources != state.registers() || !image.equals(state.image()) || pc != state.pc()) invalid();
    }
    private void bind(RegisterState state, ValueEqualityTask task) {
        witness = task.freeze(); sources = state.registers(); image = state.image(); pc = state.pc();
    }
    static RegisterComparison start(RegisterState state, RegisterProgram.ValueEquals op, LongConsumer reserve) {
        var task = ValueEqualityTask.start(state.registers().get(op.left()), state.registers().get(op.right()), "assembly", reserve);
        var result = new RegisterComparison(1, checkpoint(task, reserve, true, ValueEqualityTask::checkpoint)); result.bind(state, task); return result;
    }
    RegisterComparison advance(RegisterState state, RegisterProgram.ValueEquals op, LongConsumer reserve) {
        reserve.accept(ownedUnits());
        var task = task(state, op);
        task.advance(extra -> reserve.accept(Math.addExact(task.liveUnits(), extra)));
        var result = new RegisterComparison(Math.addExact(paid, 1), checkpoint(task, reserve, true, ValueEqualityTask::checkpoint)); result.bind(state, task); return result;
    }
    boolean complete(RegisterState state, RegisterProgram.ValueEquals op) { checkBinding(state); return ((DataValue.ListValue)progress.field("frames")).values().isEmpty(); }
    DataValue.BooleanValue result(RegisterState state, RegisterProgram.ValueEquals op) { if (!complete(state, op)) throw new IllegalStateException("Incomplete comparison"); return new DataValue.BooleanValue(!((DataValue.BooleanValue)progress.field("unequal")).value()); }
    /** Cold validation reconstructs only a temporary generic cursor once; hot ticks use the frozen witness.
     * Exact traversal proves the paid count, not just an attacker-supplied offset. The temporary peak is
     * reserved before frames are allocated. Completed progress never skips source validation. */
    public void validate(RegisterState state, RegisterProgram.ValueEquals op, LongConsumer reserve) {
        validate(state, op, reserve, ValueEqualityTask::checkpoint);
    }
    /** Package-local allocation collaborator; production always materializes the genuine generic cursor. */
    void validate(RegisterState state, RegisterProgram.ValueEquals op, LongConsumer reserve,
            java.util.function.Function<ValueEqualityTask, DataValue.RecordValue> materialize) {
        if (presenceBound) invalid();
        if (witness != null) { checkBinding(state); return; }
        var task = ValueEqualityTask.start(state.registers().get(op.left()), state.registers().get(op.right()), "assembly", reserve);
        for (long work = 1; work < paid; work++) {
            if (task.complete()) invalid();
            task.advance(extra -> reserve.accept(Math.addExact(task.liveUnits(), extra)));
        }
        // The saved retained ledger already reserves the replacement immutable witness.
        // Only the working cursor and genuine validation snapshot are additional cold temporaries.
        var actual = checkpoint(task, reserve, false, materialize);
        if (!actual.equals(progress)) invalid();
        bind(state, task);
    }
    private static DataValue.RecordValue checkpoint(ValueEqualityTask task, LongConsumer reserve, boolean newWitness,
            java.util.function.Function<ValueEqualityTask, DataValue.RecordValue> materialize) {
        long peak = Math.addExact(task.liveUnits(), task.checkpointStorageUnits());
        // Start/advance retains the previous cursor, so its new immutable witness is additional
        // until atomic publication. Cold validation fills the witness already in the retained ledger.
        if (newWitness) peak = Math.addExact(peak, task.liveUnits());
        reserve.accept(peak);
        return materialize.apply(task);
    }
    /** Presence walks immutable named occurrences; each paid step compares at most one UTF-16 unit.
     * Reuses the comparison envelope/owner RAM admission without materializing a key list or payload copy. */
    private static List<Map.Entry<String,DataValue>> entries(RegisterState state,RegisterProgram.HasField op) {
        var value=state.registers().get(op.from());
        if(!(state.registers().get(op.key()) instanceof DataValue.TextValue))throw new ComputationException(ComputationException.Code.TYPE_MISMATCH,"assembly","Presence key must be text");
        if(value instanceof DataValue.RecordValue r)return r.indexedFields();
        if(value instanceof DataValue.MapValue m)return m.indexedEntries();
        throw new ComputationException(ComputationException.Code.TYPE_MISMATCH,"assembly","Presence source must be record or map");
    }
    private static DataValue.RecordValue cursor(int index,int offset,int result) {
        return new DataValue.RecordValue(Map.of("index",new DataValue.IntegerValue(index),"offset",new DataValue.IntegerValue(offset),"result",new DataValue.IntegerValue(result)));
    }
    private void bindPresence(RegisterState state){presenceBound=true;sources=state.registers();image=state.image();pc=state.pc();}
    static RegisterComparison start(RegisterState state,RegisterProgram.HasField op,LongConsumer reserve) {
        var fields=entries(state,op);reserve.accept(PRESENCE_UNITS);var p=cursor(0,0,fields.isEmpty()?0:-1);
        var next=new RegisterComparison(1,p);next.bindPresence(state);return next;
    }
    RegisterComparison advance(RegisterState state,RegisterProgram.HasField op,LongConsumer reserve) {
        checkBinding(state);var fields=entries(state,op);String key=((DataValue.TextValue)state.registers().get(op.key())).value();
        int index=(int)((DataValue.IntegerValue)progress.field("index")).value(),offset=(int)((DataValue.IntegerValue)progress.field("offset")).value();
        if(completePresence(state))throw new IllegalStateException("Presence already complete");
        String candidate=fields.get(index).getKey();int result=-1;
        if(offset<key.length() && offset<candidate.length() && key.charAt(offset)==candidate.charAt(offset))offset++;
        else if(offset==key.length() && offset==candidate.length())result=1;
        else {index++;offset=0;if(index==fields.size())result=0;}
        reserve.accept(Math.addExact(ownedUnits(),PRESENCE_UNITS));var p=cursor(index,offset,result);
        var next=new RegisterComparison(Math.addExact(paid,1),p);next.bindPresence(state);return next;
    }
    boolean completePresence(RegisterState state){checkBinding(state);return ((DataValue.IntegerValue)progress.field("result")).value()>=0;}
    DataValue.BooleanValue presenceResult(RegisterState state){if(!completePresence(state))throw new IllegalStateException("Presence incomplete");return new DataValue.BooleanValue(((DataValue.IntegerValue)progress.field("result")).value()==1);}
    public void validate(RegisterState state,RegisterProgram.HasField op,LongConsumer reserve) {
        if(presenceBound){checkBinding(state);return;}
        var fields=entries(state,op);String key=((DataValue.TextValue)state.registers().get(op.key())).value();
        // Bound untrusted paid work before replay. Every comparison consumes either a character or a field.
        long bound=Math.addExact(2,Math.multiplyExact((long)fields.size(),Math.addExact(1L,key.length())));
        if(paid>bound)invalid();
        RegisterComparison actual=start(state,op,reserve);
        for(long i=1;i<paid;i++){if(actual.completePresence(state))invalid();actual=actual.advance(state,op,reserve);}
        if(!actual.progress.equals(progress))invalid();bindPresence(state);
    }
    public DataValue.RecordValue value() {
        return new DataValue.RecordValue(Map.of("version", new DataValue.IntegerValue(progress.fields().containsKey("format")?4:progress.fields().containsKey("typing")?3:progress.fields().containsKey("index")?2:1), "paid", new DataValue.IntegerValue(paid), "progress", progress));
    }
    private void bindType(RegisterState state, ValueTypeTask task) {
        typing = task; sources = state.registers(); image = state.image(); pc = state.pc();
    }
    static RegisterComparison start(RegisterState state, RegisterProgram.ValueCheck op, ValueTypeTask.Schema schema, LongConsumer reserve) {
        var task = ValueTypeTask.start(schema, state.registers().get(op.from()), reserve);
        var next = new RegisterComparison(1, typeCheckpoint(task, reserve, true)); next.bindType(state, task); return next;
    }
    RegisterComparison advance(RegisterState state, RegisterProgram.ValueCheck op, LongConsumer reserve) {
        checkBinding(state); typing.validateSource(state.registers().get(op.from()));
        reserve.accept(ownedUnits());
        var nextTask = typing.advance(extra -> reserve.accept(Math.addExact(typing.liveUnits(), extra)));
        var next = new RegisterComparison(Math.addExact(paid, 1), typeCheckpoint(nextTask, reserve, true)); next.bindType(state, nextTask); return next;
    }
    boolean completeType(RegisterState state) { checkBinding(state); return typing.complete(); }
    DataValue.RecordValue typeResult(RegisterState state, RegisterProgram.ValueCheck op) {
        if (!completeType(state)) throw new IllegalStateException("Type check incomplete");
        boolean matches = typing.matches();
        return new DataValue.RecordValue(Map.of("ok", new DataValue.BooleanValue(matches),
            "value", matches ? state.registers().get(op.from()) : DataValue.NullValue.INSTANCE));
    }
    public void validate(RegisterState state, RegisterProgram.ValueCheck op, ValueTypeTask.Schema schema, LongConsumer reserve) {
        if (presenceBound || witness != null) invalid();
        if (typing != null) { checkBinding(state); typing.validateSource(state.registers().get(op.from())); return; }
        var source = state.registers().get(op.from());
        if (paid > schema.workBound(source)) invalid();
        var task = ValueTypeTask.start(schema, source, reserve);
        for (long work = 1; work < paid; work++) {
            if (task.complete()) invalid();
            long live = task.liveUnits(); task = task.advance(extra -> reserve.accept(Math.addExact(live, extra)));
        }
        if (!typeCheckpoint(task, reserve, false).equals(progress)) invalid();
        bindType(state, task);
    }
    private static DataValue.RecordValue typeCheckpoint(ValueTypeTask task, LongConsumer reserve, boolean newWitness) {
        long peak = Math.addExact(task.liveUnits(), task.checkpointStorageUnits());
        if (newWitness) peak = Math.addExact(peak, task.liveUnits());
        reserve.accept(peak); return task.checkpoint();
    }
    private void bindFormat(RegisterState state,RegisterFormatCheck task) {
        formatCheck=task;sources=state.registers();image=state.image();pc=state.pc();
    }
    private static RegisterComparison formatCursor(RegisterState state,long paid,RegisterFormatCheck task,LongConsumer reserve) {
        reserve.accept(Math.addExact(task.proofUnits(),task.progressUnits(state)));
        var progress=task.progress(state);
        reserve.accept(Math.addExact(progress.storageUnits(),task.proofUnits()));
        var cursor=new RegisterComparison(paid,progress);cursor.bindFormat(state,task);return cursor;
    }
    static RegisterComparison start(RegisterState state,RegisterProgram.FormatCheck op,LongConsumer reserve) {
        var source=state.registers().get(op.from());
        return formatCursor(state,1,RegisterFormatCheck.start(source,op.version()),reserve);
    }
    RegisterComparison advance(RegisterState state,RegisterProgram.FormatCheck op,LongConsumer reserve) {
        checkBinding(state);
        reserve.accept(Math.addExact(ownedUnits(),formatCheck.nextProofBound()));
        return formatCursor(state,Math.addExact(paid,1),formatCheck.advance(),reserve);
    }
    boolean completeFormat(RegisterState state) { checkBinding(state);return formatCheck.done(); }
    long formatResultUnits(RegisterState state) { if(!completeFormat(state))throw new IllegalStateException("Incomplete format check");return formatCheck.resultUnits(); }
    DataValue.RecordValue formatResult(RegisterState state) { if(!completeFormat(state))throw new IllegalStateException("Incomplete format check");return formatCheck.result(); }
    public void validate(RegisterState state,RegisterProgram.FormatCheck op,LongConsumer reserve) {
        if(typing!=null||witness!=null||presenceBound)invalid();
        if(formatCheck!=null){checkBinding(state);return;}
        if(paid>RegisterFormatCheck.workBound(state.registers().get(op.from())))invalid();
        var actual=start(state,op,reserve);
        for(long i=1;i<paid;i++) {
            if(actual.completeFormat(state))invalid();
            actual=actual.advance(state,op,reserve);
        }
        if(!actual.progress.equals(progress))invalid();
        bindFormat(state,actual.formatCheck);
    }
    @Override public boolean equals(Object other) { return other instanceof RegisterComparison c && paid == c.paid && progress.equals(c.progress); }
    @Override public int hashCode() { return Objects.hash(paid, progress); }
    private static void invalid() { throw new ComputationException(ComputationException.Code.INVALID_CHECKPOINT, "assembly", "Typed comparison cursor differs from its sources or paid work"); }
}
