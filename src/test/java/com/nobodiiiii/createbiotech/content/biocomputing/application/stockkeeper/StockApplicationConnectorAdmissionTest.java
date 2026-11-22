package com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper;

import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialRecordCodec;
import com.nobodiiiii.createbiotech.content.biocomputing.peripheral.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import java.util.*;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.StockApplicationTest.*;

public class StockApplicationConnectorAdmissionTest {
    /** Encoded reader sample including the real envelope and worst permitted correlation, not just the order payload. */
    public static int readerFrameBytes(StockApplication.State next, StockApplication.Input input) {
        var wide = new SerialRecordCodec.Limits(65536,1048576,new ArchiveLimits(4096,65536,262144));
        return SerialRecordCodec.encode(readerFrame(next,input),wide).length;
    }
    public static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue.RecordValue readerFrame(
            StockApplication.State next, StockApplication.Input input) {
        var target = new BlockPos(1,2,3);
        var protocol = new ReaderSamplingProtocol(UUID.randomUUID(),UUID.randomUUID(),StockApplicationHost.address(target),Set.of(CreateReadComponents.STOCK_APPLICATION));
        var wide = new SerialRecordCodec.Limits(65536,1048576,new ArchiveLimits(4096,65536,262144));
        var path = new ReaderSerialBehaviour.ApplicationPath(protocol,new ReadLimits(1024,65536,65536),target,wide,wide);
        return StockApplicationHost.readerReply(next,input,path).orElseThrow();
    }

    @Test void completeReaderEnvelopeMustFitBeforeTheMenuCanPublishInput() {
        var before = initial();
        before = StockApplication.replace(before, ACCESS, before.fence(), List.of(entry()), LIMITS).state();
        var owner = new StockApplicationSerial(before);
        var target = new BlockPos(1,2,3);
        var address = StockApplicationHost.address(target);
        var protocol = new ReaderSamplingProtocol(UUID.randomUUID(),UUID.randomUUID(),address,Set.of(CreateReadComponents.STOCK_APPLICATION));
        var format = new ArchiveLimits(4096,65536,262144);
        var wide = new SerialRecordCodec.Limits(4096,1048576,format);
        var snapshot = new ReadLimits(1024,65536,65536);
        var good = new ReaderSerialBehaviour.ApplicationPath(protocol,snapshot,target,wide,wide);
        var request = request(before);
        var view = new StockApplicationMenu.Capture(7,UUID.randomUUID(),PLAYER,before.fence(),before.nextSequence(),0,Set.of(entry().key()));
        var captured = new StockApplicationMenuRequest(7,view.nonce(),StockApplicationMenuRequest.Action.CONFIRM,-1,
            request.selections().getFirst().key(),request.selections().getFirst().quantity(),request.address());
        assertTrue(view.confirm(UUID.randomUUID(),captured).isEmpty());
        var valid = view.confirm(PLAYER,captured).orElseThrow();
        assertEquals(request,valid);
        var candidate = StockApplication.confirm(before,ACCESS,PLAYER,valid,LIMITS);
        var projected = StockApplicationHost.readerReply(candidate.state(),candidate.input().orElseThrow(),good).orElseThrow();
        int bytes = SerialRecordCodec.encode(projected,new SerialRecordCodec.Limits(65536,1048576,format)).length;
        System.out.println("STOCK_READER_FRAME_BOUNDARY " + bytes);
        assertTrue(bytes <= 4096,"complete worst-correlation sample bytes: " + bytes);
        var exact = new SerialRecordCodec.Limits(bytes,1048576,format);
        var narrow = new SerialRecordCodec.Limits(bytes-1,1048576,format);
        var limited = new ReaderSerialBehaviour.ApplicationPath(protocol,snapshot,target,wide,narrow);
        var exactPath = new ReaderSerialBehaviour.ApplicationPath(protocol,snapshot,target,exact,exact);
        assertEquals("input_too_large",owner.confirm(ACCESS,PLAYER,valid,LIMITS,(next,input) ->
            StockApplicationHost.fitsReader(next,input,limited)).code());
        assertSame(before,owner.state());
        assertEquals("input_queued",owner.confirm(ACCESS,PLAYER,valid,LIMITS,(next,input) ->
            StockApplicationHost.fitsReader(next,input,exactPath) && StockApplicationHost.fitsOutput(next,address,wide,wide)).code());
        assertEquals(1,owner.state().inputs().size());
        assertTrue(StockApplicationHost.publicInput(owner.state(),owner.state().inputs().stream().findFirst()).fields().containsKey("head"));
        long next = owner.state().nextSequence();
        owner.discardInputs(UUID.randomUUID());
        assertEquals(next,owner.state().nextSequence());
        assertEquals("retired",owner.confirm(ACCESS,PLAYER,valid,LIMITS).code());
        assertFalse(StockApplicationHost.publicInput(owner.state(),Optional.empty()).fields().containsKey("head"));
    }
}
