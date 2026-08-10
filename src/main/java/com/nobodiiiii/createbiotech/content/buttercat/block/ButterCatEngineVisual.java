package com.nobodiiiii.createbiotech.content.buttercat.block;

import com.mojang.math.Axis;
import com.nobodiiiii.createbiotech.client.ButterCatPartials;
import com.simibubi.create.content.kinetics.base.ShaftVisual;
import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.OrientedInstance;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import net.createmod.catnip.math.AngleHelper;
import net.minecraft.core.Direction;
import org.joml.Quaternionf;

import java.util.function.Consumer;

public class ButterCatEngineVisual extends ShaftVisual<ButterCatEngineBlockEntity> implements SimpleDynamicVisual {
    private final OrientedInstance cat;
    private final OrientedInstance bread;
    private final OrientedInstance rope;
    private final OrientedInstance butter;

    private final Quaternionf blockOrientation;
    private final Axis attachmentRotationAxis;

    private PartialModel currentCatModel;
    private PartialModel currentBreadModel;
    private PartialModel currentRopeModel;
    private PartialModel currentButterModel;
    private float lastAttachmentAngle;

    public ButterCatEngineVisual(VisualizationContext context, ButterCatEngineBlockEntity blockEntity, float partialTick) {
        super(context, blockEntity, partialTick);

        Direction facing = blockState.getValue(ButterCatEngineBlock.HORIZONTAL_FACING);
        blockOrientation = Axis.YP.rotationDegrees(AngleHelper.horizontalAngle(facing));
        attachmentRotationAxis =
            Axis.of(Direction.get(Direction.AxisDirection.POSITIVE, rotationAxis()).step());

        currentCatModel = ButterCatPartials.getCatModel(blockEntity.getCatVariant());
        currentBreadModel = ButterCatPartials.getBreadModel(blockEntity.hasBread());
        currentRopeModel = ButterCatPartials.getRopeModel(blockEntity.hasBread());
        currentButterModel = ButterCatPartials.getButterModel(blockEntity.isInfinite(), blockEntity.getButterLevel());
        lastAttachmentAngle = ButterCatEngineRenderer.getAttachmentAngleForBe(blockEntity,
            blockEntity.getBlockPos(), rotationAxis(), partialTick);
        cat = createAttachmentInstance(currentCatModel);
        bread = createAttachmentInstance(currentBreadModel);
        rope = createAttachmentInstance(currentRopeModel);
        butter = createAttachmentInstance(currentButterModel);
    }

    @Override
    public void beginFrame(DynamicVisual.Context ctx) {
        updateModels();
        if (!isVisible(ctx.frustum()) || doDistanceLimitThisFrame(ctx))
            return;
        updateAttachmentRotation(ctx.partialTick());
    }

    @Override
    public void update(float pt) {
        super.update(pt);
        updateModels();
        updateAttachmentRotation(pt);
    }

    private OrientedInstance createAttachmentInstance(PartialModel model) {
        OrientedInstance instance =
            instancerProvider().instancer(InstanceTypes.ORIENTED, Models.partial(model)).createInstance();
        setupAttachmentInstance(instance);
        return instance;
    }

    private void setupAttachmentInstance(OrientedInstance instance) {
        instance.position(getVisualPosition())
            .rotation(getAttachmentRotation(lastAttachmentAngle))
            .light(computePackedLight())
            .setChanged();
    }

    private void updateAttachmentRotation(float partialTick) {
        lastAttachmentAngle = ButterCatEngineRenderer.getAttachmentAngleForBe(blockEntity,
            blockEntity.getBlockPos(), rotationAxis(), partialTick);
        Quaternionf rotation = getAttachmentRotation(lastAttachmentAngle);
        cat.rotation(rotation).setChanged();
        bread.rotation(rotation).setChanged();
        rope.rotation(rotation).setChanged();
        butter.rotation(rotation).setChanged();
    }

    private Quaternionf getAttachmentRotation(float angle) {
        return attachmentRotationAxis.rotationDegrees(angle).mul(blockOrientation);
    }

    private void updateModels() {
        PartialModel newCatModel = ButterCatPartials.getCatModel(blockEntity.getCatVariant());
        if (newCatModel != currentCatModel) {
            currentCatModel = newCatModel;
            instancerProvider().instancer(InstanceTypes.ORIENTED, Models.partial(newCatModel)).stealInstance(cat);
            setupAttachmentInstance(cat);
        }

        PartialModel newBreadModel = ButterCatPartials.getBreadModel(blockEntity.hasBread());
        if (newBreadModel != currentBreadModel) {
            currentBreadModel = newBreadModel;
            instancerProvider().instancer(InstanceTypes.ORIENTED, Models.partial(newBreadModel)).stealInstance(bread);
            setupAttachmentInstance(bread);
        }

        PartialModel newRopeModel = ButterCatPartials.getRopeModel(blockEntity.hasBread());
        if (newRopeModel != currentRopeModel) {
            currentRopeModel = newRopeModel;
            instancerProvider().instancer(InstanceTypes.ORIENTED, Models.partial(newRopeModel)).stealInstance(rope);
            setupAttachmentInstance(rope);
        }

        PartialModel newButterModel =
			ButterCatPartials.getButterModel(blockEntity.isInfinite(), blockEntity.getButterLevel());
        if (newButterModel != currentButterModel) {
            currentButterModel = newButterModel;
            instancerProvider().instancer(InstanceTypes.ORIENTED, Models.partial(newButterModel)).stealInstance(butter);
            setupAttachmentInstance(butter);
        }
    }

    @Override
    public void updateLight(float partialTick) {
        super.updateLight(partialTick);
        relight(cat);
        relight(butter);
        relight(bread);
        relight(rope);
    }
    @Override
    protected void _delete() {
        super._delete();
        cat.delete();
        bread.delete();
        rope.delete();
        butter.delete();
    }


    @Override
    public void collectCrumblingInstances(Consumer<Instance> consumer) {
        super.collectCrumblingInstances(consumer);
        consumer.accept(cat);
        consumer.accept(butter);
        consumer.accept(bread);
        consumer.accept(rope);
    }
}

