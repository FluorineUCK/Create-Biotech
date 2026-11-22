package com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.*;

public record MailboxState(List<MessageEnvelope> queued, Map<UUID, Receipt> receipts) {
	public record Receipt(long sequence, UUID eventId, int ordinal, boolean directReception) {
		public Receipt {
			Objects.requireNonNull(eventId);
			if (sequence < 0) throw new IllegalArgumentException("Receipt sequence must be nonnegative");
			if (ordinal < 0) throw new IllegalArgumentException("Receipt ordinal must be nonnegative");
		}
		/** Historical receipts prove buffered admission only, never direct reception. */
		public Receipt(long sequence, UUID eventId, int ordinal) { this(sequence, eventId, ordinal, false); }
	}

	public record Admission(AdmissionStatus status, MailboxState state) {
		public Admission {
			Objects.requireNonNull(status);
			Objects.requireNonNull(state);
		}
	}

	public MailboxState {
		queued = List.copyOf(queued);
		var receiptCopy = new LinkedHashMap<UUID, Receipt>();
		receipts.forEach((source, receipt) -> receiptCopy.put(Objects.requireNonNull(source), Objects.requireNonNull(receipt)));
		receipts = Collections.unmodifiableMap(receiptCopy);

		var lastQueued = new HashMap<UUID, Long>();
		for (var envelope : queued) {
			Objects.requireNonNull(envelope);
			Long previous = lastQueued.put(envelope.source(), envelope.sequence());
			if (previous != null && envelope.sequence() <= previous)
				throw new IllegalArgumentException("Queued sequences must increase per source");
			Receipt receipt = receipts.get(envelope.source());
			if (receipt == null || receipt.sequence() < envelope.sequence())
				throw new IllegalArgumentException("Queued message exceeds its source receipt");
			if (receipt.sequence() == envelope.sequence()
				&& (!receipt.eventId().equals(envelope.eventId()) || receipt.ordinal() != envelope.ordinal()))
				throw new IllegalArgumentException("Queued message conflicts with its source receipt");
			if (receipt.sequence() == envelope.sequence() && receipt.directReception())
				throw new IllegalArgumentException("Directly received message cannot also be queued");
		}
	}

	public static MailboxState empty() {
		return new MailboxState(List.of(), Map.of());
	}

	public Admission admit(MessageEnvelope envelope, MailboxLimits limits) {
		Objects.requireNonNull(envelope);
		Objects.requireNonNull(limits);
		Receipt receipt = receipts.get(envelope.source());
		if (receipt != null) {
			if (envelope.sequence() < receipt.sequence()) return rejected(AdmissionStatus.STALE);
			if (envelope.sequence() == receipt.sequence()) {
				boolean duplicate = envelope.eventId().equals(receipt.eventId()) && envelope.ordinal() == receipt.ordinal();
				return rejected(duplicate ? AdmissionStatus.DUPLICATE : AdmissionStatus.IDENTITY_CONFLICT);
			}
		}
		if (queued.size() >= limits.messages()) return rejected(AdmissionStatus.QUEUE_FULL);
		if (receipt == null && receipts.size() >= limits.sources()) return rejected(AdmissionStatus.SOURCE_LIMIT);

		var nextQueue = new ArrayList<>(queued);
		nextQueue.add(envelope);
		var nextReceipts = new LinkedHashMap<>(receipts);
		nextReceipts.put(envelope.source(), new Receipt(envelope.sequence(), envelope.eventId(), envelope.ordinal()));
		return new Admission(AdmissionStatus.ACCEPTED, new MailboxState(nextQueue, nextReceipts));
	}

	private Admission rejected(AdmissionStatus status) {
		return new Admission(status, this);
	}

	/** Reserve the existing bounded source identity for a ready frame, without creating a buffer entry. */
	public Admission admitDirect(MessageEnvelope envelope, MailboxLimits limits) {
		if (!queued.isEmpty()) return rejected(AdmissionStatus.BUSY);
		var admission = admit(envelope, limits);
		if (admission.status() != AdmissionStatus.ACCEPTED) return admission;
		var nextReceipts = new LinkedHashMap<>(receipts);
		nextReceipts.put(envelope.source(), new Receipt(envelope.sequence(), envelope.eventId(), envelope.ordinal(), true));
		return new Admission(AdmissionStatus.ACCEPTED, new MailboxState(queued, nextReceipts));
	}

	public MailboxState withoutHead() {
		if (queued.isEmpty()) throw new NoSuchElementException("Mailbox is empty");
		return new MailboxState(queued.subList(1, queued.size()), receipts);
	}

	public MailboxState clearQueue() {
		return queued.isEmpty() ? this : new MailboxState(List.of(), receipts);
	}

	public long storageUnits() {
		return queued.isEmpty() && receipts.isEmpty() ? 0 : value().storageUnits();
	}

	public DataValue.RecordValue value() {
		var queueValues = queued.stream().map(MailboxState::envelopeValue).map(DataValue.class::cast).toList();
		var receiptValues = new ArrayList<DataValue>();
		boolean direct = receipts.values().stream().anyMatch(Receipt::directReception);
		receipts.forEach((source, receipt) -> receiptValues.add(receiptValue(source, receipt, direct)));
		var fields = new LinkedHashMap<String, DataValue>();
		fields.put("version", new DataValue.IntegerValue(direct ? 3 : 2));
		fields.put("queued", new DataValue.ListValue(queueValues));
		fields.put("receipts", new DataValue.ListValue(receiptValues));
		return new DataValue.RecordValue(fields);
	}

	private static DataValue.RecordValue envelopeValue(MessageEnvelope envelope) {
		var fields = identityValue(envelope.source(), envelope.sequence(), envelope.eventId(), envelope.ordinal());
		fields.put("payload", envelope.payload());
		fields.put("protocol", envelope.protocol().value());
		return new DataValue.RecordValue(fields);
	}

	private static DataValue.RecordValue receiptValue(UUID source, Receipt receipt, boolean directSchema) {
		var fields = identityValue(source, receipt.sequence(), receipt.eventId(), receipt.ordinal());
		if (directSchema) fields.put("direct_reception", new DataValue.BooleanValue(receipt.directReception()));
		return new DataValue.RecordValue(fields);
	}

	private static LinkedHashMap<String, DataValue> identityValue(UUID source, long sequence, UUID eventId, int ordinal) {
		var fields = new LinkedHashMap<String, DataValue>();
		fields.put("source", new DataValue.TextValue(source.toString()));
		fields.put("sequence", new DataValue.IntegerValue(sequence));
		fields.put("event_id", new DataValue.TextValue(eventId.toString()));
		fields.put("ordinal", new DataValue.IntegerValue(ordinal));
		return fields;
	}
}
