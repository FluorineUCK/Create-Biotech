package com.nobodiiiii.createbiotech.content.biocomputing.library.drive;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryBookAccess;
import com.nobodiiiii.createbiotech.content.biocomputing.library.service.LibraryWriteBatch;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import java.util.*;

/** Fixed codec identity and paid continuation. Live parser nodes are a disposable cache, never authority.
 * Reconstructing that cache after load uses the SAME bounded device work budget, not a synchronous replay. */
public final class LibraryUploadCommit {
    private final LibraryWriteBatch.Admission admission;
    private final SerialRecordCodec.Limits limits;
    private final long steps, witness;
    private final int consumed;
    private final SerialDecoding.State live;
    private final boolean pooledWorkspace;

    private LibraryUploadCommit(LibraryWriteBatch.Admission admission, SerialRecordCodec.Limits limits,
        long steps, int consumed, long witness, SerialDecoding.State live, boolean pooledWorkspace) {
        this.admission=Objects.requireNonNull(admission); this.limits=Objects.requireNonNull(limits);
        this.steps=steps; this.consumed=consumed; this.witness=witness; this.live=live;
        this.pooledWorkspace=pooledWorkspace;
        if (!Set.of("", "eligible", "conflict").contains(admission.verdict()) || steps<1
            || steps>4096L*(limits.maxEncodedBytes()+(long)limits.values().maxNodes())
            || consumed<0 || consumed>limits.maxEncodedBytes()) throw failure("invalid_checkpoint");
    }
    public static LibraryUploadCommit start(LibraryWriteBatch batch, int length, long scratch, ArchiveLimits values) {
        return new LibraryUploadCommit(new LibraryWriteBatch.Admission(batch,""),
            new SerialRecordCodec.Limits(length,scratch,values),1,0,0,null,true);
    }
    public LibraryWriteBatch.Admission admission() { return admission; }
    public long steps() { return steps; }
    public int length() { return limits.maxEncodedBytes(); }
    /** V2 pools both accounted workspaces; legacy operations retain their two fixed partitions. */
    public long reservedBytes() { return Math.multiplyExact(pooledWorkspace?1:2,limits.maxDecodeHeapBytes()); }
    public boolean restoring() { return steps>1 && (live==null || live.steps()<steps); }
    public boolean done() { return !restoring() && live!=null && live.done(); }
    public SerialDecoding.Decoded decoded() { if(!done()) throw failure("busy"); return live.decoded(); }
    public LibraryUploadCommit seal(boolean conflict) {
        if(!admission.verdict().isEmpty()) return this;
        return new LibraryUploadCommit(new LibraryWriteBatch.Admission(admission.batch(),conflict?"conflict":"eligible"),
            limits,steps,consumed,witness,live,pooledWorkspace);
    }
    public LibraryUploadCommit advance(SerialDecoding.Input input,long budget) {
        if(input.size()!=limits.maxEncodedBytes() || budget<1) throw failure("invalid_checkpoint");
        var state=live==null?SerialDecoding.begin(input,false,limits):live;
        for(long used=0;used<budget;used++) {
            if(state.steps()==steps && (state.consumedBytes()!=consumed || state.wireWitness()!=witness))
                throw failure("invalid_checkpoint");
            if(state.done()) {
                if(state.steps()<steps) throw failure("invalid_checkpoint");
                break;
            }
            state=state.advance();
            // Parser charges and live immutable ownership BOTH count; neither gets free spill space.
            long available=limits.maxDecodeHeapBytes()-(pooledWorkspace?state.parserAllocatedBytes():0);
            long units=pooledWorkspace?state.bufferStorageUnits():state.storageUnits();
            if(available<0 || units>available/Long.BYTES) throw failure("buffer_full");
        }
        if(state.steps()<=steps) {
            if(state.steps()==steps && (state.consumedBytes()!=consumed || state.wireWitness()!=witness))
                throw failure("invalid_checkpoint");
            return new LibraryUploadCommit(admission,limits,steps,consumed,witness,state,pooledWorkspace);
        }
        return new LibraryUploadCommit(admission,limits,state.steps(),state.consumedBytes(),state.wireWitness(),state,pooledWorkspace);
    }
    public DataValue.RecordValue value() {
        var fields=new LinkedHashMap<String,DataValue>(Map.of("version",integer(SerialDecoding.VERSION),"batch",admission.value(),
            "length",integer(limits.maxEncodedBytes()),"scratch",integer(limits.maxDecodeHeapBytes()),
            "nodes",integer(limits.values().maxNodes()),"chars",integer(limits.values().maxStringChars()),
            "units",integer(limits.values().maxValueUnits()),"steps",integer(steps),"consumed",integer(consumed),"witness",integer(witness)));
        if(pooledWorkspace)fields.put("workspace",integer(2));
        return new DataValue.RecordValue(fields);
    }
    public static LibraryUploadCommit decode(DataValue value) {
        if(!(value instanceof DataValue.RecordValue r))throw failure("invalid_checkpoint");
        boolean pooled=r.fields().containsKey("workspace");
        var names=new HashSet<>(Set.of("version","batch","length","scratch","nodes","chars","units","steps","consumed","witness"));
        if(pooled)names.add("workspace");
        if(!r.fields().keySet().equals(names) || pooled&&number(r.field("workspace"))!=2
            || number(r.field("version"))!=SerialDecoding.VERSION) throw failure("invalid_checkpoint");
        return new LibraryUploadCommit(LibraryWriteBatch.Admission.decode(r.field("batch")),
            new SerialRecordCodec.Limits(Math.toIntExact(number(r.field("length"))),number(r.field("scratch")),
                new ArchiveLimits(Math.toIntExact(number(r.field("nodes"))),number(r.field("chars")),number(r.field("units")))),
            number(r.field("steps")),Math.toIntExact(number(r.field("consumed"))),number(r.field("witness")),null,pooled);
    }
    @Override public boolean equals(Object other) {
        return other instanceof LibraryUploadCommit c && admission.equals(c.admission) && limits.equals(c.limits)
            && steps==c.steps && consumed==c.consumed && witness==c.witness && pooledWorkspace==c.pooledWorkspace;
    }
    @Override public int hashCode() { return Objects.hash(admission,limits,steps,consumed,witness,pooledWorkspace); }
    private static DataValue.IntegerValue integer(long value) { return new DataValue.IntegerValue(value); }
    private static long number(DataValue value) {
        if(!(value instanceof DataValue.IntegerValue n)) throw failure("invalid_checkpoint"); return n.value();
    }
    private static LibraryBookAccess.Failure failure(String code) { return new LibraryBookAccess.Failure(code); }
}
