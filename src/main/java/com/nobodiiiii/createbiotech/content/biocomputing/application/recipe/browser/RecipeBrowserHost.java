package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.ProductionLibraryRouting;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeIndexSnapshot;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeSourceLocation;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/** Local manual recipe application, not a program-callable world mutation capability.
 * Implementations bind access checks to the exact loaded physical owner and current player.
 * Mutation methods must also validate the current session, generation and physical source
 * at commit; the menu's early authorization check never replaces that transaction fence. */
public interface RecipeBrowserHost {
    UUID libraryId();
    RecipeSourceLocation libraryLocation();
    ProductionLibraryRouting routingConfiguration();
    RecipeIndexSnapshot recipeSnapshot();
    boolean canPlayerUse(Player player);
    boolean canPlayerEdit(ServerPlayer player);
    boolean openBookLibrary(ServerPlayer player);
    PatternBrowserService browserService(BrowserFence fence);
    PatternBrowserDiagnostics browserDiagnostics();
    String editPattern(ServerPlayer player, BrowserFence fence, UUID pattern, long generation, String json);
    String createPattern(ServerPlayer player, BrowserFence fence, String json);
}
