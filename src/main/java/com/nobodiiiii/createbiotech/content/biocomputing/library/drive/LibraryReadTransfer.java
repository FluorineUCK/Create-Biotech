package com.nobodiiiii.createbiotech.content.biocomputing.library.drive;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryBookAccess;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import java.util.*;

/** One positioned, fenced read snapshot. Encoding is finite device work; slices never consume the snapshot.
 * Parser/encoder caches are not world references and are rebuilt within the same activity budget after load. */
public final class LibraryReadTransfer {
    sealed interface Target permits Entry, Page {}
    record Entry(String name) implements Target { Entry { LibraryReadHead.name(name); } }
    record Page(int index) implements Target {
        Page { if(index<0 || index>=net.minecraft.world.item.component.WritableBookContent.MAX_PAGES) throw failure("invalid_page"); }
    }
    private final Target target;
    private final LibraryReadHead.Sample sample;
    private final long remaining, steps, workspace, sealed;
    private final int maxBytes;
    private final ArchiveLimits archive;
    private final String hex;
    private final SerialEncoding.State live;
    private final char[] staging;
    private final int copied;

    private LibraryReadTransfer(Target target, LibraryReadHead.Sample sample, long remaining, long steps,
        int maxBytes, long workspace, ArchiveLimits archive, String hex, SerialEncoding.State live,
        long sealed, char[] staging, int copied) {
        this.target=Objects.requireNonNull(target); this.sample=Objects.requireNonNull(sample);
        this.remaining=remaining; this.steps=steps; this.maxBytes=maxBytes; this.workspace=workspace;
        this.archive=Objects.requireNonNull(archive); this.hex=hex; this.live=live;
        this.sealed=sealed; this.staging=staging; this.copied=copied;
        if(remaining<0 || steps<0 || steps>4096L*(maxBytes+(long)archive.maxNodes()) || maxBytes<1 || workspace<1
            || sealed<0 || sealed>maxBytes || steps==0 && sealed!=0
            || remaining>0 && steps!=0 || hex!=null && (remaining!=0 || steps==0 || hex.isEmpty() || sealed!=hex.length()/2
                || (hex.length()&1)!=0 || hex.length()/2>maxBytes || !hex.matches("[0-9a-f]+"))
            || target instanceof Entry && !sample.value().fields().keySet().equals(Set.of("kind","value","version"))
            || target instanceof Page p && !integer(p.index()).equals(sample.value().fields().get("page"))) throw failure("invalid_checkpoint");
    }
    public static LibraryReadTransfer begin(String entry, LibraryReadHead.Sample sample, LibraryDriveLimits limits) {
        return begin(new Entry(entry),sample,limits);
    }
    static LibraryReadTransfer begin(Target target, LibraryReadHead.Sample sample, LibraryDriveLimits limits) {
        long ticks=limits.readTicks(LibraryReadHead.encodedBytes(sample.value(),limits));
        var probe=new LibraryReadTransfer(target,sample,ticks,0,1,1,limits.archive(),null,null,0,null,0);
        long free=limits.bufferBytes()-LibraryReadHead.encodedBytes(probe.value(),limits);
        if(free<4) throw failure("buffer_full");
        // The final private checkpoint uses two UTF-16 hex digits per encoded byte.
        return new LibraryReadTransfer(target,sample,ticks,0,(int)Math.min(Integer.MAX_VALUE,free/4),free,limits.archive(),null,null,0,null,0);
    }
    Target target() {return target;}
    public LibraryReadHead.Sample sample() {return sample;}
    public long remaining() {return remaining;}
    public boolean ready() {return hex!=null;}
    public boolean restoring() {return !ready() && (steps>1 && (live==null || live.steps()<steps) || copied<sealed);}
    public String status() {return ready()?"read_ready":remaining>0?"reading":restoring()?"restoring":"encoding";}
    public long reservedBytes() {return ready()?0:workspace;}
    public long steps() {return steps+sealed;}
    public long copiedBytes() {return sealed;}
    public int size() {if(!ready()) throw failure("busy"); return hex.length()/2;}
    public DataValue.RecordValue metadata() {
        return new DataValue.RecordValue(Map.of("bytes",integer(size()),"format",new DataValue.TextValue(LibraryWriteTransfer.FORMAT),
            "kind",target instanceof Page ? new DataValue.TextValue("recipe_page") : sample.value().field("kind"),
            "version",target instanceof Page ? DataValue.NullValue.INSTANCE : sample.value().field("version")));
    }
    public DataValue.RecordValue chunk(long offset,long count) {
        int size=size();
        if(offset<0 || count<1 || offset>size || count>size-offset) throw failure("invalid_range");
        var bytes=new ArrayList<DataValue>((int)count);
        for(int i=0;i<count;i++) {int at=Math.toIntExact(2*(offset+i));
            int b=(Character.digit(hex.charAt(at),16)<<4)|Character.digit(hex.charAt(at+1),16);
            bytes.add(integer((byte)b));
        }
        return new DataValue.RecordValue(Map.of("offset",integer(offset),"total",integer(size),"eof",new DataValue.BooleanValue(offset+count==size),
            "bytes",new DataValue.NbtValue(7,new DataValue.ListValue(bytes))));
    }
    public LibraryReadTransfer advance(long budget) {
        if(budget<1) throw new IllegalArgumentException("Positive codec work budget required");
        if(ready()) return this;
        if(remaining>0) return copy(remaining-1,steps,null,live);
        var state=live; long used=0;
        if(state==null) {
            var input=new DataValue.RecordValue(Map.of("value",target instanceof Page ? sample.value() : sample.value().field("value")));
            long initial=Math.addExact(maxBytes,Math.multiplyExact(Long.BYTES,26+input.storageUnits()));
            if(initial>workspace) throw failure("buffer_full");
            state=SerialEncoding.begin(input,new SerialRecordCodec.Limits(maxBytes,workspace,archive));
            used=1;
        }
        for(;used<budget && !state.done();used++) {
            state=state.advance(units -> {if(units>workspace) throw failure("buffer_full");});
            if(state.storageBytes()>workspace) throw failure("buffer_full");
        }
        if(!state.done()) return copy(0,Math.max(steps,state.steps()),null,state);
        if(state.steps()<steps) throw failure("invalid_checkpoint");
        var encoded=state.encoded(); if(encoded.error()!=null) throw failure(encoded.error());
        // Encode and private-checkpoint copy each pay their own byte work. Only String sealing is a bounded publication copy.
        if(encoded.size()>Integer.MAX_VALUE/2 || state.storageBytes()+8L*encoded.size()>workspace) throw failure("buffer_full");
        if(sealed>encoded.size()) throw failure("invalid_checkpoint");
        char[] text=staging==null?new char[2*encoded.size()]:staging;
        int position=copied;
        while(used<budget && position<encoded.size()) {
            int b=encoded.byteAt(position);text[2*position]=Character.forDigit(b>>>4,16);text[2*position+1]=Character.forDigit(b&15,16);
            position++;used++;
        }
        long paid=Math.max(sealed,position);
        return new LibraryReadTransfer(target,sample,0,state.steps(),maxBytes,workspace,archive,
            position==encoded.size()?new String(text):null,position==encoded.size()?null:state,paid,
            position==encoded.size()?null:text,position==encoded.size()?0:position);
    }
    private LibraryReadTransfer copy(long ticks,long paid,String complete,SerialEncoding.State cursor) {
        return new LibraryReadTransfer(target,sample,ticks,paid,maxBytes,workspace,archive,complete,cursor,sealed,staging,copied);
    }
    public DataValue.RecordValue value() {
        return new DataValue.RecordValue(Map.of(target instanceof Page?"page":"entry",target instanceof Page p?integer(p.index()):new DataValue.TextValue(((Entry)target).name()),"sample",sample.valueRecord(),
            "remaining",integer(remaining),"steps",integer(steps),"max_bytes",integer(maxBytes),"workspace",integer(workspace),"sealed",integer(sealed),
            "codec",integer(SerialEncoding.VERSION),"limits",new DataValue.RecordValue(Map.of("nodes",integer(archive.maxNodes()),
                "chars",integer(archive.maxStringChars()),"units",integer(archive.maxValueUnits()))),
            "hex",hex==null?DataValue.NullValue.INSTANCE:new DataValue.TextValue(hex)));
    }
    static LibraryReadTransfer decode(DataValue value, boolean page) {
        var r=fields(value,page?"page":"entry","sample","remaining","steps","max_bytes","workspace","codec","limits","hex","sealed");
        if(number(r.field("codec"))!=SerialEncoding.VERSION) throw failure("invalid_checkpoint");
        var s=fields(r.field("sample"),"fence","value"); var a=fields(r.field("limits"),"nodes","chars","units");
        if(!(s.field("fence") instanceof DataValue.RecordValue fence) || !(s.field("value") instanceof DataValue.RecordValue data)) throw failure("invalid_checkpoint");
        return new LibraryReadTransfer(page?new Page(Math.toIntExact(number(r.field("page")))):new Entry(text(r.field("entry"))),new LibraryReadHead.Sample(fence,data),number(r.field("remaining")),number(r.field("steps")),
            Math.toIntExact(number(r.field("max_bytes"))),number(r.field("workspace")),new ArchiveLimits(Math.toIntExact(number(a.field("nodes"))),
                number(a.field("chars")),number(a.field("units"))),r.field("hex")==DataValue.NullValue.INSTANCE?null:text(r.field("hex")),null,
            number(r.field("sealed")),null,0);
    }
    @Override public boolean equals(Object other) {
        return other instanceof LibraryReadTransfer r && target.equals(r.target) && sample.equals(r.sample) && remaining==r.remaining
            && steps==r.steps && sealed==r.sealed && maxBytes==r.maxBytes && workspace==r.workspace && archive.equals(r.archive) && Objects.equals(hex,r.hex);
    }
    @Override public int hashCode() {return Objects.hash(target,sample,remaining,steps,sealed,maxBytes,workspace,archive,hex);}
    private static DataValue.IntegerValue integer(long n) {return new DataValue.IntegerValue(n);}
    private static long number(DataValue value) {if(!(value instanceof DataValue.IntegerValue n)) throw failure("invalid_checkpoint"); return n.value();}
    private static String text(DataValue value) {if(!(value instanceof DataValue.TextValue t)) throw failure("invalid_checkpoint"); return t.value();}
    private static DataValue.RecordValue fields(DataValue value,String... names) {
        if(!(value instanceof DataValue.RecordValue r) || !r.fields().keySet().equals(Set.of(names))) throw failure("invalid_checkpoint"); return r;
    }
    private static LibraryBookAccess.Failure failure(String code) {return new LibraryBookAccess.Failure(code);}
}
