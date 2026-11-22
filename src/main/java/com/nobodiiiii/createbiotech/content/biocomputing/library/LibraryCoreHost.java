package com.nobodiiiii.createbiotech.content.biocomputing.library;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;

/**
 * Physical host lifecycle, not a program-callable storage or world-write interface.
 * The registered Library entity implements this contract; the block owns geometry
 * and never needs its recipe, resident or production state.
 */
public interface LibraryCoreHost extends LibraryRemovalHost {
    void tick();

    /** A stationary, admitted physical identity; copied or in-transit hosts cannot serve storage. */
    boolean libraryIdentityReady();
    /** Device-owned durable operation identity; unrelated to Computer programs or transport correlations. */
    java.util.UUID libraryDeviceId();

    /** Placement-only admission of the validated held item; never a program-callable setter. */
    boolean applyPlacementData(LibraryCoreItemData itemData);

    /** Removes only the newly placed lower part after failed admission, without drops or recovery. */
    void rollbackLibraryPlacement();

    /** Includes client feedback, tool pass-through and the host's current viewing permission. */
    InteractionResult useLibrary(Player player);

    /** Called only for a captured resident item used on the lower part, on the server. */
    boolean installLibraryResident(Player player, InteractionHand hand);

}
