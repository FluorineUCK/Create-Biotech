package com.nobodiiiii.createbiotech.content.buttercat.block;

import net.minecraft.core.HolderLookup;

import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.nobodiiiii.createbiotech.content.buttercat.ButterCatVariants;
import com.nobodiiiii.createbiotech.content.itemapplication.ManualApplicationStateCarrier;
import com.nobodiiiii.createbiotech.foundation.advancement.CBAdvancements;
import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;
import com.nobodiiiii.createbiotech.registry.CBBlocks;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import com.nobodiiiii.createbiotech.registry.CBBlockEntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.animal.CatVariant;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;

import static com.simibubi.create.content.kinetics.base.HorizontalKineticBlock.HORIZONTAL_FACING;


public class  ButterCatEngineBlockEntity  extends GeneratingKineticBlockEntity
    implements ManualApplicationStateCarrier {
    private static final int OVERFLOW_BUTTER_COUNT = 1;

    protected ResourceKey<CatVariant> catVariant = ButterCatVariants.DEFAULT;
    protected boolean infinite =false;
    protected int butterCount = 0;
    protected int overflowCount = 0;
    protected int cd = 0;
    protected float clientAttachmentRotationOffset;
    protected boolean clientAttachmentRotationOffsetInitialized;

    public ButterCatEngineBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public ButterCatEngineBlockEntity(BlockPos pos, BlockState state) {
        this(CBBlockEntityTypes.BUTTER_CAT_ENGINE.get(), pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        super.addBehaviours(behaviours);
    }
    ///================getter/setter================
    public void addButterCount(int count) {
        if (count == 0 || infinite) return;
        int total = Math.max(0, getTotalCount() + count);
        int maxStoredButterCount = getMaxStoredButterCount();
        if (total > maxStoredButterCount)
            total = maxStoredButterCount;
        butterCount = Math.min(total, getMaxButterCount());
        overflowCount = total - butterCount;
        updateGeneratedRotation();
        awardButterCatAdvancement();
    }
    public int getButterCount() {
        return butterCount;
    }
    public int getTotalCount() {
        return butterCount + overflowCount;
    }

    public ResourceKey<CatVariant> getCatVariant() {
        return catVariant;
    }

    public void setCatVariant(ResourceKey<CatVariant> variant) {
        if (variant == null || catVariant.equals(variant))
            return;
        catVariant = variant;
        notifyUpdate();
    }

    // The cat survives its block being swapped for another butter cat block, such as a cute cat
    // on a shaft becoming an engine once bread is applied to it.
    @Override
    public void restoreManualApplicationState(CompoundTag previousData) {
        if (previousData != null)
            setCatVariant(ButterCatVariants.read(previousData));
    }

    public boolean hasBread(){
        return getBlockState() != null && getBlockState().is(CBBlocks.BUTTER_CAT_ENGINE.get());
    }

    public void setInfinite(boolean bool) {
        infinite = bool;
        overflowCount = 0;
        if(bool)
            butterCount = getMaxButterCount();
        else
            butterCount = 0;
        updateGeneratedRotation();
        if (bool)
            awardButterCatAdvancement();
    }
    public boolean isInfinite(){
        return infinite;
    }
    public boolean isFull() {
        return isInfinite() || getTotalCount() >= getMaxStoredButterCount();
    }

    public boolean canAcceptButter(int count) {
        return !isInfinite() && count > 0 && getTotalCount() + count <= getMaxStoredButterCount();
    }

    public int getCd(boolean remaining) {
        return remaining ? Math.max(0, getButterDecayTicks() - cd) : cd;
    }

    public void tick(){
        super.tick();
        if(isInfinite())return;
        if(butterCount > 0){
            cd++;
        }
        if(cd >= getButterDecayTicks() ){
            if(butterCount > 0) butterCount --;
            if(overflowCount > 0){
                butterCount ++;
                overflowCount--;
            }
            cd = 0;
            updateGeneratedRotation();
        }
    }
    ///================ speed ================
    //getSpeed() 返回最终速度
    //应力生产速度，黄油输入16个后达到最大速度，超出部分继续累加在应力系数上
    @Override
    public float getGeneratedSpeed() {
        float maxGeneratedRpm = (float) CBConfigs.SERVER.butterCat.maxGeneratedRpm.get().doubleValue();
        if (isInfinite()) return getDirectionalGeneratedSpeed(maxGeneratedRpm);
        int butterForMaxRpm = Math.max(1, CBConfigs.SERVER.butterCat.butterForMaxRpm.get());
        float speed = butterCount <= butterForMaxRpm
            ? butterCount * (float) CBConfigs.SERVER.butterCat.rpmPerButter.get().doubleValue()
            : maxGeneratedRpm;
        speed = Math.min(speed, maxGeneratedRpm);
        return getDirectionalGeneratedSpeed(speed);
    }
    //应力系数
    @Override
    public float calculateAddedStressCapacity() {
        float speed = Math.abs(getGeneratedSpeed());
        float capacity = 0;
        if (speed > 0) {
            float maxStressCapacity = getMaxStressCapacity();
            float stressPerRpm = getStressCapacityPerRpm();
            capacity = Math.min(maxStressCapacity, stressPerRpm * speed) / speed;
        }
        this.lastCapacityProvided = capacity;
        return capacity;
    }
    protected float getDirectionalGeneratedSpeed(float speed) {
        return convertToDirection(speed, getBlockState().getValue(HORIZONTAL_FACING));
    }

    private void awardButterCatAdvancement() {
        if (level == null || level.isClientSide)
            return;
        Vec3 worldPosition = SubLevelCompat.toWorld(level, getBlockPos(), Vec3.atCenterOf(getBlockPos()));
        CBAdvancements.awardNearby(level, worldPosition, 16, CBAdvancements.BUTTER_CAT);
    }

    float getAttachmentRotationOffset() {
        return clientAttachmentRotationOffset;
    }

    ///================serialize================
    @Override
    protected void write(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(compound, registries, clientPacket);

        compound.putBoolean("infinite",infinite);
        compound.putInt("cd",cd);
        compound.putInt("butterCount",butterCount);
        compound.putInt("overflowCount",overflowCount);

        ButterCatVariants.write(compound, catVariant);
    }
    @Override
    protected void read(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        float previousSpeed = getSpeed();
        super.read(compound, registries, clientPacket);

        if(compound.contains("infinite")) infinite = compound.getBoolean("infinite");
        if(compound.contains("cd")) cd = compound.getInt("cd");
        if(compound.contains("butterCount")) butterCount = compound.getInt("butterCount");
        if(compound.contains("overflowCount")) overflowCount = compound.getInt("overflowCount");

        ResourceKey<CatVariant> storedVariant = ButterCatVariants.read(compound);
        if (storedVariant != null)
            catVariant = storedVariant;

        normalizeStoredButter();

        if (level != null && level.isClientSide)
            ButterCatEngineClientRotation.sync(this, previousSpeed, clientPacket);

    }
    ///================render state================
    public int getButterLevel(){
        if(butterCount==0) return 0;
        else if(butterCount>8 && butterCount<=16) return 2;
        else if(butterCount>16) return 3;
        return 1;
    }
    public int getMaxButterCount(){
        return CBConfigs.SERVER.butterCat.maxButterCount.get();
    }
    public int getMaxStoredButterCount() {
        return getMaxButterCount() + OVERFLOW_BUTTER_COUNT;
    }
    private int getButterDecayTicks() {
        return CBConfigs.SERVER.butterCat.butterDecayTicks.get();
    }
    public float getStressCapacityPerRpm() {
        return CBConfigs.SERVER.butterCat.stressCapacityPerRpm.get().floatValue();
    }
    public float getMaxStressCapacity() {
        return CBConfigs.SERVER.butterCat.maxStressCapacity.get().floatValue();
    }

    private void normalizeStoredButter() {
        if (infinite) {
            butterCount = getMaxButterCount();
            overflowCount = 0;
            return;
        }

        int total = Math.max(0, butterCount) + Math.max(0, overflowCount);
        int maxStoredButterCount = getMaxStoredButterCount();
        if (total > maxStoredButterCount)
            total = maxStoredButterCount;
        butterCount = Math.min(total, getMaxButterCount());
        overflowCount = total - butterCount;
    }
}

