package com.nobodiiiii.createbiotech.content.biocomputing.device.localtext;

import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/** Actual vanilla chat-history output. The program supplies only the literal body. */
final class ChatOutputDelivery {
    private ChatOutputDelivery() {}

    static boolean safeLiteral(String text) {
        // Prevent legacy color/style escapes and directional formatting that could disguise the fixed source.
        return text.codePoints().noneMatch(cp -> cp == 0x00a7 || cp == 0x061c || cp == 0x200e || cp == 0x200f
            || cp >= 0x202a && cp <= 0x202e || cp >= 0x2066 && cp <= 0x2069);
    }

    static Component message(BlockPos source, String literal) {
        return Component.translatable("create_biotech.chat_output.message", source.getX(), source.getY(), source.getZ(),
            Component.literal(literal));
    }

    static String dispatch(ServerLevel level, BlockPos emitter, int range, String literal) {
        var center = SubLevelCompat.toWorld(level, emitter, Vec3.atCenterOf(emitter));
        var message = message(BlockPos.containing(center), literal);
        boolean dispatched = false;
        double rangeSquared = (double)range * range;
        for (var player : level.players()) {
            if (player.connection == null || player.serverLevel() != level
                || !(player.distanceToSqr(center) <= rangeSquared)) continue;
            player.sendSystemMessage(message, false);
            dispatched = true;
        }
        return dispatched ? "dispatched" : "no_recipients";
    }
}
