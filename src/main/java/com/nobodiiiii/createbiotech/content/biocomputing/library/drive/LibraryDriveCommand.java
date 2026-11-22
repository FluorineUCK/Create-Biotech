package com.nobodiiiii.createbiotech.content.biocomputing.library.drive;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.library.*;
import java.util.*;

/** Public device protocol, not a restriction on ordinary serial records. No name-to-book search. */
public record LibraryDriveCommand(DataValue.RecordValue target, DataValue request, String operation, long sequence, DataValue data) {
    public LibraryDriveCommand {
        if (!(request instanceof DataValue.IntegerValue n && n.value() >= 0)
            && !(request instanceof DataValue.TextValue text && !text.value().isBlank())) throw failure("invalid_command");
    }
    public static LibraryDriveCommand decode(DataValue.RecordValue frame) {
        if (!frame.fields().keySet().equals(Set.of("kind", "target", "request", "operation", "sequence", "data"))
            || !frame.field("kind").equals(new DataValue.TextValue("library_command"))
            || !(frame.field("operation") instanceof DataValue.TextValue operation)) throw failure("invalid_command");
        var target = LibraryTrackAddress.decode(frame.field("target")).value();
        var request = frame.field("request"); long sequence = number(frame.field("sequence"));
        return new LibraryDriveCommand(target, request, operation.value(), sequence, frame.field("data"));
    }
    public record Transition(LibraryReadHead.State state, DataValue.RecordValue reply) {}
    public record Admission(UUID device, com.nobodiiiii.createbiotech.content.biocomputing.library.service.LibraryWriteBatch batch) {
        public Admission { Objects.requireNonNull(device); Objects.requireNonNull(batch); }
    }
    public Transition apply(LibraryReadHead.State before, LibraryReadHead.Source source, LibraryDriveLimits limits, Admission admission) {
        return apply(before,source,limits,admission,(int)Math.min(Integer.MAX_VALUE,limits.bufferBytes()));
    }
    public Transition apply(LibraryReadHead.State before, LibraryReadHead.Source source, LibraryDriveLimits limits, Admission admission, int maximumReplyBytes) {
        LibraryReadHead.State after = before; String code; DataValue value = DataValue.NullValue.INSTANCE;
        if (!operation.equals("status") && sequence != before.sequence()) throw failure("stale_operation");
        switch (operation) {
            case "write_begin" -> {
                if (admission == null) throw failure("unsupported_operation");
                after = LibraryReadHead.beginTransfer(before, data, admission.device(), source, limits); code = "receiving";
            }
            case "write_abort" -> {
                if (admission == null) throw failure("unsupported_operation");
                empty(); after = LibraryReadHead.abortTransfer(before, admission.device()); code = "aborted";
            }
            case "write_chunk" -> {
                if (admission == null) throw failure("unsupported_operation");
                after = LibraryReadHead.appendTransfer(before, data, admission.device(), source, limits); code = "accepted";
            }
            case "write_commit" -> {
                if(admission==null) throw failure("unsupported_operation");
                empty(); after=LibraryReadHead.commitTransfer(before,admission.device(),admission.batch(),source,limits); code="accepted";
            }
            case "write" -> {
                if (admission == null) throw failure("unsupported_operation");
                if (!(data instanceof DataValue.RecordValue mutation)) throw failure("invalid_request");
                com.nobodiiiii.createbiotech.content.biocomputing.library.service.LibraryMutation decoded;
                try { decoded = com.nobodiiiii.createbiotech.content.biocomputing.library.service.LibraryMutation.decode(mutation); }
                catch (IllegalArgumentException invalid) { throw failure("invalid_request"); }
                after = LibraryReadHead.write(before, decoded, admission.device(), admission.batch(), source, limits); code = "accepted";
            }
            case "seek" -> { after = LibraryReadHead.seek(before, LibraryTrackAddress.decode(data), source, limits); code = "accepted"; }
            case "select" -> {
                long slot = number(data); if (slot >= 6) throw failure("invalid_slot");
                after = LibraryReadHead.select(before, (int)slot, source, limits); code = "accepted";
            }
            case "recipe_page", "recipe_page_begin" -> {
                if (!(data instanceof DataValue.IntegerValue page) || page.value() < 0 || page.value() >= net.minecraft.world.item.component.WritableBookContent.MAX_PAGES)
                    throw failure("invalid_page");
                after = operation.equals("recipe_page") ? LibraryReadHead.recipePage(before, (int)page.value(), source, limits)
                    : LibraryReadHead.beginRecipePageTransfer(before,(int)page.value(),source,limits); code = "accepted";
            }
            case "list" -> {
                after = LibraryReadHead.list(before, LibraryDirectoryQuery.decode(data), source, limits); code = "accepted";
            }
            case "read", "stat" -> {
                if (!(data instanceof DataValue.TextValue entry)) throw failure("invalid_entry");
                after = operation.equals("stat") ? LibraryReadHead.stat(before, entry.value(), source, limits)
                    : LibraryReadHead.read(before, entry.value(), source, limits); code = "accepted";
            }
            case "read_begin" -> {
                if(!(data instanceof DataValue.TextValue entry)) throw failure("invalid_entry");
                after=LibraryReadHead.beginReadTransfer(before,entry.value(),source,limits); code="accepted";
            }
            case "read_info" -> {empty(); value=LibraryReadHead.readInfo(before,source); code="read_ready";}
            case "read_ref" -> {
                empty();
                if (admission == null) throw failure("unsupported_operation");
                var version = LibraryReadHead.readInfo(before, source).field("version");
                if (!(version instanceof DataValue.RecordValue)) throw failure("unversioned_medium");
                value = new DataValue.RecordValue(Map.of("library", target,
                    "device", new DataValue.TextValue(admission.device().toString()),
                    "sequence", new DataValue.IntegerValue(sequence), "version", version));
                code = "read_ref";
            }
            case "read_chunk" -> {
                if(!(data instanceof DataValue.RecordValue range) || !range.fields().keySet().equals(Set.of("offset","count"))) throw failure("invalid_request");
                long count=number(range.field("count"));
                // Even raw bytes cannot fit: reject before constructing a large typed list. No implicit shortening.
                if(count>maximumReplyBytes) throw failure("payload_too_large");
                value=LibraryReadHead.readChunk(before,number(range.field("offset")),count,source); code="read_chunk";
            }
            case "read_close" -> {empty(); after=LibraryReadHead.closeReadTransfer(before); code="acknowledged";}
            case "result" -> { empty(); var result = LibraryReadHead.take(before, sequence, limits).result(); code = result.code(); value = result.value(); }
            case "ack" -> { empty(); after = LibraryReadHead.take(before, sequence, limits).state(); code = "acknowledged"; }
            case "status" -> {
                empty(); code = before.reading().isPresent()?before.reading().orElseThrow().status():before.transfer().isPresent() ? before.transfer().orElseThrow().commit().isPresent()
                    ? before.transfer().orElseThrow().commit().orElseThrow().restoring() ? "restoring" : "decoding"
                    : before.transfer().orElseThrow().remaining() > 0 ? "busy" : "receiving"
                    : LibraryReadHead.busy(before) ? "busy" : before.result().isPresent() ? "result_pending" : "idle";
                var fields = new LinkedHashMap<String, DataValue>(Map.of("remaining", new DataValue.IntegerValue(before.pending().map(LibraryReadHead.Pending::remainingTicks)
                    .orElseGet(() -> before.writing().map(LibraryWriteWork::remaining).orElseGet(() -> before.transfer().map(LibraryWriteTransfer::remaining)
                        .orElseGet(() -> before.reading().map(LibraryReadTransfer::remaining).orElse(0L))))),
                    "track", before.track().<DataValue>map(LibraryTrackAddress::value).orElse(DataValue.NullValue.INSTANCE), "slot", new DataValue.IntegerValue(before.slot())));
                before.transfer().ifPresent(t -> {
                    fields.put("offset", new DataValue.IntegerValue(t.received()));
                    fields.put("bytes", new DataValue.IntegerValue(t.length()));
                    fields.put("format", new DataValue.TextValue(t.format()));
                    t.commit().ifPresent(c -> fields.put("codec_work",new DataValue.IntegerValue(c.steps())));
                });
                before.reading().ifPresent(r -> {
                    fields.put("codec_work",new DataValue.IntegerValue(r.steps()));
                    fields.put("format",new DataValue.TextValue(LibraryWriteTransfer.FORMAT));
                    if(r.ready()) fields.put("bytes",new DataValue.IntegerValue(r.size()));
                });
                value = new DataValue.RecordValue(fields);
            }
            default -> throw failure("invalid_command");
        }
        return new Transition(after, reply(after.sequence(), code, value));
    }
    public Transition apply(LibraryReadHead.State before, LibraryReadHead.Source source, LibraryDriveLimits limits) {
        return apply(before, source, limits, null);
    }
    public DataValue.RecordValue reply(long currentSequence, String code, DataValue value) {
        return new DataValue.RecordValue(Map.of("kind", new DataValue.TextValue("library_reply"), "target", target,
            "request", request, "sequence", new DataValue.IntegerValue(currentSequence),
            "code", new DataValue.TextValue(code), "value", value));
    }
    private void empty() { if (data != DataValue.NullValue.INSTANCE) throw failure("invalid_command"); }
    private static long number(DataValue value) {
        if (!(value instanceof DataValue.IntegerValue integer) || integer.value() < 0) throw failure("invalid_command"); return integer.value();
    }
    private static LibraryBookAccess.Failure failure(String code) { return new LibraryBookAccess.Failure(code); }
}
