package com.nobodiiiii.createbiotech.content.biocomputing.cluster;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterState.Side.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.RequestReplyFixtures.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class ClusterComputerRoundTripTest {
    @Test void twoActualComputersKeepIndependentRamAndRejectOldReplyAcrossRouterNbtRestore() {
        var limits = ClusterRouterTest.LIMITS; var archive = limits.archive();
        var client = new ComputerRuntime(UUID.randomUUID(), client(), HARDWARE);
        var worker = new ComputerRuntime(UUID.randomUUID(), server(), HARDWARE);
        var router = new ClusterRouter(() -> {});
        var member = new ClusterConfiguration.Member(worker.id(), new BlockPos(9, 70, 9));
        router.configure(new ClusterConfiguration(Map.of("A", member), Map.of("server",
            new ClusterConfiguration.Port("A", "worker", "result", "display", true))), limits);
        for (int query : List.of(7, 9)) {
            client.begin(input("query", query)); finish(client); var output = client.outbound().orElseThrow();
            assertEquals(AdmissionStatus.ACCEPTED, router.receive(EXTERNAL, output.endpoint(), request(client.id(), output, "client"), limits));
            acknowledge(client);
        }
        var restored = new ClusterRouter(() -> {}); restored.read(router.write()); router = restored;
        client = restore(client, archive); worker = restore(worker, archive);
        for (int i = 0; i < 2; i++) {
            var forward = router.outbound(limits).orElseThrow();
            assertEquals(worker.id(), forward.target().member().orElseThrow().identity()); assertEquals("worker", forward.target().port());
            assertEquals(AdmissionStatus.ACCEPTED, worker.receive(forward.envelope())); assertTrue(router.acknowledge(forward, limits));
        }
        assertEquals(new DataValue.IntegerValue(100), worker.memory().field("result"));
        assertEquals(new DataValue.IntegerValue(0), client.memory().field("result"));
        for (int expected : List.of(7, 9)) {
            worker.step(); finish(worker); assertEquals(new DataValue.IntegerValue(expected), worker.memory().field("result"));
            var output = worker.outbound().orElseThrow(); assertEquals("result", output.endpoint());
            assertEquals(AdmissionStatus.ACCEPTED, router.receive(INTERNAL, output.endpoint(), reply(worker.id(), output), limits)); acknowledge(worker);
            var returned = router.outbound(limits).orElseThrow(); assertEquals("client", returned.target().port());
            var admitted = client.receive(returned.envelope());
            assertEquals(expected == 7 ? AdmissionStatus.STALE_REPLY : AdmissionStatus.ACCEPTED, admitted);
            assertTrue(admitted.accepted()); assertTrue(router.acknowledge(returned, limits));
            assertEquals(new DataValue.IntegerValue(0), client.memory().field("result"));
        }
        client.step(); finish(client); assertEquals(new DataValue.IntegerValue(9), client.memory().field("result"));
        assertTrue(client.snapshot().rpc().pending().isEmpty()); assertTrue(router.snapshot(limits).orElseThrow().pending().isEmpty());
        assertTrue(router.outbound(limits).isEmpty());
    }
    private static ComputerRuntime restore(ComputerRuntime runtime, ArchiveLimits limits) {
        var restored = ComputerRuntime.restore(ComputerNbtCodec.decode(ComputerNbtCodec.encode(runtime.snapshot(), limits), limits), HARDWARE); restored.load(); return restored;
    }
}
