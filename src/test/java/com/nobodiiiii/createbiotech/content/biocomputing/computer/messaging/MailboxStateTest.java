package com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.*;
import org.junit.jupiter.api.Test;
class MailboxStateTest {
	private static final UUID A = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final UUID B = UUID.fromString("00000000-0000-0000-0000-000000000002");
	private static final UUID EVENT = UUID.fromString("10000000-0000-0000-0000-000000000001");

	@Test void copiesCollectionsAndPreservesFifo() {
		var q=new ArrayList<>(List.of(msg(A,1,EVENT,0)));
		var r=new LinkedHashMap<UUID,MailboxState.Receipt>();
		r.put(A,new MailboxState.Receipt(1,EVENT,0));
		var s=new MailboxState(q,r);
		q.clear();
		r.clear();
		assertEquals(1,s.queued().size());
		assertThrows(UnsupportedOperationException.class,()->s.queued().clear());
		assertThrows(UnsupportedOperationException.class,()->s.receipts().clear());
		assertTrue(s.withoutHead().queued().isEmpty());
		assertThrows(NoSuchElementException.class,()->MailboxState.empty().withoutHead());
	}
	@Test void increasingSequencesAppendAndAdvanceHighWater() {
		var a=MailboxState.empty().admit(msg(A,4,EVENT,0),new MailboxLimits(2,1));
		var b=a.state().admit(msg(A,5,EVENT,1),new MailboxLimits(2,1));
		assertEquals(AdmissionStatus.ACCEPTED,a.status());
		assertEquals(List.of(4L,5L),b.state().queued().stream().map(MessageEnvelope::sequence).toList());
		assertEquals(new MailboxState.Receipt(5,EVENT,1),b.state().receipts().get(A));
	}
	@Test void updatingReceiptPreservesSourceInsertionOrder() {
		var limits = new MailboxLimits(4, 2);
		var state = MailboxState.empty().admit(msg(A, 1, EVENT, 0), limits).state();
		state = state.admit(msg(B, 1, EVENT, 0), limits).state();
		state = state.admit(msg(A, 2, EVENT, 1), limits).state();
		assertEquals(List.of(A, B), List.copyOf(state.receipts().keySet()));
	}
	@Test void identityChecksPrecedeCapacity() {
		var l=new MailboxLimits(1,1);
		var s=MailboxState.empty().admit(msg(A,7,EVENT,2),l).state();
		assertEquals(AdmissionStatus.DUPLICATE,s.admit(msg(A,7,EVENT,2),l).status());
		assertEquals(AdmissionStatus.STALE,s.admit(msg(A,6,EVENT,2),l).status());
		assertEquals(AdmissionStatus.IDENTITY_CONFLICT,s.admit(msg(A,7,EVENT,3),l).status());
		assertEquals(AdmissionStatus.IDENTITY_CONFLICT,s.admit(msg(A,7,B,2),l).status());
		assertEquals(AdmissionStatus.QUEUE_FULL,s.admit(msg(A,8,EVENT,3),l).status());
	}
	@Test void capsRejectWithoutEvictionOrMutation() {
		var one=MailboxState.empty().admit(msg(A,1,EVENT,0),new MailboxLimits(2,1)).state();
		var sr=one.admit(msg(B,1,EVENT,0),new MailboxLimits(2,1));
		assertEquals(AdmissionStatus.SOURCE_LIMIT,sr.status());
		assertSame(one,sr.state());
		var full=one.admit(msg(A,2,EVENT,1),new MailboxLimits(2,1)).state();
		var qr=full.admit(msg(A,3,EVENT,2),new MailboxLimits(2,1));
		assertEquals(AdmissionStatus.QUEUE_FULL,qr.status());
		assertSame(full,qr.state());
	}
	@Test void clearQueueRetainsReceipts() {
		var s=MailboxState.empty().admit(msg(A,1,EVENT,0),MailboxLimits.DEFAULT).state();
		var c=s.clearQueue();
		assertTrue(c.queued().isEmpty());
		assertEquals(s.receipts(),c.receipts());
	}
	@Test void validatesLimitsIdentitiesAndQueueReceiptConsistency() {
		assertThrows(IllegalArgumentException.class,()->new MailboxLimits(0,1));
		assertThrows(IllegalArgumentException.class,()->msg(A,-1,EVENT,0));
		assertThrows(IllegalArgumentException.class,()->msg(A,1,EVENT,-1));
		assertThrows(IllegalArgumentException.class,()->new MailboxState.Receipt(-1,EVENT,0));
		assertThrows(IllegalArgumentException.class,()->new MailboxState(List.of(msg(A,2,EVENT,0)),Map.of()));
		assertThrows(IllegalArgumentException.class,()->new MailboxState(List.of(msg(A,2,EVENT,0)),Map.of(A,new MailboxState.Receipt(1,EVENT,0))));
		assertThrows(IllegalArgumentException.class,()->new MailboxState(List.of(msg(A,2,EVENT,0)),Map.of(A,new MailboxState.Receipt(2,B,0))));
		assertThrows(IllegalArgumentException.class,()->new MailboxState(List.of(msg(A,2,EVENT,0),msg(A,2,EVENT,0)),Map.of(A,new MailboxState.Receipt(2,EVENT,0))));
	}
	@Test void acceptedIncludesOnlyAcceptedDuplicateAndObsoleteReplies() {
		for(var s:AdmissionStatus.values())assertEquals(s==AdmissionStatus.ACCEPTED||s==AdmissionStatus.DUPLICATE||s==AdmissionStatus.STALE_REPLY,s.accepted());
	}
	private static MessageEnvelope msg(UUID s,long n,UUID e,int o) {
		return new MessageEnvelope(s,n,e,o,new DataValue.RecordValue(Map.of("value",new DataValue.IntegerValue(n))));
	}
}
