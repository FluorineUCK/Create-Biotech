package com.nobodiiiii.createbiotech.content.biocomputing.persistence;

import static com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveData.*;
import static org.junit.jupiter.api.Assertions.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RequestReplyArchiveTest {
	@Test
	void currentVersionValueNbtRoundTripPreservesEveryDispatchField() {
		var sends = List.of(new ProgramDefinition.Send("endpoint", "payload"),
			ProgramDefinition.Send.request("endpoint", "payload", "pending", "reply"));
		var replies = List.of(ProgramDefinition.Send.reply("payload"));
		var program = new ProgramDefinition(graph(), Map.of(), List.of(), sends, Map.of(
			"request", new ProgramDefinition.Entry(graph(), List.of(), replies),
			"reply", new ProgramDefinition.Entry(graph(), List.of(), List.of())));
		var archive = new ProgramArchive(program, Map.of());

		var encoded = ProgramArchiveCodec.encode(archive);
		assertEquals(new DataValue.IntegerValue(12), encoded.field("version"));
		assertEquals(archive, ProgramArchiveCodec.decode(ValueNbtCodec.decode(
			ValueNbtCodec.encode(encoded, ValueNbtCodecTest.LIMITS), ValueNbtCodecTest.LIMITS)));
	}

	@Test
	void literalHistoricalVersionThreeSendDecodesAsMessage() {
		var fixture = obj("version", 3,
			"graph", literalGraph(), "cells", obj(), "writes", sequence(List.<DataValue>of(), value -> value),
			"sends", sequence(List.of(obj("endpointOutput", "endpoint", "payloadOutput", "payload")), value -> value),
			"entries", obj(), "modules", sequence(List.<DataValue>of(), value -> value));

		assertEquals(List.of(new ProgramDefinition.Send("endpoint", "payload")),
			ProgramArchiveCodec.decode(fixture).program().sends());
	}

	@Test
	void literalVersionTenSendWithoutConditionRemainsUnconditional() {
		var fixture = obj("version", 10,
			"graph", literalGraph(), "cells", obj(), "writes", sequence(List.<DataValue>of(), value -> value),
			"sends", sequence(List.of(obj("endpointOutput", "endpoint", "payloadOutput", "payload",
				"dispatch", "MESSAGE", "requestSlot", "", "replyEntry", "", "continuation", DataValue.NullValue.INSTANCE)), value -> value),
			"entries", obj(), "modules", sequence(List.<DataValue>of(), value -> value));
		var send = ProgramArchiveCodec.decode(fixture).program().sends().getFirst();
		assertEquals(new ProgramDefinition.Send("endpoint", "payload"), send);
		assertTrue(send.enabled(new DataValue.RecordValue(Map.of())));
	}

	@Test
	void versionFourRejectsUnknownDispatchAndMalformedFieldCombination() {
		var encoded = ProgramArchiveCodec.encode(new ProgramArchive(new ProgramDefinition(graph(), Map.of(), List.of(),
			List.of(new ProgramDefinition.Send("endpoint", "payload"))), Map.of()));
		var badMode = replaceFirstSend(encoded, obj("endpointOutput", "endpoint", "payloadOutput", "payload",
			"dispatch", "BROADCAST", "requestSlot", "", "replyEntry", ""));
		var badShape = replaceFirstSend(encoded, obj("endpointOutput", "endpoint", "payloadOutput", "payload",
			"dispatch", "MESSAGE", "requestSlot", "pending", "replyEntry", ""));
		assertThrows(ArchiveException.class, () -> ProgramArchiveCodec.decode(badMode));
		assertThrows(ArchiveException.class, () -> ProgramArchiveCodec.decode(badShape));
	}

	@Test
	void futureVersionRemainsUnsupported() {
		var encoded = ProgramArchiveCodec.encode(new ProgramArchive(new ProgramDefinition(graph(), Map.of(), List.of()), Map.of()));
		var fields = new java.util.LinkedHashMap<>(encoded.fields());
		fields.put("version", new DataValue.IntegerValue(Integer.MAX_VALUE));
		assertEquals(ArchiveException.Code.UNSUPPORTED_VERSION, assertThrows(ArchiveException.class,
			() -> ProgramArchiveCodec.decode(new DataValue.RecordValue(fields))).code());
	}

	private static DataValue.RecordValue replaceFirstSend(DataValue.RecordValue archive, DataValue send) {
		var fields = new java.util.LinkedHashMap<>(archive.fields());
		fields.put("sends", new DataValue.ListValue(List.of(send)));
		return new DataValue.RecordValue(fields);
	}

	private static DataValue literalGraph() {
		return obj("inputs", obj(), "nodes", obj(
			"endpoint", obj("kind", "constant", "value", "factory:input"),
			"payload", obj("kind", "constant", "value", obj("amount", 1))),
			"outputs", obj("endpoint", "endpoint", "payload", "payload"));
	}

	private static ComputationGraph graph() {
		return new ComputationGraph(Map.of(), Map.of(
			"endpoint", new ComputationGraph.Constant(new DataValue.TextValue("factory:input")),
			"payload", new ComputationGraph.Constant(new DataValue.RecordValue(Map.of(
				"amount", new DataValue.IntegerValue(1))))), Map.of("endpoint", "endpoint", "payload", "payload"));
	}
}
