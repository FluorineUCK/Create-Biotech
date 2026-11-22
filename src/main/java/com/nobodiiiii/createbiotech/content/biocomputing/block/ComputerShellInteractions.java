package com.nobodiiiii.createbiotech.content.biocomputing.block;

import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.common.NeoForge;

/** Physical player/lifecycle hooks, never executable program operations or world-object data values. */
public final class ComputerShellInteractions {
    private ComputerShellInteractions() {}

    public static void beforeRemoval(BlockState state, Level level, BlockPos pos, BlockState replacement) {
        if (level instanceof ServerLevel server && state.getBlock() instanceof ComputerShellBlock
                && !(replacement.getBlock() instanceof ComputerShellBlock))
            NeoForge.EVENT_BUS.post(new Removing(server, pos));
    }

    /** PASS means use the casing's original Create wrench fallback. Client acknowledgement is unchanged. */
    public static InteractionResult sneakWrench(BlockState state, UseOnContext context) {
        if (!(context.getPlayer() instanceof ServerPlayer)) return InteractionResult.SUCCESS;
        var event = NeoForge.EVENT_BUS.post(new SneakWrench(state, context));
        return event.handledResult().orElse(InteractionResult.PASS);
    }

    /** Emitted before the physical shell's original removal callback. This notification cannot cancel removal. */
    public static final class Removing extends Event {
        private final ServerLevel level;
        private final BlockPos position;
        public Removing(ServerLevel level, BlockPos position) {
            this.level = Objects.requireNonNull(level); this.position = Objects.requireNonNull(position).immutable();
        }
        public ServerLevel level() { return level; }
        public BlockPos position() { return position; }
    }

    /** A selected device interaction may consume the wrench action; absent consumers never block dismantling. */
    public static final class SneakWrench extends Event {
        private final BlockState state;
        private final UseOnContext context;
        private InteractionResult result;
        public SneakWrench(BlockState state, UseOnContext context) {
            this.state = Objects.requireNonNull(state); this.context = Objects.requireNonNull(context);
        }
        public BlockState state() { return state; }
        public UseOnContext context() { return context; }
        public Optional<InteractionResult> handledResult() { return Optional.ofNullable(result); }
        public void handle(InteractionResult result) {
            if (this.result != null) throw new IllegalStateException("Shell interaction already handled");
            if (result == null || result == InteractionResult.PASS)
                throw new IllegalArgumentException("Handled shell interaction must not fall through");
            this.result = result;
        }
    }
}
