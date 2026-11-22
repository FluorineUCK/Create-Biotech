package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerFlowHostTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerXBusExchangeTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerXBusRecoveryTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.XBusExchange.Status.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowRuntime;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowBlockHost;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationException;
import com.nobodiiiii.createbiotech.content.biocomputing.network.XBusAccess;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ComputerXBusPublicationTest {
    enum DirtyFailure {
        ARCHIVE, MEMORY, ARITHMETIC;
        RuntimeException create() {
            return switch (this) {
                case ARCHIVE -> new ArchiveException(ArchiveException.Code.LIMIT_EXCEEDED, "Dirty notification failed after publication");
                case MEMORY -> new ComputationException(ComputationException.Code.MEMORY_LIMIT, "dirty", "Published already");
                case ARITHMETIC -> new ComputationException(ComputationException.Code.ARITHMETIC_OVERFLOW, "dirty", "Published already");
            };
        }
    }

    static Stream<Arguments> failuresAndSides() {
        return Arrays.stream(DirtyFailure.values()).flatMap(failure -> Stream.of(false, true).flatMap(reader ->
            Stream.of(false, true).map(reverse -> Arguments.of(failure, reader, reverse))));
    }

    static Stream<Arguments> failuresAndOrder() {
        return Arrays.stream(DirtyFailure.values()).flatMap(failure ->
            Stream.of(false, true).map(reverse -> Arguments.of(failure, reverse)));
    }

    @ParameterizedTest @MethodSource("failuresAndSides")
    void dirtyFailureCannotBeReportedAsAnUncommittedExchange(DirtyFailure kind, boolean readerFails, boolean reverse) {
        var failure = kind.create(); int failing = readerFails ? 1 : 0;
        var armed = new boolean[1]; var notifications = new int[2];
        var hosts = new ComputerBlockHost[2];
        for (int i = 0; i < hosts.length; i++) {
            int side = i;
            hosts[i] = new ComputerBlockHost(() -> {
                if (!armed[0]) return;
                assertPublished(hosts); notifications[side]++;
                if (side == failing) throw failure;
            });
        }
        start(hosts[0], writer(), INPUTS); start(hosts[1], reader(), EMPTY);
        var competitor = new ComputerBlockHost(() -> {}); start(competitor, writer(), INPUTS);
        armed[0] = true;
        var network = XBusAccess.network(List.of(access(hosts[0], "x0"), access(hosts[1], "x1"), access(competitor, "x0")));

        var left = reverse ? pin(hosts[1], "x1") : pin(hosts[0], "x0");
        var right = reverse ? pin(hosts[0], "x0") : pin(hosts[1], "x1");
        assertSame(failure, assertThrows(failure.getClass(), () -> network.exchange(left, right)));
        assertPublished(hosts); assertArrayEquals(new int[] { 1, 1 }, notifications);
        assertEquals(FlowRuntime.Status.COMPLETE, competitor.flowSnapshot().orElseThrow().state().status());
        assertEquals(hosts[0].flowSnapshot().orElseThrow().receipts(), competitor.flowSnapshot().orElseThrow().receipts(),
            "A dirty failure happens after ALL cohort participants, including competing writers, are published");
        assertEquals(STALE, network.exchange(left, right));
        var published = List.of(hosts[0].write(), hosts[1].write(), competitor.write());
        var retry = XBusAccess.network(List.of(access(hosts[0], "x0"), access(hosts[1], "x1"), access(competitor, "x0")));
        assertEquals(NOT_READY, retry.exchange(pin(hosts[0], "x0"), pin(hosts[1], "x1")));
        assertEquals(published, List.of(hosts[0].write(), hosts[1].write(), competitor.write()));
        assertArrayEquals(new int[] { 1, 1 }, notifications);
        assertEquals(hosts[0].flowSnapshot(), restore(hosts[0].write()).flowSnapshot());
        assertEquals(hosts[1].flowSnapshot(), restore(hosts[1].write()).flowSnapshot());
        assertEquals(competitor.flowSnapshot(), restore(competitor.write()).flowSnapshot());
    }

    @ParameterizedTest @MethodSource("failuresAndSides")
    void recoveryDirtyFailureKeepsTheRepairedCheckpointAndDoesNotExecuteItsNextInstruction(
            DirtyFailure kind, boolean repairReader, boolean reverse) {
        var failure = kind.create(); int repaired = repairReader ? 1 : 0;
        var armed = new boolean[1]; var notifications = new int[2]; var committed = new FlowBlockHost.Saved[2];
        var hosts = new ComputerBlockHost[2];
        for (int i = 0; i < hosts.length; i++) {
            int side = i;
            hosts[i] = new ComputerBlockHost(() -> {
                if (!armed[0]) return;
                notifications[side]++;
                assertEquals(committed[side], hosts[side].flowSnapshot().orElseThrow());
                if (side == repaired) throw failure;
            });
        }
        start(hosts[0], new FlowProgram(Map.of("packet", PACKET), List.of(new FlowProgram.Write("write", "x0", "packet"),
            new FlowProgram.AwaitReadable("after", "x0"))), INPUTS);
        start(hosts[1], new FlowProgram(Map.of(), List.of(new FlowProgram.Read("read", "x1", "packet", PACKET),
            new FlowProgram.AwaitReadable("after", "x1"))), EMPTY);
        var old = hosts[repaired].write(); assertEquals(EXCHANGED, exchange(hosts[0], hosts[1]));
        committed[repaired] = hosts[repaired].flowSnapshot().orElseThrow();
        hosts[1 - repaired].tick(LIMITS, true); committed[1 - repaired] = hosts[1 - repaired].flowSnapshot().orElseThrow();
        var advanced = hosts[1 - repaired].write();
        hosts[repaired].read(old); assertTrue(hosts[repaired].synchronize(LIMITS, true));
        var competitor = new ComputerBlockHost(() -> {}); start(competitor, writer(), INPUTS);
        var beforeCompetitor = competitor.write(); armed[0] = true;
        var network = XBusAccess.network(List.of(access(hosts[0], "x0"), access(hosts[1], "x1"), access(competitor, "x0")));
        var left = reverse ? pin(hosts[1], "x1") : pin(hosts[0], "x0");
        var right = reverse ? pin(hosts[0], "x0") : pin(hosts[1], "x1");
        assertSame(failure, assertThrows(failure.getClass(), () -> network.exchange(left, right)));
        assertEquals(List.of(committed), List.of(hosts[0].flowSnapshot().orElseThrow(), hosts[1].flowSnapshot().orElseThrow()));
        assertEquals(advanced, hosts[1 - repaired].write());
        assertEquals(FlowRuntime.Status.READY, hosts[repaired].flowSnapshot().orElseThrow().state().status());
        assertEquals(1, notifications[repaired]); assertEquals(0, notifications[1 - repaired]);
        assertEquals(beforeCompetitor, competitor.write());
        assertEquals(STALE, network.exchange(left, right));
        var retry = XBusAccess.network(List.of(access(hosts[0], "x0"), access(hosts[1], "x1"), access(competitor, "x0")));
        var after = List.of(hosts[0].write(), hosts[1].write());
        assertEquals(NOT_READY, retry.exchange(left, right));
        assertEquals(after, List.of(hosts[0].write(), hosts[1].write()));
        assertEquals(1, notifications[repaired]); assertEquals(0, notifications[1 - repaired]);
        for (var host : hosts) {
            assertTrue(host.loaded()); assertTrue(host.diagnostic().isEmpty());
            var restored = new ComputerBlockHost(() -> {}); restored.read(host.write()); assertTrue(restored.synchronize(LIMITS, true));
            assertEquals(host.flowSnapshot(), restored.flowSnapshot(), "Loading a READY checkpoint must not run its successor");
        }
    }

    @ParameterizedTest @MethodSource("failuresAndOrder")
    void readableNotificationFailureDoesNotConsumeTheWriterOrExecuteTheFollowingRead(DirtyFailure kind, boolean reverse) {
        var failure = kind.create(); var armed = new boolean[1]; var notifications = new int[1];
        var writer = new ComputerBlockHost(() -> { if (armed[0]) fail("A readable probe must not dirty the writer"); });
        var reader = new ComputerBlockHost(() -> { if (armed[0]) { notifications[0]++; throw failure; } });
        start(writer, writer(), INPUTS);
        start(reader, new FlowProgram(Map.of(), List.of(new FlowProgram.AwaitReadable("wait", "x1"),
            new FlowProgram.Read("read", "x1", "packet", PACKET))), EMPTY);
        var beforeWriter = writer.write(); armed[0] = true;
        var network = XBusAccess.network(List.of(access(writer, "x0"), access(reader, "x1")));
        var left = reverse ? pin(reader, "x1") : pin(writer, "x0");
        var right = reverse ? pin(writer, "x0") : pin(reader, "x1");
        assertSame(failure, assertThrows(failure.getClass(), () -> network.exchange(left, right)));
        assertEquals(beforeWriter, writer.write()); assertEquals(1, notifications[0]);
        var ready = reader.flowSnapshot().orElseThrow();
        assertEquals(FlowRuntime.Status.READY, ready.state().status());
        assertEquals(1, ready.state().cursor()); assertEquals(EMPTY, ready.state().locals());
        assertTrue(ready.receipts().isEmpty()); assertTrue(reader.diagnostic().isEmpty()); assertTrue(reader.loaded());
        assertEquals(STALE, network.exchange(left, right));
        var retry = XBusAccess.network(List.of(access(writer, "x0"), access(reader, "x1")));
        assertEquals(NOT_READY, retry.exchange(left, right)); assertEquals(1, notifications[0]);
        assertEquals(ready, reader.flowSnapshot().orElseThrow());

        armed[0] = false; reader.tick(LIMITS, true);
        assertEquals(EXCHANGED, exchange(writer, reader));
        assertEquals(INPUTS, reader.flowSnapshot().orElseThrow().state().locals());
        assertEquals(1, reader.flowSnapshot().orElseThrow().receipts().size());
        assertEquals(1, writer.flowSnapshot().orElseThrow().receipts().size());
    }

    private static void assertPublished(ComputerBlockHost[] hosts) {
        for (var host : hosts) {
            var saved = host.flowSnapshot().orElseThrow();
            assertEquals(FlowRuntime.Status.COMPLETE, saved.state().status());
            assertEquals(1, saved.receipts().size());
            assertTrue(host.diagnostic().isEmpty());
        }
        assertEquals(hosts[0].flowSnapshot().orElseThrow().receipts(), hosts[1].flowSnapshot().orElseThrow().receipts());
        assertEquals(INPUTS, hosts[1].flowSnapshot().orElseThrow().state().locals());
    }
}
