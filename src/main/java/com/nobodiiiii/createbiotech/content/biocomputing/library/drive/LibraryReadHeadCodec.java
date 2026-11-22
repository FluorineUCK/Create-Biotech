package com.nobodiiiii.createbiotech.content.biocomputing.library.drive;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryTrackAddress;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import net.minecraft.nbt.CompoundTag;

/** Private hardware checkpoint using the existing typed-value archive, not a public authority token. */
public final class LibraryReadHeadCodec {
    private LibraryReadHeadCodec() {}
    public static CompoundTag encode(LibraryReadHead.State state, LibraryDriveLimits limits) {
        LibraryReadHead.validateBuffer(state, limits);
        return ValueNbtCodec.encode(value(state), limits.archive());
    }
    public static LibraryReadHead.State decode(CompoundTag tag, LibraryDriveLimits limits) {
        var decoded = anyRecord(ValueNbtCodec.decode(tag, limits.archive()));
        long version = number(decoded.fields().get("version"));
        if (version < 1 || version > 10) throw new ArchiveException(ArchiveException.Code.UNSUPPORTED_VERSION, "Unsupported read-head checkpoint");
        var root = version >= 9 ? record(decoded,"version","sequence","track","slot","pending","result","reading")
            : version >= 6 ? record(decoded, "version", "sequence", "track", "slot", "pending", "result", "transfer")
            : version == 3 ? record(decoded, "version", "sequence", "track", "slot", "pending", "result", "writing")
            : record(decoded, "version", "sequence", "track", "slot", "pending", "result");
        Optional<LibraryTrackAddress> track = root.field("track") == DataValue.NullValue.INSTANCE
            ? Optional.empty() : Optional.of(LibraryTrackAddress.decode(root.field("track")));
        Optional<LibraryReadHead.Pending> pending = Optional.empty();
        if (root.field("pending") != DataValue.NullValue.INSTANCE) {
            var job = record(root.field("pending"), "action", "target", "slot", "entry", "remaining", "total", "sample");
            Optional<LibraryReadHead.Sample> sample = Optional.empty();
            if (job.field("sample") != DataValue.NullValue.INSTANCE) {
                var captured = record(job.field("sample"), "fence", "value");
                sample = Optional.of(new LibraryReadHead.Sample(anyRecord(captured.field("fence")), anyRecord(captured.field("value"))));
            }
            pending = Optional.of(new LibraryReadHead.Pending(LibraryReadHead.Action.valueOf(text(job.field("action"))),
                LibraryTrackAddress.decode(job.field("target")), slot(job.field("slot")), text(job.field("entry")),
                number(job.field("remaining")), number(job.field("total")), sample));
        }
        Optional<LibraryReadHead.Result> result = Optional.empty();
        if (root.field("result") != DataValue.NullValue.INSTANCE) {
            var completed = record(root.field("result"), "code", "value");
            result = Optional.of(new LibraryReadHead.Result(text(completed.field("code")), completed.field("value")));
        }
        var writing = version == 3 ? Optional.of(LibraryWriteWork.decode(root.field("writing"))) : Optional.<LibraryWriteWork>empty();
        var transfer = version >= 6 && version <= 8 ? Optional.of(LibraryWriteTransfer.decode(root.field("transfer"))) : Optional.<LibraryWriteTransfer>empty();
        if (version == 6 && transfer.filter(t -> !t.segments().isEmpty()).isPresent()) throw invalid();
        if (version < 8 && transfer.filter(t -> t.commit().isPresent()).isPresent()) throw invalid();
        var reading=version>=9?Optional.of(LibraryReadTransfer.decode(root.field("reading"),version==10)):Optional.<LibraryReadTransfer>empty();
        var state = new LibraryReadHead.State(number(root.field("sequence")), track, slot(root.field("slot")), pending, result, writing, transfer,reading);
        if (version < 5 && needsRecipeSchema(state)) throw invalid();
        if (version == 1 && needsStatSchema(state)) throw invalid();
        if (version < 4 && needsDirectorySchema(state)) throw invalid();
        LibraryReadHead.validateBuffer(state, limits); return state;
    }
    private static DataValue.RecordValue value(LibraryReadHead.State state) {
        DataValue pending = state.pending().<DataValue>map(job -> new DataValue.RecordValue(Map.of(
            "action", new DataValue.TextValue(job.action().name()), "target", job.target().value(), "slot", integer(job.slot()),
            "entry", new DataValue.TextValue(job.entry()), "remaining", integer(job.remainingTicks()), "total", integer(job.totalTicks()),
            "sample", job.sample().<DataValue>map(LibraryReadHead.Sample::valueRecord).orElse(DataValue.NullValue.INSTANCE))))
            .orElse(DataValue.NullValue.INSTANCE);
        DataValue result = state.result().<DataValue>map(completed -> LibraryReadHead.object("code",
            new DataValue.TextValue(completed.code()), "value", completed.value())).orElse(DataValue.NullValue.INSTANCE);
        var fields = new LinkedHashMap<String, DataValue>(Map.of("version", integer(state.reading().isPresent()?(state.reading().orElseThrow().target() instanceof LibraryReadTransfer.Page?10:9):state.transfer().isPresent() ? state.transfer().orElseThrow().commit().isPresent() ? 8 : state.transfer().orElseThrow().segments().isEmpty() ? 6 : 7 : needsRecipeSchema(state) ? 5 : needsDirectorySchema(state) ? 4 : state.writing().isPresent() ? 3 : needsStatSchema(state) ? 2 : 1), "sequence", integer(state.sequence()),
            "track", state.track().<DataValue>map(LibraryTrackAddress::value).orElse(DataValue.NullValue.INSTANCE),
            "slot", integer(state.slot()), "pending", pending, "result", result));
        state.writing().ifPresent(work -> fields.put("writing", work.value()));
        state.transfer().ifPresent(transfer -> fields.put("transfer", transfer.value()));
        state.reading().ifPresent(read -> fields.put("reading",read.value()));
        return new DataValue.RecordValue(fields);
    }
    private static boolean needsRecipeSchema(LibraryReadHead.State state) {
        return state.pending().filter(job -> job.action() == LibraryReadHead.Action.RECIPE_PAGE).isPresent()
            || state.result().filter(result -> result.code().equals("recipe_page")).isPresent();
    }
    private static boolean needsStatSchema(LibraryReadHead.State state) {
        return state.pending().filter(job -> job.action() == LibraryReadHead.Action.STAT).isPresent()
            || state.result().filter(result -> result.code().equals("stat")).isPresent();
    }
    private static boolean needsDirectorySchema(LibraryReadHead.State state) {
        return state.pending().filter(job -> job.action() == LibraryReadHead.Action.LIST).isPresent()
            || state.result().filter(result -> result.code().equals("list")).isPresent();
    }
    private static DataValue.IntegerValue integer(long number) { return new DataValue.IntegerValue(number); }
    private static long number(DataValue value) {
        if (!(value instanceof DataValue.IntegerValue number)) throw invalid();
        return number.value();
    }
    private static int slot(DataValue value) {
        long slot = number(value); if (slot < -1 || slot >= 6) throw invalid(); return (int)slot;
    }
    private static String text(DataValue value) { if (!(value instanceof DataValue.TextValue text)) throw invalid(); return text.value(); }
    private static DataValue.RecordValue anyRecord(DataValue value) {
        if (!(value instanceof DataValue.RecordValue record)) throw invalid(); return record;
    }
    private static DataValue.RecordValue record(DataValue value, String... keys) {
        var record = anyRecord(value); if (!record.fields().keySet().equals(Set.of(keys))) throw invalid(); return record;
    }
    private static ArchiveException invalid() { return new ArchiveException(ArchiveException.Code.INVALID_FORMAT, "Invalid read-head checkpoint"); }
}
