package com.nobodiiiii.createbiotech.content.biocomputing.block;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

/** Embedded concentrated I/O entry; its outward side anchors the enclosure. */
public interface EnclosureController {
    Direction outward(BlockState state);
}
