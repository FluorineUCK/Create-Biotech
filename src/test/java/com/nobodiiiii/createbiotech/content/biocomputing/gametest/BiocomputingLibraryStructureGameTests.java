package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.nobodiiiii.createbiotech.content.biocomputing.library.*;
import com.nobodiiiii.createbiotech.registry.CBBlocks;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.*;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class BiocomputingLibraryStructureGameTests {
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void physicalClassificationBelongsToLibraryAndPreservesShelfGeometry(GameTestHelper helper) {
        var level = helper.getLevel(); var core = helper.absolutePos(new BlockPos(3, 2, 3));
        placeCore(helper, core);
        level.setBlock(core.east(), Blocks.BOOKSHELF.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(core.east(2), Blocks.CHISELED_BOOKSHELF.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(core.above().west(), Blocks.CHISELED_BOOKSHELF.defaultBlockState(), Block.UPDATE_ALL);
        var view = new LibraryStructureWorldView(level);
        var result = LibraryStructureScanner.scan(view, core, 32, 16);
        helper.assertValueEqual(result.state(), LibraryStructureScanner.State.VALID, "Registered physical core is valid");
        helper.assertValueEqual(Set.copyOf(result.snapshot().members()), Set.of(core, core.east(), core.east(2)), "Upper half cannot bridge to an otherwise detached shelf");
        helper.assertValueEqual(result.snapshot().ordinaryShelves(), java.util.List.of(core.east()), "Ordinary shelf is a bridge, not a data medium");
        helper.assertValueEqual(result.snapshot().mediaShelves(), java.util.List.of(core.east(2)), "Only real chiseled shelf is a media location");
        helper.assertValueEqual(view.memberAt(core.above()), LibraryStructureScanner.MemberKind.CORE_UPPER, "Shared vanilla half property identifies upper core");
        helper.assertValueEqual(LibraryStructureScanner.scan(view, core.above(), 32, 16).state(), LibraryStructureScanner.State.UNFORMED, "Upper half cannot be a service origin");
        helper.assertValueEqual(LibraryStructureScanner.scan(view, core, 2, 16).state(), LibraryStructureScanner.State.TOO_LARGE, "Caller member limit remains authoritative");
        helper.assertValueEqual(LibraryStructureScanner.scan(view, core, 32, 2).state(), LibraryStructureScanner.State.TOO_WIDE, "Caller span limit remains authoritative");
        level.setBlock(core.east(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        var disconnected = LibraryStructureScanner.scan(view, core, 32, 16);
        helper.assertValueEqual(disconnected.snapshot().members(), java.util.List.of(core), "Removed bridge immediately excludes the distant shelf");
        helper.assertTrue(disconnected.snapshot().mediaShelves().isEmpty(), "Geometry does not reuse the previous media range");
        level.setBlock(core.east(), Blocks.BOOKSHELF.defaultBlockState(), Block.UPDATE_ALL);
        placeCore(helper, core.east(2));
        helper.assertValueEqual(LibraryStructureScanner.scan(view, core, 32, 16).state(), LibraryStructureScanner.State.CORE_CONFLICT, "A connected second core is not merged");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void aMissingChunkDoesNotBecomeAnEmptyOrForceLoadedLibrary(GameTestHelper helper) {
        var level = helper.getLevel(); var absent = new BlockPos(29_999_000, 64, 29_999_000);
        helper.assertTrue(!level.isLoaded(absent), "Probe starts outside loaded chunks");
        var result = LibraryStructureScanner.scan(new LibraryStructureWorldView(level), absent, 32, 16);
        helper.assertValueEqual(result.state(), LibraryStructureScanner.State.PARTIAL, "Unavailable geometry is not empty success");
        helper.assertTrue(result.snapshot() == null && !level.isLoaded(absent), "Scan did not obtain or load the missing core");
        helper.succeed();
    }

    private static void placeCore(GameTestHelper helper, BlockPos pos) {
        var level = helper.getLevel(); var state = CBBlocks.PATTERN_STORAGE_CORE.get().defaultBlockState();
        level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(pos, state.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER), Block.UPDATE_ALL);
        level.setBlock(pos.above(), state.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
    }
}
