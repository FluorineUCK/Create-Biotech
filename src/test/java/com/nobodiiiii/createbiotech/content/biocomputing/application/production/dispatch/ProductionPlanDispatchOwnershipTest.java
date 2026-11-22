package com.nobodiiiii.createbiotech.content.biocomputing.application.production.dispatch;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ProductionPlanDispatchOwnershipTest {
    @Test void actualHostUsesOneDispatchOwnerAndRoutesEverySubmittedConsumerToIt() throws Exception {
        Class<?> host = Class.forName("com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationServer$Runtime");
        assertEquals(1, Arrays.stream(host.getDeclaredFields()).filter(field -> field.getType() == ProductionPlanDispatch.class).count());
        assertFalse(Arrays.stream(host.getDeclaredFields()).anyMatch(field -> field.getName().equals("submittedRuns")));
        assertFalse(Arrays.stream(host.getDeclaredMethods()).anyMatch(method -> method.getName().equals("dispatchReadyPlans") || method.getName().equals("sourceComputerExhausted")));
        String source = Files.readString(Path.of("src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/runtime/FactoryOrchestrationServer.java"));
        assertFalse(source.contains("submittedRuns"));
        assertTrue(source.indexOf("planDispatch.advance();") < source.indexOf("localExpansion.advance();"));
        assertTrue(source.contains("planDispatch.markSubmitted(runId)"), "remote replay and ordinary material dispatch must share one owner");
        assertTrue(source.contains("planDispatch.forgetSubmitted(dispatch.runId())"), "confirmed rejection must clear this exact owner's fence");
        assertTrue(source.contains("liveRunIds.addAll(planDispatch.submittedRunIds())"), "retirement sees the same immutable submitted snapshot");
        assertTrue(source.contains("new ProductionStatus(runId, planDispatch.isSubmitted(runId)"), "status reads the actual owner's fence");
        assertTrue(source.contains("Runtime.this.waitSource(state, goal, reason)"), "adapter preserves the second uncached snapshot in waitSource");
    }
}
