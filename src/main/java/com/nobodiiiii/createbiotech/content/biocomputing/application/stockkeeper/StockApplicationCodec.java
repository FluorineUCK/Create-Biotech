package com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ValueNbtCodec;
import java.util.*;
import net.minecraft.nbt.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.StockApplication.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.StockApplicationValues.*;

/** Strict bounded archive. Balance limits never truncate or reinterpret saved data. */
public final class StockApplicationCodec {
    private StockApplicationCodec() {}
    public static CompoundTag save(State state) {
        validate(state);
        var root = new CompoundTag(); root.putInt("Version",2); root.put("State",ValueNbtCodec.encode(state(state), FORMAT)); return root;
    }
    public static State load(CompoundTag tag, UUID freshSession) {
        int version = tag.getInt("Version");
        if (!tag.getAllKeys().equals(Set.of("Version","State")) || !tag.contains("Version",Tag.TAG_INT) || version < 1 || version > 2
            || !(tag.get("State") instanceof CompoundTag raw)) throw invalid();
        var decoded = ValueNbtCodec.decode(raw, FORMAT);
        // Reject unknown fields, unused rows and noncanonical representations in our own archive only.
        if (!ValueNbtCodec.encode(decoded,FORMAT).equals(raw)) throw invalid();
        var r = fields(decoded,"version","device","session","revision","application","open","next_sequence","catalog","inputs");
        if (integer(r.field("version")) != version || !(r.field("open") instanceof DataValue.BooleanValue open)) throw invalid();
        var app = r.field("application") == DataValue.NullValue.INSTANCE ? Optional.<Application>empty() : Optional.of(readApplication(r.field("application")));
        var state = new State(uuid(r.field("device")),uuid(r.field("session")),integer(r.field("revision")),app,open.value(),integer(r.field("next_sequence")),
            readEntries(r.field("catalog")),list(r.field("inputs")).stream().map(v -> readInput(v, version)).toList());
        validate(state);
        if (state.session().equals(freshSession)) throw new IllegalArgumentException("session_reused");
        return new State(state.device(),freshSession,state.revision(),state.application(),state.open(),state.nextSequence(),state.catalog(),state.inputs());
    }
    static void validate(State state) {
        if (state.catalog().size() > MAX_MEMBERS || state.inputs().size() > MAX_MEMBERS || state.application().isEmpty() && (!state.catalog().isEmpty() || !state.inputs().isEmpty())) throw invalid();
        validateEntries(state.catalog(), MAX_TEXT);
        long previous = state.nextSequence() - state.inputs().size() - 1;
        for (var i : state.inputs()) {
            var req = i.request();
            if (i.sequence() < 1 || i.sequence() != previous+1 || !i.device().equals(state.device())
                || !state.application().filter(i.application()::equals).isPresent() || req.sequence() != i.sequence()
                || !req.fence().device().equals(state.device()) || req.fence().revision() > state.revision()
                || req.address().isBlank() || req.address().length() > MAX_TEXT
                || (req.nativeOrder().isPresent() ? !i.items().isEmpty() : i.items().isEmpty())
                || i.items().size() > MAX_MEMBERS || i.items().size() != req.selections().size()) throw invalid();
            req.nativeOrder().ifPresent(order -> { StockNativeOrder.validate(order, MAX_MEMBERS); checkText(order, MAX_TEXT); });
            validateEntries(i.items().stream().map(Item::entry).toList(),MAX_TEXT);
            for (int n=0;n<i.items().size();n++) {
                var item = i.items().get(n); var selected = req.selections().get(n);
                if (item.quantity() < 1 || item.quantity() != selected.quantity() || !item.entry().key().equals(selected.key())) throw invalid();
            }
            previous = i.sequence();
        }
        ValueNbtCodec.encode(state(state),FORMAT);
    }
    static Application readApplication(DataValue v) { var r=fields(v,"computer","program"); return new Application(uuid(r.field("computer")),uuid(r.field("program"))); }
    static Fence readFence(DataValue v) { var r=fields(v,"device","session","revision"); return new Fence(uuid(r.field("device")),uuid(r.field("session")),integer(r.field("revision"))); }
    static Entry readEntry(DataValue v) {
        var r=fields(v,"key","title","unit","icon","data");
        if (!(r.field("icon") instanceof DataValue.RecordValue icon) || !(r.field("data") instanceof DataValue.RecordValue data)) throw invalid();
        return new Entry(string(r.field("key")),string(r.field("title")),string(r.field("unit")),icon,data);
    }
    static List<Entry> readEntries(DataValue v) { return list(v).stream().map(StockApplicationCodec::readEntry).toList(); }
    private static Request readRequest(DataValue v, int version) {
        var r = version == 1 ? fields(v,"fence","sequence","address","selections")
            : fields(v,"fence","sequence","address","selections","kind","order");
        var selections=list(r.field("selections")).stream().map(s->{var f=fields(s,"key","quantity"); return new Selection(string(f.field("key")),integer(f.field("quantity")));}).toList();
        if (version == 2) {
            if (string(r.field("kind")).equals("native_order")) {
                if (!selections.isEmpty() || !(r.field("order") instanceof DataValue.NbtValue order)) throw invalid();
                return Request.nativeOrder(readFence(r.field("fence")),integer(r.field("sequence")),string(r.field("address")),order);
            }
            if (!string(r.field("kind")).equals("catalog") || r.field("order") != DataValue.NullValue.INSTANCE) throw invalid();
        }
        return new Request(readFence(r.field("fence")),integer(r.field("sequence")),selections,string(r.field("address")));
    }
    private static Input readInput(DataValue v, int version) {
        var r=fields(v,"device","sequence","application","player","request","items");
        var items=list(r.field("items")).stream().map(i->{var f=fields(i,"entry","quantity");return new Item(readEntry(f.field("entry")),integer(f.field("quantity")));}).toList();
        return new Input(uuid(r.field("device")),integer(r.field("sequence")),readApplication(r.field("application")),uuid(r.field("player")),readRequest(r.field("request"), version),items);
    }
    static DataValue.RecordValue fields(DataValue v,String... names) { if (!(v instanceof DataValue.RecordValue r) || !r.fields().keySet().equals(Set.of(names))) throw invalid(); return r; }
    static List<DataValue> list(DataValue v) { if (!(v instanceof DataValue.ListValue l) || l.values().size()>MAX_MEMBERS) throw invalid(); return l.values(); }
    static long integer(DataValue v) { if (!(v instanceof DataValue.IntegerValue n)) throw invalid(); return n.value(); }
    static String string(DataValue v) { if (!(v instanceof DataValue.TextValue t)) throw invalid(); return t.value(); }
    static UUID uuid(DataValue v) { String s=string(v);UUID id=UUID.fromString(s);if(!id.toString().equals(s))throw invalid();return id; }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("invalid_archive"); }
}
