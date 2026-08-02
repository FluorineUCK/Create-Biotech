package com.nobodiiiii.createbiotech.content.buttercat.register;

import com.nobodiiiii.createbiotech.content.buttercat.datagen.other.ModTags;
import com.nobodiiiii.createbiotech.foundation.feature.CBFeature;
import com.simibubi.create.AllFluids;
import com.tterrag.registrate.util.entry.FluidEntry;

import net.createmod.catnip.theme.Color;
import net.minecraft.core.BlockPos;
import net.minecraft.core.dispenser.BlockSource;
import net.minecraft.core.dispenser.DefaultDispenseItemBehavior;
import net.minecraft.core.dispenser.DispenseItemBehavior;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.DispensibleContainerItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.BaseFlowingFluid;
import org.joml.Vector3f;

import static com.nobodiiiii.createbiotech.content.buttercat.ButterCatModule.REGISTRATE;

public class ModFluids {
    public static final FluidEntry<BaseFlowingFluid.Flowing> CREAM =
            REGISTRATE.standardFluid("cream",(p,s,f)->new BaseFluidType(p,s,f,14147267))
                    .properties(b -> b. viscosity(100)
                            .canSwim(false)
                            .canPushEntity(false)
                    )
                    .fluidProperties(p -> p.levelDecreasePerBlock(2)
                            .tickRate(60)
                            .slopeFindDistance(2)
                            .explosionResistance(50))
                    .tag(ModTags.CREAM)
                    .source(BaseFlowingFluid.Flowing.Source::new)
                    .block()
                    .properties(p -> p.mapColor(MapColor.TERRACOTTA_WHITE))
                    .build()
                    .bucket()
                    .onRegister(ModFluids::registerFluidDispenseBehavior)
                    .build()
                    .register();



    private static final DispenseItemBehavior DEFAULT = new DefaultDispenseItemBehavior();
    private static final DispenseItemBehavior DISPENSE_FLUID = new DefaultDispenseItemBehavior(){
        @Override
        protected ItemStack execute(BlockSource pSource, ItemStack pStack) {
            if (!CBFeature.BUTTER_CAT.isEnabled())
                return pStack;
            DispensibleContainerItem dispensibleContainerItem = (DispensibleContainerItem) pStack.getItem();
            BlockPos pos = pSource.pos().relative(pSource.state().getValue(DispenserBlock.FACING));
            Level level = pSource.level();
            if (dispensibleContainerItem.emptyContents(null, level, pos, null, pStack)) {
                return new ItemStack(Items.BUCKET);
            }
            return DEFAULT.dispense(pSource, pStack);
        }
    };

    private static void registerFluidDispenseBehavior(BucketItem bucket) {
        DispenserBlock.registerBehavior(bucket, DISPENSE_FLUID);
    }
    public static void register() {}

    private static class BaseFluidType extends AllFluids.TintedFluidType {

        private static Vector3f tintColor;
        public BaseFluidType (Properties p, ResourceLocation s, ResourceLocation f,int tintColor) {
            super (p,s,f);
            BaseFluidType.tintColor = new Color(tintColor,false).asVectorF();
        }
        @Override
        protected int getTintColor(FluidStack stack) {return 0xffffffff;}

        @Override
        public int getTintColor(FluidState state, BlockAndTintGetter world, BlockPos pos) {
            return 0xffffffff;
        }

        @Override
        protected Vector3f getCustomFogColor() {
            return tintColor;
        }

        @Override
        protected float getFogDistanceModifier() {
            return 1/16f;
        }
    }

}

