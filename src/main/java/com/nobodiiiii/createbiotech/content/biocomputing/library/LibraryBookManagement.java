package com.nobodiiiii.createbiotech.content.biocomputing.library;

import com.nobodiiiii.createbiotech.content.biocomputing.library.menu.LibraryBookMenu;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Local book management owns geometry and sessions, using the exact host-bound generic player access. */
public final class LibraryBookManagement {
    private LibraryBookManagement() {}

    public static boolean open(LibraryPlayerAccess access, ServerPlayer player) {
        Objects.requireNonNull(access); Objects.requireNonNull(player);
        var owner = access.owner();
        var pos = owner.getBlockPos().immutable();
        if (!(owner.getLevel() instanceof ServerLevel level) || !present(owner, level, pos)
            || !access.canUse(player)) return false;
        if (!available(owner, level, pos)) {
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                "create_biotech.biocomputing.library.code.owner_unavailable"), false);
            return false;
        }
        var provider = LibraryBookMenu.provider(actor -> new LocalAccess(access, level, pos, actor).world,
            actor -> owner.getBehaviour(LibraryServiceBehaviour.TYPE),
            actor -> owner.getBehaviour(com.nobodiiiii.createbiotech.content.biocomputing.library.drive.LibraryControlBehaviour.TYPE));
        return player.openMenu(provider, provider::writeOpeningData).isPresent();
    }

    private static boolean available(SmartBlockEntity owner, ServerLevel level, BlockPos pos) {
        return owner instanceof LibraryCoreHost host && host.libraryIdentityReady()
            && present(owner, level, pos);
    }

    private static boolean present(SmartBlockEntity owner, ServerLevel level, BlockPos pos) {
        return !owner.isRemoved() && owner.getLevel() == level && owner.getBlockPos().equals(pos)
            && level.isLoaded(pos) && level.getBlockEntity(pos) == owner;
    }

    private static final class LocalAccess {
        private final SmartBlockEntity owner;
        private final ServerLevel level;
        private final BlockPos pos;
        private final ServerPlayer player;
        private final LibraryPlayerAccess access;
        private final LibraryBookWorldAccess world;

        private LocalAccess(LibraryPlayerAccess access, ServerLevel level, BlockPos pos, ServerPlayer player) {
            this.owner = access.owner(); this.level = level; this.pos = pos; this.player = player; this.access = access;
            world = new LibraryBookWorldAccess(level, pos, player, this::valid, () -> access.canEdit(player), this::shelves);
            world.observe(List.of(pos));
        }

        private boolean valid() { return available(owner, level, pos) && access.canUse(player); }

        private List<BlockPos> shelves() {
            var physical = LibraryStructureScanner.scan(new LibraryStructureWorldView(level), pos,
                CBConfigs.SERVER.library.libraryMaxMembers.get(), CBConfigs.SERVER.library.libraryMaxSpan.get());
            if (physical.snapshot() != null) world.observe(physical.snapshot().members());
            if (physical.state() != LibraryStructureScanner.State.VALID || physical.snapshot() == null)
                throw new LibraryBookAccess.Failure(physical.state().name().toLowerCase(Locale.ROOT));
            return physical.snapshot().mediaShelves().stream().sorted(Comparator.comparingLong(BlockPos::asLong)).toList();
        }
    }
}
