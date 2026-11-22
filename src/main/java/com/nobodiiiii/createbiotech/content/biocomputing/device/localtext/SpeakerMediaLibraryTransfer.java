package com.nobodiiiii.createbiotech.content.biocomputing.device.localtext;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryBookVolumes;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryTrackAddress;
import com.nobodiiiii.createbiotech.content.biocomputing.library.drive.LibraryWriteTransfer;
import com.nobodiiiii.createbiotech.content.biocomputing.library.service.LibraryMutation;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Caller-side protocol state only. It cannot open a cable, access a shelf, or publish a mutation. */
public final class SpeakerMediaLibraryTransfer {
    @FunctionalInterface public interface Bytes { byte[] slice(int offset, int count); }
    private enum Step { INITIAL, SEEK, SEEK_RESULT, SEEK_ACK, SELECT, SELECT_RESULT, SELECT_ACK,
        STAT, STAT_RESULT, STAT_ACK, BEGIN, CHUNK, CHUNK_STATUS, COMMIT, WRITE_RESULT, WRITE_ACK }
    private final UUID transfer;
    private final DataValue.RecordValue device;
    private final LibraryTrackAddress track;
    private final int slot, chunkBytes, size;
    private final String volume, entry;
    private final Bytes bytes;
    private Step step = Step.INITIAL;
    private DataValue.RecordValue pending;
    private LibraryMutation.Version expected, written;
    private long sequence, request;
    private int offset, sentCount;
    private String afterStat, afterWrite, outcome;

    public SpeakerMediaLibraryTransfer(UUID transfer, LibraryTrackAddress device, LibraryTrackAddress track, int slot,
            String volume, String entry, int chunkBytes, int size, Bytes bytes) {
        this.transfer = Objects.requireNonNull(transfer); this.device = device.value(); this.track = Objects.requireNonNull(track);
        if (slot < 0 || slot >= 6 || size < 1 || chunkBytes < 1) throw new IllegalArgumentException("invalid_file_transfer");
        LibraryBookVolumes.requireName(volume); LibraryBookVolumes.requireName(entry);
        this.slot = slot; this.volume = volume; this.entry = entry;
        this.chunkBytes = chunkBytes; this.size = size; this.bytes = Objects.requireNonNull(bytes);
    }

    public Optional<String> code() { return Optional.ofNullable(outcome); }
    public boolean finished() { return outcome != null; }
    public Optional<LibraryMutation.Version> writtenVersion() { return Optional.ofNullable(written); }
    public int transferredBytes() { return offset; }

    /** Close the pending exchange without retrying or inferring that a missing reply means a failed write. */
    public String replyTimeout() {
        if (!finished()) {
            pending = null;
            outcome = written != null ? "ack_pending" : afterWrite != null ? afterWrite : "library_reply_timeout";
        }
        return outcome;
    }

    /** A pending frame is exposed once. The transport must not automatically retransmit mutations. */
    public Optional<DataValue.RecordValue> next() {
        if (finished() || pending != null) return Optional.empty();
        var data = switch (step) {
            case SEEK -> track.value();
            case SELECT -> number(slot);
            case STAT -> text(entry);
            case BEGIN -> new DataValue.RecordValue(Map.of("volume", text(volume), "entry", text(entry),
                "expected", expected.value(), "kind", text("DATA"), "format", text(LibraryWriteTransfer.FORMAT), "bytes", number(size)));
            case CHUNK -> {
                sentCount = Math.min(chunkBytes, size - offset);
                byte[] part = bytes.slice(offset, sentCount);
                if (part == null || part.length != sentCount) throw new IllegalArgumentException("file_unavailable");
                var values = new ArrayList<DataValue>(sentCount);
                for (byte value : part) values.add(number(value));
                yield new DataValue.RecordValue(Map.of("offset", number(offset), "bytes", new DataValue.NbtValue(7, new DataValue.ListValue(values))));
            }
            default -> DataValue.NullValue.INSTANCE;
        };
        pending = new DataValue.RecordValue(Map.of("kind", text("library_command"), "target", device,
            "request", text(transfer + "/" + request++), "operation", text(operation()), "sequence", number(sequence), "data", data));
        return Optional.of(pending);
    }

    public boolean matches(DataValue.RecordValue reply) {
        return pending != null && text("library_reply").equals(reply.fields().get("kind"))
            && device.equals(reply.fields().get("target")) && pending.field("request").equals(reply.fields().get("request"));
    }

    /** True consumes only this request's reply. The serial adapter must also verify the physical source. */
    public boolean reply(DataValue.RecordValue reply) {
        if (!matches(reply)) return false;
        pending = null;
        if (!(reply.fields().get("code") instanceof DataValue.TextValue code)
            || !(reply.fields().get("sequence") instanceof DataValue.IntegerValue returned) || returned.value() < 0) {
            outcome = "invalid_reply";
            return true;
        }
        try { accept(code.value(), returned.value(), reply.fields().get("value")); }
        catch (IllegalArgumentException invalid) { outcome = "invalid_reply"; }
        return true;
    }

