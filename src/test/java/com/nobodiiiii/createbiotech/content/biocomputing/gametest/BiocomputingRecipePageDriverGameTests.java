package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import static com.nobodiiiii.createbiotech.content.biocomputing.gametest.AuthoredLibraryWorldFixture.*;

import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.block.*;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.*;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.ClusterMemberEditorAccess;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.drive.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.service.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import com.nobodiiiii.createbiotech.registry.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;

/** Actual sole index, physical book, bounded serial and public editable ISA. No test-side protocol builder. */
@GameTestHolder("create_biotech") @PrefixGameTestTemplate(false)
public final class BiocomputingRecipePageDriverGameTests {
    @GameTest(template = "empty", timeoutTicks = 12000)
    public static void physicalRecipePageSurvivesDeviceReconstructionAndFeedsPublicArithmetic(GameTestHelper h) {
        new Fixture(h).start();
    }
    private static DataValue.RecordValue record(Object... pairs) {
        var values = new LinkedHashMap<String, DataValue>();
        for (int i = 0; i < pairs.length; i += 2) values.put((String)pairs[i], pairs[i + 1] instanceof DataValue v
            ? v : new DataValue.IntegerValue(((Number)pairs[i + 1]).longValue()));
        return new DataValue.RecordValue(values);
    }
    private static ProgramDocument document(String name) {
        java.util.function.Function<net.minecraft.resources.ResourceLocation, java.io.InputStream> resources = id ->
            BiocomputingRecipePageDriverGameTests.class.getClassLoader().getResourceAsStream("assets/" + id.getNamespace() + "/" + id.getPath());
        var entry = ProgramExampleCatalog.load(resources).stream().filter(e -> e.id().equals(name)).findFirst().orElseThrow();
        return ProgramExampleCatalog.preview(entry, resources, CBConfigs.SERVER.biocomputing.current(true).archive());
    }
    private static final class Fixture {
        final GameTestHelper h;
        final ServerLevel level;
        final ComputerBlockEntity[] computers = new ComputerBlockEntity[4];
        final ChiseledBookShelfBlockEntity shelf;
        final ItemStack original;
        LibraryBlockEntity library;
        boolean rebuilt;
        int polls;
        Fixture(GameTestHelper h) {
            this.h = h; level = h.getLevel();
            var min = h.absolutePos(new BlockPos(1, 1, 1)); var max = min.offset(6, 3, 4);
            var controllerPos = min.offset(6, 1, 2); var libraryPos = controllerPos.east(4);
            var members = new LinkedHashMap<String, ClusterConfiguration.Member>();
            for (int n = 0; n < 4; n++) {
                String role = String.valueOf((char)('a' + n));
                computers[n] = computer(h, n == 3 ? min.offset(3, 1, 3) : min.offset(1 + n * 2, 1, 1), VillagerProfession.CLERIC, 5);
                var empty = new ProgramWorkspace();
                empty.setFlow(Optional.of(FlowDocumentBody.fromProgram(new FlowProgram(Map.of(), List.of()).withAssembly(), record())));
                host(computers[n]).installDocument(empty.snapshot(), ComputerRuntime.MemoryPolicy.RESET);
                host(computers[n]).configureEndpoint(new EndpointConfiguration(role, true));
                members.put(role, new ClusterConfiguration.Member(host(computers[n]).communicationIdentity()
                    .orElseThrow(() -> new IllegalStateException("Missing configured identity: " + role)), computers[n].getBlockPos()));
            }
            for (var p : BlockPos.betweenClosed(min, max)) if (p.getX() == min.getX() || p.getX() == max.getX()
                || p.getY() == min.getY() || p.getY() == max.getY() || p.getZ() == min.getZ() || p.getZ() == max.getZ())
                level.setBlock(p, CBBlocks.TRANSPARENT_COMPUTER_CASING.get().defaultBlockState(), 3);
            level.setBlock(controllerPos, CBBlocks.CLUSTER_CONTROLLER.get().defaultBlockState().setValue(ClusterControllerBlock.FACING, Direction.EAST), 3);
            var controller = (ClusterControllerBlockEntity)level.getBlockEntity(controllerPos);
            var actor = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "recipe-page"));
            actor.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(controllerPos.east()));
            h.assertValueEqual(controller.configure(new ClusterConfiguration(members, Map.of(), Map.of(), Map.of(
                "ab", new XBusWire(new XBusWire.Pin("a", "a", "x0"), new XBusWire.Pin("b", "b", "x0"), true),
                "bc", new XBusWire(new XBusWire.Pin("b", "b", "x1"), new XBusWire.Pin("c", "c", "x0"), true),
                "ad", new XBusWire(new XBusWire.Pin("a", "a", "x1"), new XBusWire.Pin("d", "d", "x0"), true))), actor), "configured", "Explicit cooperation");
            var b = host(computers[1]);
            b.configureSerialGateway("s0", Optional.of(SerialGatewayAccess.authorize(new ClusterMemberEditorAccess(controller, computers[1]), actor, "s0", "rom")));
            b.configureSerial(new SerialControlData.Configuration("s0", Direction.EAST, true));
            for (int x = 1; x < 4; x++) level.setBlock(controllerPos.east(x), CBBlocks.DATA_CABLE.get().defaultBlockState(), 3);
            var state = CBBlocks.PATTERN_STORAGE_CORE.get().defaultBlockState().setValue(LibraryCoreBlock.FACING, Direction.EAST);
            level.setBlock(libraryPos.below(), Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(libraryPos, state.setValue(LibraryCoreBlock.HALF, DoubleBlockHalf.LOWER), 3);
            level.setBlock(libraryPos.above(), state.setValue(LibraryCoreBlock.HALF, DoubleBlockHalf.UPPER), 3);
            library = (LibraryBlockEntity)level.getBlockEntity(libraryPos);
            library.applyPlacementData(LibraryCoreItemData.configured(CommunicationFrequency.of("recipe-page"), 0, UUID.randomUUID(), 0)); library.initialize();
            level.setBlock(libraryPos.north(), Blocks.CHISELED_BOOKSHELF.defaultBlockState(), 3);
            shelf = (ChiseledBookShelfBlockEntity)level.getBlockEntity(libraryPos.north());
            var book = new ItemStack(Items.WRITABLE_BOOK);
            book.set(DataComponents.WRITABLE_BOOK_CONTENT, new WritableBookContent(List.of(Filterable.passThrough("""
                {"version":2,"type":"standard","inputs":[{"item":"minecraft:stone","count":2}],"outputs":[{"item":"minecraft:paper","count":1}],"address":"press"}
                """))));
            var media = new LibraryBookMedia(CBDataComponents.BIOCOMPUTING_LIBRARY_CONTENTS.get(), CBConfigs.SERVER.biocomputing.current(true).archive());
            book = media.put(book, "note", new LibraryEntry(LibraryEntry.Kind.DATA, new DataValue.TextValue("preserve")));
            book = new LibraryBookVolumes(CBDataComponents.BIOCOMPUTING_LIBRARY_VOLUME.get()).rename(book, "Recipes");
            original = book.copy(); shelf.setItem(0, book);
            var service = library.getBehaviour(LibraryServiceBehaviour.TYPE);
            h.assertValueEqual(service.configure(0, new LibraryServiceConfiguration(Optional.empty(), Map.of("Recipes", LibraryServiceConfiguration.Access.READ_ONLY))), "configured", "Explicit read-only ROM grant");
            actor.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(libraryPos));
            var control = library.getBehaviour(LibraryControlBehaviour.TYPE);
            var delegate = control.discover(actor).stream().filter(c -> c.member().equals(computers[1].getBlockPos()) && c.controller().isPresent()).findFirst().orElseThrow();
            h.assertValueEqual(control.configure(actor, 0, Optional.of(delegate)), "configured", "Physical delegate");
            h.assertValueEqual(control.configureSerial(actor, 1, true), "configured", "Explicit serial enable");
            var a = document("recipe_page_a"); var body = a.flow().orElseThrow();
            var fields = new LinkedHashMap<>(body.initial().fields());
            fields.put("target", record("x", libraryPos.getX(), "y", libraryPos.getY(), "z", libraryPos.getZ()));
            var rows = new ArrayList<>(((DataValue.ListValue)fields.get("commands")).values());
            var seek = new LinkedHashMap<>(((DataValue.RecordValue)rows.get(1)).fields());
            seek.put("d", LibraryTrackAddress.from(libraryPos, Direction.EAST, shelf.getBlockPos()).value()); rows.set(1, new DataValue.RecordValue(seek));
            fields.put("commands", new DataValue.ListValue(rows));
            a = new ProgramDocument(a.archive(), a.positions(), a.terminal(), Optional.of(new FlowDocumentBody(body.inputs(), body.steps(), new DataValue.RecordValue(fields), body.canvas(), body.inline(), body.execution())));
            var docs = List.of(a, document("library_read_b"), document("library_read_c"), document("recipe_page_d"));
            for (int i = 0; i < 4; i++) {
                host(computers[i]).installDocument(docs.get(i), ComputerRuntime.MemoryPolicy.RESET);
                h.assertValueEqual(host(computers[i]).hardware().limits().hardware(), new ResourceLimits(256, 65536, 8), "Unchanged individual hardware");
            }
        }
        void start() {
            h.runAfterDelay(60, () -> {
                host(computers[3]).setPaused(false);
                h.runAfterDelay(60, () -> {
                    var waiting=host(computers[3]).flowSnapshot().orElseThrow().state();
                    h.assertTrue(waiting.status()!=FlowRuntime.Status.FAILED&&waiting.status()!=FlowRuntime.Status.WAITING_READ,
                        "D starts alone and polls without admitting READ against paused A");
                    h.assertTrue(waiting.work()>0&&waiting.work()<60,"D actually ran with bounded polling rather than a busy loop");
                    for(int n:List.of(0,1,2)){
                        var paused=host(computers[n]).flowSnapshot().orElseThrow().state();
                        h.assertTrue(paused.paused()&&paused.work()==0,"A/B/C remain paused during the D-first startup gate");
                    }
                    for(int n:List.of(2,1,0))host(computers[n]).setPaused(false);
                    poll();
                });
            });
        }
        void poll() {
            h.runAfterDelay(1, () -> {
                var drive = library.getBehaviour(LibraryDriveBehaviour.TYPE).snapshot().orElseThrow();
                h.assertTrue(drive.sequence() <= 3, "Exactly seek/select/recipe_page; no replay");
                for (var computer : computers) h.assertTrue(host(computer).flowSnapshot().orElseThrow().state().status() != FlowRuntime.Status.FAILED,
                    "ISA failure: " + host(computer).flowSnapshot().orElseThrow().state().diagnostic());
                // Reconstruct mechanical work only after its admission reply has left the wire.
                // Unloading an actively transmitting serial endpoint intentionally interrupts that frame;
                // this test must not demand hidden reliable resending from the public driver.
                if (!rebuilt && drive.pending().filter(p -> p.action().name().equals("RECIPE_PAGE")).isPresent()
                        && library.getBehaviour(LibrarySerialBehaviour.TYPE).device().status("s0").transmit().equals("transmitted")) {
                    rebuilt = true;
                    for (var computer : computers) host(computer).setPaused(true);
                    library = (LibraryBlockEntity)reconstruct(level, library);
                    h.assertValueEqual(library.getBehaviour(LibraryDriveBehaviour.TYPE).snapshot().orElseThrow(), drive, "Exact pending device checkpoint");
                    h.runAfterDelay(250, () -> {
                        var completed = library.getBehaviour(LibraryDriveBehaviour.TYPE).snapshot().orElseThrow();
                        h.assertValueEqual(completed.result().orElseThrow().code(), "recipe_page", "Device completes independently of paused delegates");
                        for (int n : List.of(3, 2, 1, 0)) host(computers[n]).setPaused(false);
                        poll();
                    });
                    return;
                }
                var a = host(computers[0]).flowSnapshot().orElseThrow().state();
                if (++polls % 500 == 0) System.out.println("RECIPE_ROM tick=" + polls + " drive=" + drive
                    + " owners=" + Arrays.stream(computers).map(c -> host(c).flowSnapshot().orElseThrow().state().status().toString()).toList());
                if (a.status() == FlowRuntime.Status.COMPLETE)
                    h.assertValueEqual(((DataValue.RecordValue)a.locals().field("result")).field("code"),
                        new DataValue.TextValue("acknowledged"), "Public reader completed without a hidden device failure");
                var d = host(computers[3]).flowSnapshot().orElseThrow().state();
                if (a.status() != FlowRuntime.Status.COMPLETE || d.status() != FlowRuntime.Status.COMPLETE) { poll(); return; }
                var result = (DataValue.RecordValue)d.locals().field("result");
                h.assertTrue(rebuilt, "Real in-flight Library reconstruction occurred");
                h.assertValueEqual(result.field("count"), new DataValue.IntegerValue(6), "Public ISA computes 2 x 3");
                h.assertTrue(result.field("recipe") instanceof DataValue.RecipeValue,"D retains a genuine recipe@1, not a similar raw record");
                var recipe=(DataValue.RecipeValue)result.field("recipe");
                h.assertValueEqual(recipe.fmt(),"recipe","Explicit nominal recipe format");
                h.assertValueEqual(recipe.version(),1,"Explicit nominal recipe version");
                h.assertValueEqual(((DataValue.RecordValue)result.field("ingredient")).field("count"), new DataValue.IntegerValue(2), "Read source value remains unchanged");
                h.assertTrue(ItemStack.matches(original, shelf.getItem(0)), "No original page, UUID or data entry mutation");
                h.assertTrue(drive.result().isEmpty() && drive.sequence() == 3, "Public ACK, no hidden cleanup");
                h.assertTrue(host(computers[3]).document().orElseThrow().terminal().isPresent(), "Installed public result terminal");
                for (var computer : computers) host(computer).setPaused(true);
                h.succeed();
            });
        }
    }
}
