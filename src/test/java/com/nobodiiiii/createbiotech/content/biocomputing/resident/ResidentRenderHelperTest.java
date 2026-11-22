package com.nobodiiiii.createbiotech.content.biocomputing.resident;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerRenderer;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryRenderer;
import com.nobodiiiii.createbiotech.content.evokerenchantingchamber.EvokerEnchantingVisual;
import com.nobodiiiii.createbiotech.foundation.render.SeatedModelPose;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.IllagerModel;
import net.minecraft.client.model.VillagerModel;
import net.minecraft.client.model.ZombieVillagerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.AbstractIllager;
import net.minecraft.world.entity.monster.ZombieVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.phys.AABB;

class ResidentRenderHelperTest {
	private static final List<String> RESTRICTOR_HEAD_FACES =
		List.of("north", "east", "south", "west", "up", "down");

	@BeforeAll
	static void bootstrap() {
		installEmptyLoadingModList();
		net.minecraft.SharedConstants.tryDetectVersion();
		try {
			Field bootstrapped = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
			bootstrapped.setAccessible(true);
			bootstrapped.setBoolean(null, true);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	@Test
	void sharedSeatedPoseChangesOnlyLegRotationsOnTheActualVillagerModel() {
		ModelPart root = LayerDefinition.create(VillagerModel.createBodyModel(), 64, 64).bakeRoot();
		ModelPart head = root.getChild("head");
		ModelPart arms = root.getChild("arms");
		ModelPart rightLeg = root.getChild("right_leg");
		ModelPart leftLeg = root.getChild("left_leg");
		head.setRotation(0.11f, 0.12f, 0.13f);
		arms.setRotation(0.21f, 0.22f, 0.23f);
		float headX = head.x;
		float headY = head.y;
		float headZ = head.z;
		float armsX = arms.x;
		float armsY = arms.y;
		float armsZ = arms.z;
		float rightLegX = rightLeg.x;
		float rightLegY = rightLeg.y;
		float rightLegZ = rightLeg.z;
		float leftLegX = leftLeg.x;
		float leftLegY = leftLeg.y;
		float leftLegZ = leftLeg.z;
		rightLeg.setRotation(0.9f, 0.8f, 0.7f);
		leftLeg.setRotation(0.6f, 0.5f, 0.4f);

		SeatedModelPose.apply(root);

		assertEquals(-1.4137167f, rightLeg.xRot, 0.000001f);
		assertEquals(0.31415927f, rightLeg.yRot, 0.000001f);
		assertEquals(0.07853982f, rightLeg.zRot, 0.000001f);
		assertEquals(-1.4137167f, leftLeg.xRot, 0.000001f);
		assertEquals(-0.31415927f, leftLeg.yRot, 0.000001f);
		assertEquals(-0.07853982f, leftLeg.zRot, 0.000001f);
		assertPartState(head, 0.11f, 0.12f, 0.13f, headX, headY, headZ);
		assertPartState(arms, 0.21f, 0.22f, 0.23f, armsX, armsY, armsZ);
		assertPivot(rightLeg, rightLegX, rightLegY, rightLegZ);
		assertPivot(leftLeg, leftLegX, leftLegY, leftLegZ);
	}

	@Test
	void sharedSeatedPoseChangesOnlyLegRotationsOnTheActualIllagerModel() {
		ModelPart root = IllagerModel.createBodyLayer().bakeRoot();
		ModelPart head = root.getChild("head");
		ModelPart rightArm = root.getChild("right_arm");
		ModelPart leftArm = root.getChild("left_arm");
		ModelPart rightLeg = root.getChild("right_leg");
		ModelPart leftLeg = root.getChild("left_leg");
		head.setRotation(0.11f, 0.12f, 0.13f);
		rightArm.setRotation(0.21f, 0.22f, 0.23f);
		leftArm.setRotation(0.31f, 0.32f, 0.33f);
		float[] pivots = {head.x, head.y, head.z, rightArm.x, rightArm.y, rightArm.z,
			leftArm.x, leftArm.y, leftArm.z, rightLeg.x, rightLeg.y, rightLeg.z,
			leftLeg.x, leftLeg.y, leftLeg.z};

		SeatedModelPose.apply(root);

		assertEquals(-1.4137167f, rightLeg.xRot, 0.000001f);
		assertEquals(0.31415927f, rightLeg.yRot, 0.000001f);
		assertEquals(0.07853982f, rightLeg.zRot, 0.000001f);
		assertEquals(-1.4137167f, leftLeg.xRot, 0.000001f);
		assertEquals(-0.31415927f, leftLeg.yRot, 0.000001f);
		assertEquals(-0.07853982f, leftLeg.zRot, 0.000001f);
		assertPartState(head, 0.11f, 0.12f, 0.13f, pivots[0], pivots[1], pivots[2]);
		assertPartState(rightArm, 0.21f, 0.22f, 0.23f, pivots[3], pivots[4], pivots[5]);
		assertPartState(leftArm, 0.31f, 0.32f, 0.33f, pivots[6], pivots[7], pivots[8]);
		assertPivot(rightLeg, pivots[9], pivots[10], pivots[11]);
		assertPivot(leftLeg, pivots[12], pivots[13], pivots[14]);
	}

	@Test
	void evokerPreparationAppliesTheSharedSeatedLegPoseAfterAnimationSetup() {
		ModelPart root = IllagerModel.createBodyLayer().bakeRoot();
		IllagerModel<InertRenderEvoker> model = new IllagerModel<>(root);
		InertRenderEvoker evoker = allocate(InertRenderEvoker.class);

		EvokerEnchantingVisual.prepareModel(model, evoker, 0.0f, false);

		ModelPart rightLeg = root.getChild("right_leg");
		ModelPart leftLeg = root.getChild("left_leg");
		assertEquals(-1.4137167f, rightLeg.xRot, 0.000001f);
		assertEquals(0.31415927f, rightLeg.yRot, 0.000001f);
		assertEquals(0.07853982f, rightLeg.zRot, 0.000001f);
		assertEquals(-1.4137167f, leftLeg.xRot, 0.000001f);
		assertEquals(-0.31415927f, leftLeg.yRot, 0.000001f);
		assertEquals(-0.07853982f, leftLeg.zRot, 0.000001f);
	}

	@Test
	void preparesActualZombieVillagerModelWithChestCrossingArmsAfterSetupAnim() {
		ModelPart root = ZombieVillagerModel.createBodyLayer().bakeRoot();
		ZombieVillagerModel<ZombieVillager> model = new ZombieVillagerModel<>(root);
		ModelPart rightArm = root.getChild("right_arm");
		ModelPart leftArm = root.getChild("left_arm");
		ModelPart rightLeg = root.getChild("right_leg");
		ModelPart leftLeg = root.getChild("left_leg");

		prepareSeatedModel(model, root, allocate(InertZombieVillager.class), 0.0f);
		assertEquals(-1.4137167f, rightLeg.xRot, 0.000001f);
		assertEquals(0.31415927f, rightLeg.yRot, 0.000001f);
		assertEquals(0.07853982f, rightLeg.zRot, 0.000001f);
		assertEquals(-1.4137167f, leftLeg.xRot, 0.000001f);
		assertEquals(-0.31415927f, leftLeg.yRot, 0.000001f);
		assertEquals(-0.07853982f, leftLeg.zRot, 0.000001f);

		ArmEndpoint rightHand = handEndpoint(rightArm);
		ArmEndpoint leftHand = handEndpoint(leftArm);

		assertTrue(rightHand.x() > 0.5f,
			() -> "right hand must cross the center line, got " + rightHand);
		assertTrue(leftHand.x() < -0.5f,
			() -> "left hand must cross the center line, got " + leftHand);
		assertTrue(rightHand.y() > 5.0f && rightHand.y() < 8.0f,
			() -> "right hand must remain at chest height, got " + rightHand);
		assertTrue(leftHand.y() > 5.0f && leftHand.y() < 8.0f,
			() -> "left hand must remain at chest height, got " + leftHand);
		assertTrue(rightHand.z() < -3.0f,
			() -> "right hand must lie in front of the body, got " + rightHand);
		assertTrue(leftHand.z() < -3.0f,
			() -> "left hand must lie in front of the body, got " + leftHand);
		assertEquals(rightHand.y(), leftHand.y(), 0.000001f);
		assertEquals(rightHand.z(), leftHand.z(), 0.000001f);
	}

	@Test
	void selectsAWhitelistedDisplayAdapterForEachSupportedResidentType() {
		assertEquals(Villager.class, ResidentRenderHelper.displayEntityClassFor(EntityType.VILLAGER));
		assertEquals(WanderingTrader.class,
			ResidentRenderHelper.displayEntityClassFor(EntityType.WANDERING_TRADER));
		assertEquals(ZombieVillager.class,
			ResidentRenderHelper.displayEntityClassFor(EntityType.ZOMBIE_VILLAGER));
		assertThrows(IllegalArgumentException.class,
			() -> ResidentRenderHelper.displayEntityClassFor(EntityType.COW));
	}

	@Test
	void selectsVanillaProfessionLayersForVillagerFamiliesOnly() {
		assertTrue(ResidentRenderHelper.hasVillagerProfessionLayerFor(EntityType.VILLAGER));
		assertTrue(ResidentRenderHelper.hasVillagerProfessionLayerFor(EntityType.ZOMBIE_VILLAGER));
		assertFalse(ResidentRenderHelper.hasVillagerProfessionLayerFor(EntityType.WANDERING_TRADER));
	}

	@Test
	void keepsWorldBoundDisplayCachesWeak() throws NoSuchFieldException {
		assertEquals(WeakReference.class,
			ResidentRenderHelper.class.getDeclaredField("displayLevel").getType());
		assertEquals(WeakReference.class,
			ResidentRenderHelper.class.getDeclaredField("displayVillager").getType());
		assertEquals(WeakReference.class,
			ResidentRenderHelper.class.getDeclaredField("displayWanderingTrader").getType());
		assertEquals(WeakReference.class,
			ResidentRenderHelper.class.getDeclaredField("displayZombieVillager").getType());
	}

	@Test
	void keepsSeatAndScaleLayoutsIndependentForEachBlockRenderer() {
		ResidentRenderHelper.ResidentRenderLayout computer =
			new ResidentRenderHelper.ResidentRenderLayout(0.21d, 0.61f);
		ResidentRenderHelper.ResidentRenderLayout patternCore =
			new ResidentRenderHelper.ResidentRenderLayout(0.47d, 0.83f);

		assertEquals(0.21d, computer.seatY());
		assertEquals(0.61f, computer.scale());
		assertEquals(0.47d, patternCore.seatY());
		assertEquals(0.83f, patternCore.scale());
		assertNotSame(ComputerRenderer.RESIDENT_LAYOUT, LibraryRenderer.RESIDENT_LAYOUT);
		assertEquals(-0.125d, ComputerRenderer.RESIDENT_LAYOUT.seatY());
		assertEquals(1.0f, ComputerRenderer.RESIDENT_LAYOUT.scale());
		assertEquals("STANDING", poseName(ComputerRenderer.RESIDENT_LAYOUT));
		assertEquals(2 / 16d, LibraryRenderer.RESIDENT_LAYOUT.seatY());
		assertEquals(0.72f, LibraryRenderer.RESIDENT_LAYOUT.scale());
		assertEquals("STANDING", poseName(LibraryRenderer.RESIDENT_LAYOUT));
	}

	@Test
	void computerStandingPreparationUsesNormalLegAnimationInsteadOfSeatedOverride() {
		ModelPart root = ZombieVillagerModel.createBodyLayer().bakeRoot();
		ZombieVillagerModel<ZombieVillager> model = new ZombieVillagerModel<>(root);
		ModelPart rightLeg = root.getChild("right_leg");
		ModelPart leftLeg = root.getChild("left_leg");

		assertTrue(prepareModelForPose(model, root, allocate(InertZombieVillager.class), "STANDING"),
			"standing layouts must reset/setup normal animation without a seated leg override");
		assertEquals(0.0f, rightLeg.xRot, 0.000001f);
		assertEquals(0.0f, leftLeg.xRot, 0.000001f);
	}

	@Test
	void productionResidentMatrixMapsANonSymmetricForwardVectorToAllFourFacings() {
		for (Direction facing : Direction.Plane.HORIZONTAL) {
			VertexCapture capture = new VertexCapture();
			PoseStack transform = residentModelTransform(facing);
			addProductionTransformedVertex(capture, transform, 0.0f, 0.0f, 0.0f);
			addProductionTransformedVertex(capture, transform, 0.25f, 0.0f, -0.75f);
			Vertex origin = capture.vertices.getFirst();
			Vertex endpoint = capture.vertices.getLast();
			Vertex transformedForward = new Vertex(endpoint.x() - origin.x(), 0.0f,
				endpoint.z() - origin.z());
			assertEquals(facing, Direction.getNearest(transformedForward.x(), 0.0f,
				transformedForward.z()), () -> facing + " used the wrong resident matrix direction");
		}
	}

	@Test
	void computerStandingAdultHeadStrictlyOverlapsTheRestrictorHeadForEveryHorizontalFacing()
		throws Exception {
		assertEquals("STANDING", poseName(ComputerRenderer.RESIDENT_LAYOUT));
		assertEquals(-0.125d, ComputerRenderer.RESIDENT_LAYOUT.seatY());
		assertEquals(1.0f, ComputerRenderer.RESIDENT_LAYOUT.scale());

		AABB restrictorHead = restrictorHeadElement();
		for (Direction facing : Direction.Plane.HORIZONTAL) {
			AABB head = bakedVillagerHeadEnvelope(residentModelTransform(facing));
			assertStrictPositiveOverlap(head, restrictorHead, facing);
			assertTrue(head.maxY <= 2.0d,
				() -> facing + " must keep the baked villager head inside the two-cell Computer envelope");
		}
	}

	@Test
	void restrictorHeadSelectorRejectsAnEmptyFaceSet() {
		assertThrows(AssertionError.class, () -> restrictorHeadElement(candidateElements(
			restrictorHeadCandidate(3.0d, 7.0d, 3.0d, 13.0d, 13.0d, 13.0d))));
	}

	@Test
	void restrictorHeadSelectorRejectsASubsetOfFaces() {
		assertThrows(AssertionError.class, () -> restrictorHeadElement(candidateElements(
			restrictorHeadCandidate(3.0d, 7.0d, 3.0d, 13.0d, 13.0d, 13.0d, "north"))));
	}

	@Test
	void restrictorHeadSelectorRejectsDuplicateCentralCandidates() {
		assertThrows(AssertionError.class, () -> restrictorHeadElement(candidateElements(
			completeRestrictorHeadCandidate(3.0d, 7.0d, 3.0d, 13.0d, 13.0d, 13.0d),
			completeRestrictorHeadCandidate(3.0d, 7.0d, 3.0d, 13.0d, 13.0d, 13.0d))));
	}

	@Test
	void restrictorHeadSelectorRejectsANonCentralCandidate() {
		assertThrows(AssertionError.class, () -> restrictorHeadElement(candidateElements(
			completeRestrictorHeadCandidate(0.0d, 7.0d, 0.0d, 2.0d, 13.0d, 2.0d))));
	}

	private static void installEmptyLoadingModList() {
		try {
			Class<?> type = Class.forName("net.neoforged.fml.loading.LoadingModList");
			if (type.getMethod("get").invoke(null) != null) return;
			Object empty = type.getMethod("of", List.class, List.class, List.class, List.class,
				java.util.Map.class).invoke(null, List.of(), List.of(), List.of(), List.of(), java.util.Map.of());
			if (empty == null) throw new IllegalStateException("LoadingModList.of returned null");
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static void assertPartState(ModelPart part, float xRot, float yRot, float zRot,
		float x, float y, float z) {
		assertEquals(xRot, part.xRot, 0.000001f);
		assertEquals(yRot, part.yRot, 0.000001f);
		assertEquals(zRot, part.zRot, 0.000001f);
		assertPivot(part, x, y, z);
	}

	private static void assertPivot(ModelPart part, float x, float y, float z) {
		assertEquals(x, part.x, 0.000001f);
		assertEquals(y, part.y, 0.000001f);
		assertEquals(z, part.z, 0.000001f);
	}

	private static void prepareSeatedModel(ZombieVillagerModel<ZombieVillager> model, ModelPart root,
		ZombieVillager zombieVillager, float ageInTicks) {
		try {
			Method method = ResidentRenderHelper.class.getDeclaredMethod("prepareSeatedModel",
				EntityModel.class, ModelPart.class, LivingEntity.class, float.class);
			method.setAccessible(true);
			method.invoke(null, model, root, zombieVillager, ageInTicks);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static String poseName(ResidentRenderHelper.ResidentRenderLayout layout) {
		try {
			Object pose = layout.getClass().getMethod("pose").invoke(layout);
			return pose == null ? null : pose.toString();
		} catch (ReflectiveOperationException missingPose) {
			return null;
		}
	}

	private static boolean prepareModelForPose(ZombieVillagerModel<ZombieVillager> model,
		ModelPart root, ZombieVillager zombieVillager, String poseName) {
		try {
			Class<?> pose = Class.forName(ResidentRenderHelper.class.getName() + "$ResidentPose");
			Object value = pose.getField(poseName).get(null);
			Method method = ResidentRenderHelper.class.getDeclaredMethod("prepareModel",
				EntityModel.class, ModelPart.class, LivingEntity.class, float.class, pose);
			method.setAccessible(true);
			method.invoke(null, model, root, zombieVillager, 0.0f, value);
			return true;
		} catch (ReflectiveOperationException missingLayoutPosePreparation) {
			return false;
		}
	}

	private static PoseStack residentModelTransform(Direction facing) {
		PoseStack poseStack = new PoseStack();
		ResidentRenderHelper.assembleResidentModelTransform(poseStack, facing,
			ComputerRenderer.RESIDENT_LAYOUT, EntityType.VILLAGER);
		return poseStack;
	}

	private static void addProductionTransformedVertex(VertexCapture capture, PoseStack poseStack,
		float x, float y, float z) {
		try {
			Method method = VertexConsumer.class.getMethod("addVertex", PoseStack.Pose.class,
				float.class, float.class, float.class);
			method.invoke(capture, poseStack.last(), x, y, z);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("test must capture the production Matrix4f-transformed vertex",
				exception);
		}
	}

	private static AABB bakedVillagerHeadEnvelope(PoseStack poseStack) {
		VillagerModel<Villager> model = new VillagerModel<>(
			LayerDefinition.create(VillagerModel.createBodyModel(), 64, 64).bakeRoot());
		ModelPart head = model.root().getChild("head");
		VertexCapture capture = new VertexCapture();
		head.render(poseStack, capture, 0, 0, 0xFFFFFFFF);
		assertFalse(capture.vertices.isEmpty(), "baked villager head must emit vertices");
		double minX = capture.vertices.stream().mapToDouble(Vertex::x).min().orElseThrow();
		double minY = capture.vertices.stream().mapToDouble(Vertex::y).min().orElseThrow();
		double minZ = capture.vertices.stream().mapToDouble(Vertex::z).min().orElseThrow();
		double maxX = capture.vertices.stream().mapToDouble(Vertex::x).max().orElseThrow();
		double maxY = capture.vertices.stream().mapToDouble(Vertex::y).max().orElseThrow();
		double maxZ = capture.vertices.stream().mapToDouble(Vertex::z).max().orElseThrow();
		return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
	}

	private static AABB restrictorHeadElement() throws Exception {
		Path model = Path.of("art/biocomputing/pack/assets/create_biotech/models/block/biocomputing/computer/upper.json");
		JsonArray elements = JsonParser.parseString(Files.readString(model)).getAsJsonObject()
			.getAsJsonArray("elements");
		return restrictorHeadElement(elements);
	}

	private static AABB restrictorHeadElement(JsonArray elements) {
		List<AABB> candidates = new ArrayList<>();
		for (var element : elements) {
			JsonObject object = element.getAsJsonObject();
			if (!hasExpectedRestrictorHeadFaces(object)) continue;
			AABB candidate = blockSpaceBounds(object);
			if (containsBlockCenter(candidate)) candidates.add(candidate);
		}
		assertEquals(1, candidates.size(),
			"computer_upper.json must contain exactly one central six-face #8 restrictor_head element");
		return candidates.getFirst();
	}

	private static boolean hasExpectedRestrictorHeadFaces(JsonObject element) {
		JsonObject faces = element.getAsJsonObject("faces");
		return faces != null
			&& faces.size() == RESTRICTOR_HEAD_FACES.size()
			&& RESTRICTOR_HEAD_FACES.stream().allMatch(faces::has)
			&& faces.entrySet().stream().allMatch(face -> {
				if (!face.getValue().isJsonObject()) return false;
				JsonObject definition = face.getValue().getAsJsonObject();
				return definition.has("texture") && definition.get("texture").isJsonPrimitive()
					&& "#8".equals(definition.get("texture").getAsString());
			});
	}

	private static boolean containsBlockCenter(AABB bounds) {
		return bounds.minX <= 0.5d && bounds.maxX >= 0.5d
			&& bounds.minZ <= 0.5d && bounds.maxZ >= 0.5d;
	}

	private static JsonArray candidateElements(JsonObject... elements) {
		JsonArray candidates = new JsonArray();
		for (JsonObject element : elements) candidates.add(element);
		return candidates;
	}

	private static JsonObject completeRestrictorHeadCandidate(double minX, double minY, double minZ,
		double maxX, double maxY, double maxZ) {
		return restrictorHeadCandidate(minX, minY, minZ, maxX, maxY, maxZ,
			RESTRICTOR_HEAD_FACES.toArray(String[]::new));
	}

	private static JsonObject restrictorHeadCandidate(double minX, double minY, double minZ,
		double maxX, double maxY, double maxZ, String... faceNames) {
		JsonObject element = new JsonObject();
		element.add("from", coordinates(minX, minY, minZ));
		element.add("to", coordinates(maxX, maxY, maxZ));
		JsonObject faces = new JsonObject();
		for (String faceName : faceNames) {
			JsonObject face = new JsonObject();
			face.addProperty("texture", "#8");
			faces.add(faceName, face);
		}
		element.add("faces", faces);
		return element;
	}

	private static JsonArray coordinates(double x, double y, double z) {
		JsonArray coordinates = new JsonArray();
		coordinates.add(x);
		coordinates.add(y);
		coordinates.add(z);
		return coordinates;
	}

	private static AABB blockSpaceBounds(JsonObject element) {
		JsonArray from = element.getAsJsonArray("from");
		JsonArray to = element.getAsJsonArray("to");
		return new AABB(from.get(0).getAsDouble() / 16.0d,
			1.0d + from.get(1).getAsDouble() / 16.0d,
			from.get(2).getAsDouble() / 16.0d,
			to.get(0).getAsDouble() / 16.0d,
			1.0d + to.get(1).getAsDouble() / 16.0d,
			to.get(2).getAsDouble() / 16.0d);
	}

	private static void assertStrictPositiveOverlap(AABB head, AABB restrictorHead, Direction facing) {
		assertTrue(Math.min(head.maxX, restrictorHead.maxX) > Math.max(head.minX, restrictorHead.minX),
			() -> facing + " must strictly overlap the #8 restrictor_head on X");
		assertTrue(Math.min(head.maxY, restrictorHead.maxY) > Math.max(head.minY, restrictorHead.minY),
			() -> facing + " must strictly overlap the #8 restrictor_head on Y");
		assertTrue(Math.min(head.maxZ, restrictorHead.maxZ) > Math.max(head.minZ, restrictorHead.minZ),
			() -> facing + " must strictly overlap the #8 restrictor_head on Z");
	}

	private static final class VertexCapture implements VertexConsumer {
		private final List<Vertex> vertices = new ArrayList<>();
        private final List<String> uvs = new ArrayList<>();

		@Override public VertexConsumer addVertex(float x, float y, float z) {
			vertices.add(new Vertex(x, y, z));
			return this;
		}
		@Override public VertexConsumer setColor(int red, int green, int blue, int alpha) {
			return this;
		}
		@Override public VertexConsumer setUv(float u, float v) { uvs.add(u+","+v); return this; }
		@Override public VertexConsumer setUv1(int u, int v) { return this; }
		@Override public VertexConsumer setUv2(int u, int v) { return this; }
		@Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
	}

    @Test
    void librarianStandsOnBaseWithoutIntersectingPostOrSlopedDesk() throws Exception {
        var model = new VillagerModel<Villager>(LayerDefinition.create(VillagerModel.createBodyModel(),64,64).bakeRoot());
        var desk=JsonParser.parseString(Files.readString(Path.of("art/biocomputing/pack/assets/create_biotech/models/block/pattern_storage_core_lower.json")))
            .getAsJsonObject().getAsJsonArray("elements");
        for (Direction side : Direction.Plane.HORIZONTAL) {
            var pose=new PoseStack();
            var look=LibraryRenderer.positionReader(pose,side);
            ResidentRenderHelper.assembleResidentModelTransform(pose,look,LibraryRenderer.RESIDENT_LAYOUT,EntityType.VILLAGER);
            var mesh=new VertexCapture();
            model.root().render(pose,mesh,0,0,-1);
            assertFalse(mesh.vertices.isEmpty());
            assertEquals(0,mesh.vertices.size()%24);
            if(side==Direction.NORTH) {
                var proof=Path.of("build/library-model-proof/reader.csv"); Files.createDirectories(proof.getParent());
                Files.write(proof,java.util.stream.IntStream.range(0,mesh.vertices.size()).mapToObj(i->{
                    var v=mesh.vertices.get(i);return v.x()+","+v.y()+","+v.z()+","+mesh.uvs.get(i);
                }).toList());
            }
            assertEquals(2/16d,mesh.vertices.stream().mapToDouble(Vertex::y).min().orElseThrow(),.002,"feet meet the base");
            assertTrue(mesh.vertices.stream().allMatch(v->v.y()<2),"resident stays below the ceiling");
            assertTrue(mesh.vertices.stream().allMatch(v->v.x()>=0 && v.x()<=1 && v.z()>=0 && v.z()<=1),
                "the librarian including hat rim must fit inside the workstation");
            for (var elem : desk) {
                var e=elem.getAsJsonObject();var min=e.getAsJsonArray("from");var max=e.getAsJsonArray("to");
                for(int i=0;i<mesh.vertices.size();i+=24){
                    double[] low={100,100,100},high={-100,-100,-100};
                    double worldMaxZ=-100,deskMinZ=min.get(2).getAsDouble();
                    for(var v:mesh.vertices.subList(i,i+24)){
                        double x=8+16*((v.x()-.5)*-side.getStepZ()+(v.z()-.5)*side.getStepX());
                        double y=16*v.y(),z=8-16*((v.x()-.5)*side.getStepX()+(v.z()-.5)*side.getStepZ());
                        worldMaxZ=Math.max(worldMaxZ,z);
                        if(e.has("rotation")){
                            var rot=e.getAsJsonObject("rotation"); assertEquals("x",rot.get("axis").getAsString());
                            var o=rot.getAsJsonArray("origin");double oy=o.get(1).getAsDouble(),oz=o.get(2).getAsDouble();
                            double a=-Math.toRadians(rot.get("angle").getAsDouble()),dy=y-oy,dz=z-oz;
                            double sin=Math.sin(-a); // forward rotation of the extreme desk corner
                            deskMinZ=oz+((sin<0?max:min).get(1).getAsDouble()-oy)*sin+(min.get(2).getAsDouble()-oz)*Math.cos(a);
                            y=oy+dy*Math.cos(a)-dz*Math.sin(a); z=oz+dy*Math.sin(a)+dz*Math.cos(a);
                        }
                        double[] pt={x,y,z};
                        for(int j=0;j<3;j++){low[j]=Math.min(low[j],pt[j]);high[j]=Math.max(high[j],pt[j]);}
                    }
                    boolean separate=worldMaxZ<=deskMinZ+.001;
                    for(int j=0;j<3;j++)separate |= high[j]<=min.get(j).getAsDouble()+.001 || low[j]>=max.get(j).getAsDouble()-.001;
                    assertTrue(separate,side+" resident cube "+i/24+" overlaps lectern "+min);
                }
            }
        }
    }

    private record Vertex(float x, float y, float z) {}

	/** Uses the same Z-Y-X limb transform ModelPart applies after translating to its shoulder pivot. */
	private static ArmEndpoint handEndpoint(ModelPart arm) {
		float endpointY = 10.0f * (float) Math.cos(arm.xRot);
		float endpointZ = 10.0f * (float) Math.sin(arm.xRot);
		float endpointX = (float) Math.sin(arm.yRot) * endpointZ;
		endpointZ *= (float) Math.cos(arm.yRot);
		float x = (float) Math.cos(arm.zRot) * endpointX - (float) Math.sin(arm.zRot) * endpointY;
		float y = (float) Math.sin(arm.zRot) * endpointX + (float) Math.cos(arm.zRot) * endpointY;
		return new ArmEndpoint(x + arm.x, y + arm.y, endpointZ + arm.z);
	}

	private record ArmEndpoint(float x, float y, float z) {}

	private static <T> T allocate(Class<T> type) {
		try {
			Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
			field.setAccessible(true);
			return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static final class InertZombieVillager extends ZombieVillager {
		private InertZombieVillager() {
			super(EntityType.ZOMBIE_VILLAGER, null);
		}

		@Override public int getFallFlyingTicks() { return 0; }
		@Override public boolean isVisuallySwimming() { return false; }
		@Override public HumanoidArm getMainArm() { return HumanoidArm.RIGHT; }
		@Override public boolean isUsingItem() { return false; }
		@Override public boolean isAggressive() { return false; }
	}

	private static final class InertRenderEvoker extends EvokerEnchantingVisual.RenderEvoker {
		private InertRenderEvoker() {
			super(null);
		}

		@Override public void setCasting(boolean casting) {
			// This fixture has no SynchedEntityData; the test exercises model preparation only.
		}

		@Override public AbstractIllager.IllagerArmPose getArmPose() {
			return AbstractIllager.IllagerArmPose.CROSSED;
		}

		@Override public boolean isAggressive() { return false; }
		@Override public HumanoidArm getMainArm() { return HumanoidArm.RIGHT; }
	}
}
