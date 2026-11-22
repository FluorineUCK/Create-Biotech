package com.nobodiiiii.createbiotech.content.biocomputing.device.localtext;

import com.nobodiiiii.createbiotech.content.biocomputing.device.narrator.NarratorOutputAudience;
import com.nobodiiiii.createbiotech.content.biocomputing.device.narrator.NarratorOutputText;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.BiocomputingConfig;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.SerialHardware;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Fixed by the placed block. Neither a serial frame nor a menu action can change the delivery channel. */
enum LocalTextOutputKind {
    NARRATION("narrator", "speak", "speech", "BiocomputingNarrator", 1),
    CHAT("chat", "send", "chat", "BiocomputingChat", 20);

    final String id, operation, errors, archivePrefix;
    private final int ticksPerWindow;
    private record Quota(long window, int used) {}
    private final Map<ServerLevel, Quota> quotas = new WeakHashMap<>();

    LocalTextOutputKind(String id, String operation, String errors, String archivePrefix, int ticksPerWindow) {
        this.id = id; this.operation = operation; this.errors = errors; this.archivePrefix = archivePrefix;
        this.ticksPerWindow = ticksPerWindow;
    }

    String command() { return id + "_output_command"; }
    String reply() { return id + "_output_reply"; }
    String rateLimit() { return errors + "_rate_limit"; }
    String budgetExhausted() { return errors + "_budget_exhausted"; }
    String invalidText() { return "invalid_" + errors + "_text"; }
    String textLimit() { return errors + "_text_limit"; }

    String checkText(String line, int bytes) {
        try { NarratorOutputText.checkedBytes(line, bytes); }
        catch (IllegalArgumentException invalid) {
            return "speech_text_limit".equals(invalid.getMessage()) ? textLimit() : invalidText();
        }
        return this == CHAT && !ChatOutputDelivery.safeLiteral(line) ? invalidText() : "ok";
    }

    record Limits(int bytes, int range, int cooldown, int dimensionQuota) {}
    Limits limits(BiocomputingConfig config, boolean loaded) {
        return this == CHAT ? new Limits(config.chatOutputBytes(loaded), config.chatOutputRange(loaded),
            config.chatOutputCooldown(loaded), config.chatOutputPerDimensionSecond(loaded))
            : new Limits(config.narratorOutputBytes(loaded), config.narratorOutputRange(loaded),
                config.narratorOutputCooldown(loaded), config.narratorOutputPerDimensionTick(loaded));
    }
    SerialHardware serial(BiocomputingConfig config, boolean loaded) {
        return this == CHAT ? config.chatOutputSerial(loaded) : config.narratorOutputSerial(loaded);
    }

    boolean hasRoom(ServerLevel level, long tick, int limit) {
        var quota = quotas.get(level);
        return quota == null || quota.window() != tick / ticksPerWindow || quota.used() < limit;
    }

    String dispatch(ServerLevel level, BlockPos pos, long tick, Limits limits, String text) {
        if (!hasRoom(level, tick, limits.dimensionQuota())) return budgetExhausted();
        long window = tick / ticksPerWindow;
        quotas.compute(level, (key, current) -> new Quota(window,
            current != null && current.window() == window ? current.used() + 1 : 1));
        return this == CHAT ? ChatOutputDelivery.dispatch(level, pos, limits.range(), text)
            : NarratorOutputAudience.dispatch(level, pos, limits.range(), text);
    }
}
