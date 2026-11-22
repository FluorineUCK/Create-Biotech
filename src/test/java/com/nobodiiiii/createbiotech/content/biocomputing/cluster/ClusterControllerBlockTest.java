package com.nobodiiiii.createbiotech.content.biocomputing.cluster;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nobodiiiii.createbiotech.content.biocomputing.block.DataPort;
import com.nobodiiiii.createbiotech.content.biocomputing.block.EnclosureController;
import com.simibubi.create.foundation.block.IBE;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ClusterControllerBlockTest {
    private static final String ID = "biocomputing_cluster_controller";
    @BeforeAll static void bootstrap() throws Exception { WorldEnclosureViewTest.bootstrap(); }

    @Test void fullShellBlockHasOneExplicitOutwardPortInAllSixOrientations() throws Exception {
        var type = Class.forName("com.nobodiiiii.createbiotech.content.biocomputing.block.ClusterControllerBlock");
        var block = (Block)type.getConstructor(BlockBehaviour.Properties.class).newInstance(BlockBehaviour.Properties.of());
        assertInstanceOf(IBE.class, block);
        var controller = assertInstanceOf(EnclosureController.class, block);
        var port = assertInstanceOf(DataPort.class, block);
        for (Direction facing : Direction.values()) {
            var state = block.defaultBlockState().setValue(BlockStateProperties.FACING, facing);
            assertEquals(facing, controller.outward(state));
            for (Direction side : Direction.values()) assertEquals(side == facing, port.acceptsCable(state, side));
            assertTrue(Block.isShapeFullBlock(state.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, net.minecraft.core.BlockPos.ZERO)));
        }
    }

    @Test void physicalControllerHasSixDirectionalModelsLootAndSurvivalRecipe() throws Exception {
        var variants = json("assets/create_biotech/blockstates/" + ID + ".json").getAsJsonObject("variants");
        assertEquals(6, variants.size());
        for (Direction direction : Direction.values()) {
            var model = variants.getAsJsonObject("facing=" + direction.getName()).get("model").getAsString();
            assertTrue(json("assets/" + model.replace(":", "/models/") + ".json").has("textures"));
        }
        var parent = json("assets/create_biotech/models/item/" + ID + ".json").get("parent").getAsString();
        assertTrue(json("assets/" + parent.replace(":", "/models/") + ".json").has("textures"));
        assertEquals("create_biotech:" + ID, json("data/create_biotech/recipe/crafting/" + ID + ".json")
            .getAsJsonObject("result").get("id").getAsString());
        assertEquals("create_biotech:" + ID, json("data/create_biotech/loot_table/blocks/" + ID + ".json")
            .getAsJsonArray("pools").get(0).getAsJsonObject().getAsJsonArray("entries").get(0).getAsJsonObject().get("name").getAsString());
        var mineable = json("data/minecraft/tags/block/mineable/pickaxe.json").getAsJsonArray("values");
        assertTrue(java.util.stream.StreamSupport.stream(mineable.spliterator(), false).anyMatch(v -> v.getAsString().equals("create_biotech:" + ID)));
    }

    private static JsonObject json(String path) throws Exception {
        try (var stream = ClusterControllerBlockTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(stream, path);
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
