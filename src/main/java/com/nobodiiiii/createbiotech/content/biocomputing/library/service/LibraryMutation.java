package com.nobodiiiii.createbiotech.content.biocomputing.library.service;

import static com.nobodiiiii.createbiotech.content.biocomputing.library.service.LibraryServiceData.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.MailboxArchiveCodec;
import java.util.*;

/** Strict value-only storage commands. Empty replacement means delete, not a null DATA value. */
public record LibraryMutation(String volume, String entry, Version expected, Optional<LibraryEntry> replacement) {
    public LibraryMutation {
        LibraryBookVolumes.requireName(volume); LibraryBookVolumes.requireName(entry);
        Objects.requireNonNull(expected); Objects.requireNonNull(replacement);
        if (!expected.entry().equals(entry)) throw new IllegalArgumentException("Version belongs to another entry");
    }
    public record Version(UUID medium, String entry, long revision) {
        public Version {
            Objects.requireNonNull(medium); LibraryBookVolumes.requireName(entry);
            if (revision < 0) throw new IllegalArgumentException("Negative revision");
        }
        public DataValue.RecordValue value() { return obj("medium", medium.toString(), "entry", entry, "revision", revision); }
        public static Version decode(DataValue value) {
            var r = fields(value, "medium", "entry", "revision"); String id = text(r.field("medium"));
            UUID uuid = UUID.fromString(id);
            if (!uuid.toString().equals(id)) throw new IllegalArgumentException("Noncanonical medium identity");
            return new Version(uuid, text(r.field("entry")), number(r.field("revision")));
        }
    }
    public static LibraryMutation decode(DataValue.RecordValue payload) {
        String op = text(payload.fields().get("op"));
        if (op.equals("put")) fields(payload, "op", "volume", "entry", "expected", "kind", "value");
        else if (op.equals("delete")) fields(payload, "op", "volume", "entry", "expected");
        else throw new IllegalArgumentException("Not a storage mutation");
        return new LibraryMutation(text(payload.field("volume")), text(payload.field("entry")), Version.decode(payload.field("expected")),
            op.equals("delete") ? Optional.empty() : Optional.of(new LibraryEntry(LibraryEntry.Kind.valueOf(text(payload.field("kind"))), payload.field("value"))));
    }
    public void requireVersion(Version current) {
        if (!expected.medium().equals(current.medium())) throw new LibraryBookAccess.Failure("medium_changed");
        if (!expected.equals(current)) throw new LibraryBookAccess.Failure("stale_version");
    }
    public DataValue.RecordValue value() {
        return replacement.map(entryValue -> obj("op", "put", "volume", volume, "entry", entry,
            "expected", expected.value(), "kind", entryValue.kind().name(), "value", entryValue.value()))
            .orElseGet(() -> obj("op", "delete", "volume", volume, "entry", entry, "expected", expected.value()));
    }
    /** Pending completion evidence travels with the medium. Existing envelope codec owns RPC identity semantics. */
    public record Receipt(MessageEnvelope request, DataValue.RecordValue reply) implements LibraryWriteReceipt {
        public Receipt {
            Objects.requireNonNull(request); Objects.requireNonNull(reply);
            if (!(request.protocol() instanceof MessageProtocol.Request)) throw new IllegalArgumentException("Receipt requires a request");
            LibraryWriteReceipt.validate(decode(request.payload()), reply);
        }
        public static String key(MessageEnvelope message) { return message.source() + ":" + message.sequence(); }
        @Override public String key() { return key(request); }
        public DataValue.RecordValue value() {
            var mailbox = new MailboxState(List.of(request), Map.of(request.source(),
                new MailboxState.Receipt(request.sequence(), request.eventId(), request.ordinal())));
            return obj("request", MailboxArchiveCodec.encode(mailbox), "reply", reply);
        }
        public static Receipt decodeValue(DataValue value) {
            var r = fields(value, "request", "reply"); var box = MailboxArchiveCodec.decode(r.field("request"));
            if (box.queued().size() != 1 || box.receipts().size() != 1 || !(r.field("reply") instanceof DataValue.RecordValue reply))
                throw new IllegalArgumentException("Invalid book receipt");
            return new Receipt(box.queued().getFirst(), reply);
        }
        public DataValue.RecordValue replay(MessageEnvelope message) {
            // Gateways rewrite the return endpoint, not the original source/event/command identity.
            if (!(message.protocol() instanceof MessageProtocol.Request) || !request.source().equals(message.source())
                || request.sequence() != message.sequence() || !request.eventId().equals(message.eventId())
                || request.ordinal() != message.ordinal() || !request.payload().equals(message.payload()))
                throw new LibraryBookAccess.Failure("identity_conflict");
            return reply;
        }
    }
}
