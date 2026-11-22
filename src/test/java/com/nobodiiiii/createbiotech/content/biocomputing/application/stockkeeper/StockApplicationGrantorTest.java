package com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.GameType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StockApplicationGrantorTest {
    @Test void offlineAccountRequiresAnExactKnownBuildPermittingStateAndMatchingIdentity() {
        var id=UUID.randomUUID();
        assertFalse(StockApplicationGrantor.savedMayOperate(null,id));
        assertFalse(StockApplicationGrantor.savedMayOperate(new CompoundTag(),id));
        for (var mode : GameType.values()) {
            for (var build : new boolean[]{false,true})
                assertEquals(build && !mode.isBlockPlacingRestricted(),StockApplicationGrantor.savedMayOperate(saved(id,mode,build),id));
        }
        var source=saved(id,GameType.SURVIVAL,true);
        assertFalse(StockApplicationGrantor.savedMayOperate(source,UUID.randomUUID()));
        source.putInt("playerGameType",99);
        assertFalse(StockApplicationGrantor.savedMayOperate(source,id),"Unknown mode must not fall back to Survival");
        source.putInt("playerGameType",GameType.SURVIVAL.getId());
        source.getCompound("abilities").putByte("mayBuild",(byte)2);
        assertFalse(StockApplicationGrantor.savedMayOperate(source,id),"Invalid ability is not permission");
        source.getCompound("abilities").remove("mayBuild");
        assertFalse(StockApplicationGrantor.savedMayOperate(source,id),"Missing ability is not the vanilla default");
        source.putIntArray("UUID",new int[]{1,2,3});
        assertFalse(StockApplicationGrantor.savedMayOperate(source,id));
    }
    private static CompoundTag saved(UUID player, GameType mode, boolean build) {
        var root=new CompoundTag(); root.putUUID("UUID",player); root.putInt("playerGameType",mode.getId());
        var abilities=new CompoundTag(); abilities.putBoolean("mayBuild",build); root.put("abilities",abilities);
        return root;
    }
}
