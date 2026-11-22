package com.nobodiiiii.createbiotech.content.biocomputing.network;

import com.nobodiiiii.createbiotech.CreateBiotech;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/** Single normal Flow driver, after all physical block ticks have registered their actual participants.
 * Event programs retain their existing local driver. Neither this registry nor a Cluster pools CPU budgets. */
@EventBusSubscriber(modid = CreateBiotech.MOD_ID)
public final class ComputerExecutionTicks {
    private ComputerExecutionTicks() {}
    private static final Map<ServerLevel, Long> LAST_TICK = new WeakHashMap<>();

    @SubscribeEvent public static void endTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        long tick = level.getGameTime();
        if (LAST_TICK.getOrDefault(level, Long.MIN_VALUE) == tick) return;
        // Reserve before publication; a callback failure must not replay this activity.
        LAST_TICK.put(level, tick);
        var computers = ComputerExecutionDirectory.snapshot(level).ticking();
        var scopes = computers.stream().filter(c -> c.samplingEdges()).toList();
        var cycle = new ComputerExecutionCycle(level, computers, tick);
        for (int round = 0; ; round++) {
            for(var scope:scopes)scope.sampleEdges(tick); // same pre-execution boundary, even without a program on the scope
            var result = cycle.advanceRound(seed(tick, round));
            // Use results already produced by execution, not another topology scan or history inspection.
            if (!result.diagnostics().isEmpty()) for (var computer : computers) computer.wireSnapshot().ifPresent(saved -> {
                var code = result.diagnostics().get(saved.state().computer());
                if (code != null) computer.observeXBusExecution(code);
            });
            if (!result.progressed()) break;
        }
    }

    /** Host default, not a recovery identity: test case 0, signed low-32 world cycle, local instruction round.
     * Preserve the original signed widening, addition and overflow; do not mask the cycle to 16 bits. */
    public static long seed(long gameTime, int round) {
        return (1L << 32) + (((long)(int)gameTime + 1L) << 16) + (long)round + 1L;
    }
}
