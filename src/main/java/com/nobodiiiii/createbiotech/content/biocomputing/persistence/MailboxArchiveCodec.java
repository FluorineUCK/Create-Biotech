package com.nobodiiiii.createbiotech.content.biocomputing.persistence;

import static com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveData.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.*;
import java.util.*;

public final class MailboxArchiveCodec {
	private static final Set<String> ROOT_FIELDS = Set.of("version", "queued", "receipts");
	private static final Set<String> IDENTITY_FIELDS = Set.of("source", "sequence", "event_id", "ordinal");
	private static final Set<String> DIRECT_RECEIPT_FIELDS = Set.of("source", "sequence", "event_id", "ordinal", "direct_reception");
	private static final Set<String> ENVELOPE_FIELDS = Set.of("source", "sequence", "event_id", "ordinal", "payload");
	private static final Set<String> TAGGED_ENVELOPE_FIELDS = Set.of("source", "sequence", "event_id", "ordinal", "payload", "protocol");

	private MailboxArchiveCodec() {}

	public static DataValue.RecordValue encode(MailboxState state) {
		return Objects.requireNonNull(state).value();
	}

	public static MailboxState decode(DataValue value) {
		try {
			requireFields(value, ROOT_FIELDS);
			long version = number(at(value, "version"));
			if (version != 1 && version != 2 && version != 3)
				throw new ArchiveException(ArchiveException.Code.UNSUPPORTED_VERSION, "Unsupported mailbox schema version");
			var queueValues = list(at(value, "queued"));
			var receiptValues = list(at(value, "receipts"));

			var receipts = new LinkedHashMap<UUID, MailboxState.Receipt>();
			for (var receiptValue : receiptValues) {
				requireFields(receiptValue, version == 3 ? DIRECT_RECEIPT_FIELDS : IDENTITY_FIELDS);
				UUID source = uuid(at(receiptValue, "source"));
				var receipt = new MailboxState.Receipt(nonnegativeLong(at(receiptValue, "sequence")),
					uuid(at(receiptValue, "event_id")), nonnegativeInt(at(receiptValue, "ordinal")),
					version == 3 && bool(at(receiptValue, "direct_reception")));
				if (receipts.putIfAbsent(source, receipt) != null) throw ArchiveException.invalid("Duplicate receipt source");
			}

			var queued = new ArrayList<MessageEnvelope>();
			for (var envelopeValue : queueValues) {
				requireFields(envelopeValue, version == 1 ? ENVELOPE_FIELDS : TAGGED_ENVELOPE_FIELDS);
				DataValue payload = at(envelopeValue, "payload");
				if (!(payload instanceof DataValue.RecordValue record)) throw ArchiveException.invalid("Expected record payload");
				queued.add(new MessageEnvelope(uuid(at(envelopeValue, "source")),
					nonnegativeLong(at(envelopeValue, "sequence")), uuid(at(envelopeValue, "event_id")),
					nonnegativeInt(at(envelopeValue, "ordinal")), record,
					version == 1 ? MessageProtocol.Message.INSTANCE : readProtocol(at(envelopeValue, "protocol"))));
			}
			return new MailboxState(queued, receipts);
		} catch (ComputationException | IllegalArgumentException invalid) {
			if (invalid instanceof ArchiveException archive) throw archive;
			throw ArchiveException.invalid("Invalid mailbox: " + invalid.getMessage());
		}
	}

	private static void requireFields(DataValue value, Set<String> expected) {
		if (!fields(value).keySet().equals(expected)) throw ArchiveException.invalid("Unexpected mailbox fields");
	}

	private static MessageProtocol readProtocol(DataValue value) {
		return switch (string(at(value, "kind"))) {
			case "MESSAGE" -> {
				requireFields(value, Set.of("kind"));
				yield MessageProtocol.Message.INSTANCE;
			}
			case "REQUEST" -> {
				requireFields(value, Set.of("kind", "context"));
				yield new MessageProtocol.Request(RpcArchiveCodec.readRequest(at(value, "context")));
			}
			case "REPLY" -> {
				requireFields(value, Set.of("kind", "correlation"));
				yield new MessageProtocol.Reply(RpcArchiveCodec.readCorrelation(at(value, "correlation")));
			}
			default -> throw ArchiveException.invalid("Unknown message protocol");
		};
	}

	private static UUID uuid(DataValue value) {
		try {
			return UUID.fromString(string(value));
		} catch (IllegalArgumentException invalid) {
			throw ArchiveException.invalid("Invalid UUID");
		}
	}

	private static long nonnegativeLong(DataValue value) {
		long number = number(value);
		if (number < 0) throw ArchiveException.invalid("Expected nonnegative integer");
		return number;
	}

	private static int nonnegativeInt(DataValue value) {
		long number = nonnegativeLong(value);
		if (number > Integer.MAX_VALUE) throw ArchiveException.invalid("Integer exceeds supported range");
		return (int) number;
	}
}
