package com.nobodiiiii.createbiotech.content.biocomputing.device.settings;

import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.registry.CBMenuTypes;
import io.netty.buffer.Unpooled;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;

/** Uses the same menu opening, nonce and authorization request as the real client. */
public final class MotionPanelSettingsFixture extends FakePlayer {
    private ComponentSettingsMenu shown;
    private int id;

    public MotionPanelSettingsFixture(ServerLevel world) {
        super(world, new GameProfile(UUID.randomUUID(), "winch-configurer"));
    }

    public void grant(GameTestHelper h, BlockPos pos) {
        var center = Vec3.atCenterOf(pos);
        moveTo(center);
        h.assertValueEqual(level().getBlockState(pos)
            .useWithoutItem(level(), this, new BlockHitResult(center, Direction.UP, pos, false)),
            InteractionResult.CONSUME, "Open the actual component menu");
        h.assertValueEqual(shown.code(), "ok", "Open the component discovery at " + pos.toShortString());
        h.assertTrue(shown.view().field("choices") instanceof com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue.ListValue choices
            && !choices.values().isEmpty(), "A real serial source is selectable at " + pos.toShortString());
        var wire = new RegistryFriendlyByteBuf(Unpooled.buffer(), registryAccess());
        try {
            shown.request(ComponentSettingsRequest.Action.GRANT, 0).write(wire);
            new ComponentSettingsRequest(wire).handle(this);
        } finally { wire.release(); }
        h.assertValueEqual(shown.code(), "configured", "Authorized actual line to the component");
    }

    @Override public OptionalInt openMenu(MenuProvider provider, Consumer<RegistryFriendlyByteBuf> opening) {
        int next = ++id;
        containerMenu = provider.createMenu(next, getInventory(), this);
        var wire = new RegistryFriendlyByteBuf(Unpooled.buffer(), registryAccess());
        try {
            opening.accept(wire);
            shown = CBMenuTypes.COMPONENT_SETTINGS.get().create(next, getInventory(), wire);
        } finally { wire.release(); }
        return OptionalInt.of(next);
    }
}
