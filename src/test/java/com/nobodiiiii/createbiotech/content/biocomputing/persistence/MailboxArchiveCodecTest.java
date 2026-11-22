package com.nobodiiiii.createbiotech.content.biocomputing.persistence;

import static org.junit.jupiter.api.Assertions.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class MailboxArchiveCodecTest {
	private static final UUID SOURCE = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final UUID EVENT = UUID.fromString("10000000-0000-0000-0000-000000000001");

	@Test void versionTwoRoundTripsAndStorageIsExact() {
		var state = MailboxState.empty().admit(message(SOURCE, 3), MailboxLimits.DEFAULT).state();
		var value = MailboxArchiveCodec.encode(state);
		assertEquals(new DataValue.IntegerValue(2), value.field("version"));
		assertEquals(state, MailboxArchiveCodec.decode(value));
		assertEquals(value.storageUnits(), state.storageUnits());
		assertEquals(0, MailboxState.empty().storageUnits());
	}

	@Test void rejectsUnknownVersionAndUnexpectedFields() {
		var value = MailboxArchiveCodec.encode(MailboxState.empty());
		var unknownFields = new LinkedHashMap<>(value.fields());
		unknownFields.put("version", new DataValue.IntegerValue(4));
		var unknownVersion = new DataValue.RecordValue(unknownFields);
		assertEquals(ArchiveException.Code.UNSUPPORTED_VERSION,
			assertThrows(ArchiveException.class, () -> MailboxArchiveCodec.decode(unknownVersion)).code());
		var extraFields = new LinkedHashMap<>(value.fields());
		extraFields.put("extra", DataValue.NullValue.INSTANCE);
		assertThrows(ArchiveException.class,
			() -> MailboxArchiveCodec.decode(new DataValue.RecordValue(extraFields)));
	}

	@Test void rejectsMalformedIdentityDuplicateReceiptsAndInconsistentQueue() {
		var value = MailboxArchiveCodec.encode(MailboxState.empty().admit(message(SOURCE, 3), MailboxLimits.DEFAULT).state());
		var originalReceipts = ((DataValue.ListValue) value.field("receipts")).values();
		var duplicateFields = new LinkedHashMap<>(value.fields());
		duplicateFields.put("receipts", new DataValue.ListValue(List.of(originalReceipts.getFirst(), originalReceipts.getFirst())));
		assertThrows(ArchiveException.class,
			() -> MailboxArchiveCodec.decode(new DataValue.RecordValue(duplicateFields)));
		var badReceipt = new LinkedHashMap<>(((DataValue.RecordValue) originalReceipts.getFirst()).fields());
		badReceipt.put("source", new DataValue.TextValue("bad"));
		var malformedFields = new LinkedHashMap<>(value.fields());
		malformedFields.put("receipts", new DataValue.ListValue(List.of(new DataValue.RecordValue(badReceipt))));
		assertThrows(ArchiveException.class,
			() -> MailboxArchiveCodec.decode(new DataValue.RecordValue(malformedFields)));
		var inconsistentFields = new LinkedHashMap<>(value.fields());
		inconsistentFields.put("receipts", new DataValue.ListValue(List.of()));
		assertThrows(ArchiveException.class,
			() -> MailboxArchiveCodec.decode(new DataValue.RecordValue(inconsistentFields)));
	}

	@Test void codecDoesNotConfuseConfiguredMailboxLimitsWithArchiveBounds() {
		var state = MailboxState.empty();
		var limits = new MailboxLimits(32, 80);
		for (int i = 0; i < 20; i++) state = state.admit(message(new UUID(0, i + 1), 1), limits).state();
		var encoded = MailboxArchiveCodec.encode(state);
		assertEquals(state, MailboxArchiveCodec.decode(encoded));
		assertThrows(ArchiveException.class,
			() -> ValueNbtCodec.encode(encoded, new ArchiveLimits(4, 20, 10)));
	}

	private static MessageEnvelope message(UUID source, long sequence) {
		return new MessageEnvelope(source, sequence, EVENT, 2,
			new DataValue.RecordValue(Map.of("amount", new DataValue.IntegerValue(7))));
	}
}