    private void accept(String code, long returned, DataValue data) {
        if (step == Step.INITIAL) {
            if (!code.equals("idle")) { outcome = code; return; }
            sequence = returned; step = Step.SEEK; return;
        }
        boolean admits = switch (step) {
            case SEEK, SELECT, STAT -> code.equals("accepted");
            case BEGIN -> code.equals("receiving");
            default -> false;
        };
        if (admits) {
            if (sequence == Long.MAX_VALUE || returned != sequence + 1) { outcome = "stale_operation"; return; }
            sequence = returned;
        } else if (returned != sequence) { outcome = "stale_operation"; return; }
        switch (step) {
            case SEEK -> advance(code, "accepted", Step.SEEK_RESULT);
            case SEEK_RESULT -> { if (!code.equals("busy")) advance(code, "positioned", Step.SEEK_ACK); }
            case SEEK_ACK -> advance(code, "acknowledged", Step.SELECT);
            case SELECT -> advance(code, "accepted", Step.SELECT_RESULT);
            case SELECT_RESULT -> { if (!code.equals("busy")) advance(code, "selected", Step.SELECT_ACK); }
            case SELECT_ACK -> advance(code, "acknowledged", Step.STAT);
            case STAT -> advance(code, "accepted", Step.STAT_RESULT);
            case STAT_RESULT -> {
                if (code.equals("busy")) return;
                afterStat = code.equals("stat") ? inspection(data) : code;
                step = Step.STAT_ACK;
            }
            case STAT_ACK -> {
                if (code.equals("acknowledged") && afterStat != null) outcome = afterStat;
                else advance(code, "acknowledged", Step.BEGIN);
            }
            case BEGIN -> advance(code, "receiving", Step.CHUNK);
            case CHUNK -> {
                if (!code.equals("accepted")) { outcome = code; return; }
                offset += sentCount;
                step = Step.CHUNK_STATUS;
            }
            case CHUNK_STATUS -> {
                if (code.equals("busy")) return;
                if (code.equals("receiving") && data instanceof DataValue.RecordValue status
                    && number(offset).equals(status.fields().get("offset")) && number(size).equals(status.fields().get("bytes"))
                    && number(slot).equals(status.fields().get("slot")) && track.value().equals(status.fields().get("track"))) {
                    step = offset == size ? Step.COMMIT : Step.CHUNK;
                } else outcome = code.equals("receiving") ? "changed" : code;
            }
            case COMMIT -> advance(code, "accepted", Step.WRITE_RESULT);
            case WRITE_RESULT -> {
                if (code.equals("busy")) return;
                if (code.equals("written")) {
                    if (!(data instanceof DataValue.RecordValue result)) { outcome = "invalid_reply"; return; }
                    var version = LibraryMutation.Version.decode(result.field("version"));
                    if (!version.entry().equals(entry) || !version.medium().equals(expected.medium())
                        || version.revision() <= expected.revision()) { outcome = "changed"; return; }
                    written = version;
                }
                afterWrite = code; step = Step.WRITE_ACK;
            }
            // A failure to retire the result cannot change the device's confirmed write outcome.
            case WRITE_ACK -> outcome = code.equals("acknowledged") || written == null ? afterWrite : "ack_pending";
            default -> throw new IllegalStateException();
        }
    }

    private String inspection(DataValue data) {
        if (!(data instanceof DataValue.RecordValue stat) || !(stat.fields().get("present") instanceof DataValue.BooleanValue present))
            return "invalid_reply";
        if (present.value()) return "entry_exists"; // Create only; no silent overwrite, even by a stale UI.
        var version = LibraryMutation.Version.decode(stat.field("version"));
        if (!version.entry().equals(entry)) return "changed";
        expected = version;
        return null;
    }

    private void advance(String code, String accepted, Step next) {
        if (code.equals(accepted)) step = next; else outcome = code;
    }

    private String operation() {
        return switch (step) {
            case INITIAL, CHUNK_STATUS -> "status";
            case SEEK -> "seek";
            case SELECT -> "select";
            case STAT -> "stat";
            case SEEK_RESULT, SELECT_RESULT, STAT_RESULT, WRITE_RESULT -> "result";
            case SEEK_ACK, SELECT_ACK, STAT_ACK, WRITE_ACK -> "ack";
            case BEGIN -> "write_begin";
            case CHUNK -> "write_chunk";
            case COMMIT -> "write_commit";
        };
    }
    private static DataValue.TextValue text(String value) { return new DataValue.TextValue(value); }
    private static DataValue.IntegerValue number(long value) { return new DataValue.IntegerValue(value); }
}
