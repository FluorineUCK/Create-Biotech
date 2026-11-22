package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ValueNbtCodec;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.*;

/** A cold copy of an actual partial book write. Nothing here can supply a byte, ACK or rebind the driver. */
@GameTestHolder("create_biotech") @PrefixGameTestTemplate(false)
public final class StockBookWriteResumeGameTests {
    @GameTest(template="empty", timeoutTicks=1400)
    public static void installedDriverContinuesTheSavedPartialWrite(GameTestHelper h) throws Exception {
        var meta = new Properties();
        try (var in = Files.newBufferedReader(Path.of("partial.properties"))) { meta.load(in); }
        var expected = Files.readAllBytes(Path.of("actual-order.typed.nbt"));
        int prior = Integer.parseInt(meta.getProperty("received"));
        h.assertTrue(prior > 0 && prior < expected.length, "An existing partial, not an empty or complete transfer");
        h.assertTrue(ProcessHandle.current().pid() != Long.parseLong(meta.getProperty("originPid")), "Independent process");
        var xyz = Arrays.stream(meta.getProperty("controller").split(",")).mapToInt(Integer::parseInt).toArray();
        var gate = new BlockPos(xyz[0],xyz[1],xyz[2]); var level = h.getLevel();
        h.assertTrue(h.absolutePos(BlockPos.ZERO).distSqr(gate) > 128 * 128, "Test structure must not overlap the saved installation");
        for (int x=(gate.getX()-8)>>4; x<=(gate.getX()+16)>>4; x++)
            for (int z=(gate.getZ()-8)>>4; z<=(gate.getZ()+8)>>4; z++) {
                level.setChunkForced(x,z,true); level.getChunk(x,z);
            }
        var core = gate.offset(-2,0,2); var output = gate.offset(5,0,-1);
        var shelf = (Container)level.getBlockEntity(core.north());
        var book = shelf.getItem(2).copy(); var stock = stock(level,output);
        var queue = (ListValue)stock.field("inputs");
        h.assertValueEqual(queue.values().size(),1,"Same native input still pending on load");
        var head = (RecordValue)queue.values().getFirst();
        h.assertValueEqual(head.field("device"),new TextValue(meta.getProperty("inputDevice")),"Original Stock device");
        h.assertValueEqual(head.field("sequence"),new IntegerValue(Long.parseLong(meta.getProperty("inputSequence"))),"Original queue sequence");
        var initial = transfer(level,core);
        h.assertValueEqual(initial.field("device"),new TextValue(meta.getProperty("writer")),"Same delegated drive operation");
        h.assertValueEqual(initial.field("sequence"),new IntegerValue(Long.parseLong(meta.getProperty("driveSequence"))),"Not a new transaction");
        h.assertValueEqual(initial.field("bytes"),new IntegerValue(expected.length),"Original declaration");
        h.assertValueEqual(((RecordValue)initial.field("expected")).field("medium"),new TextValue(meta.getProperty("medium")),"Physical medium UUID");
        h.assertValueEqual(prefix(h,initial,expected),prior,"Already accepted bytes survive deserialization");
        System.out.println("STOCK_BOOK_COLD restored " + prior + "/" + expected.length + " bytes; waiting for the saved program");
        h.startSequence().thenWaitUntil(() -> h.assertTrue(
            ((IntegerValue)transfer(level,core).field("received")).value() > prior,"Original transaction has not gained its next fragment"))
            .thenExecute(() -> {
                var now = transfer(level,core); int advanced = prefix(h,now,expected);
                for (var key : List.of("device","sequence","volume","entry","expected","bytes","format","kind"))
                    h.assertValueEqual(now.field(key),initial.field(key),"No transaction replacement: " + key);
                h.assertTrue(advanced < expected.length,"Do not substitute a final commit for the in-flight boundary");
                h.assertTrue(ItemStack.matches(book,shelf.getItem(2)),"No partial write visible on the book");
                var after = stock(level,output);
                for (var key : List.of("device","next_sequence","inputs"))
                    h.assertValueEqual(after.field(key),stock.field(key),"No re-enqueue or ACK: " + key);
                for (var p : List.of(gate.offset(4,-1,0),gate.offset(2,-1,-4)))
                    h.assertValueEqual(((Container)level.getBlockEntity(p)).getItem(0).getCount(),16,"No inventory extraction during staging");
                System.out.println("STOCK_BOOK_COLD continued " + prior + " -> " + advanced + "/" + expected.length +
                    " exact bytes on the original transaction; book, stock and queue unchanged");
            }).thenSucceed();
    }
    private static int prefix(GameTestHelper h, RecordValue transfer, byte[] expected) {
        int at=0;
        for (var part : ((ListValue)transfer.field("segments")).values())
            for (byte b : HexFormat.of().parseHex(((TextValue)part).value())) {
                h.assertTrue(at < expected.length && b == expected[at],"Wrong byte at saved offset " + at); at++;
            }
        h.assertValueEqual(((IntegerValue)transfer.field("received")).value(),(long)at,"No missing/duplicated segment");
        return at;
    }
    private static RecordValue transfer(ServerLevel level, BlockPos pos) {
        var tag = Objects.requireNonNull(level.getBlockEntity(pos)).saveWithoutMetadata(level.registryAccess());
        return (RecordValue)read(tag.getCompound("BiocomputingLibraryDrive")).field("transfer");
    }
    private static RecordValue stock(ServerLevel level, BlockPos pos) {
        var tag = Objects.requireNonNull(level.getBlockEntity(pos)).saveWithoutMetadata(level.registryAccess());
        return read(tag.getCompound("BiocomputingStockApplication").getCompound("State"));
    }
    private static RecordValue read(net.minecraft.nbt.CompoundTag tag) {
        return (RecordValue)ValueNbtCodec.decode(tag,CBConfigs.SERVER.biocomputing.current(CBConfigs.SERVER_SPEC.isLoaded()).archive());
    }
}
