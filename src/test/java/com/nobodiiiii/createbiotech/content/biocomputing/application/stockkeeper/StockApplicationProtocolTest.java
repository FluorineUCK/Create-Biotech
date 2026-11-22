package com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.StockApplicationTest.*;
import java.util.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import org.junit.jupiter.api.Test;

class StockApplicationProtocolTest {
    static final DataValue.RecordValue TARGET = record("x",number(1),"y",number(2),"z",number(3));
    static DataValue.RecordValue command(String operation, DataValue.RecordValue arguments) {
        return record("kind",text("stock_application_command"),"version",number(1),"target",TARGET,"request",text("test:1"),"operation",text(operation),"arguments",arguments);
    }
    static DataValue.RecordValue args(StockApplication.State s, Object... extra) {
        var fields = new LinkedHashMap<String, DataValue>(Map.of("device",text(s.device().toString()),"session",text(s.session().toString()),"revision",number(s.revision())));
        fields.putAll(record(extra).fields()); return new DataValue.RecordValue(fields);
    }
    @Test void externalOutputCannotPollAndOversizedCatalogStillRequiresCurrentFence() {
        var start = initial();
        var valid = new ArrayList<DataValue>();
        for (int i = 0; i <= StockApplicationValues.MAX_MEMBERS; i++) {
            var e = entry();
            valid.add(StockApplicationValues.entry(new StockApplication.Entry("sample" + i, e.title(), e.unit(), e.icon(), e.data())));
        }
        var above = new DataValue.ListValue(valid);
        var stale = record("device",text(start.device().toString()),"session",text(start.session().toString()),"revision",number(start.revision()+1),"entries",above);
        assertEquals(text("stale_revision"), StockApplicationProtocol.plan(start,ACCESS,TARGET,command("replace",stale),LIMITS).orElseThrow().reply().field("code"));
        assertEquals(text("capacity"), StockApplicationProtocol.plan(start,ACCESS,TARGET,command("replace",args(start,"entries",above)),LIMITS).orElseThrow().reply().field("code"));
        valid.set(0, text("malformed"));
        assertEquals(text("invalid_command"), StockApplicationProtocol.plan(start,ACCESS,TARGET,command("replace",args(start,"entries",new DataValue.ListValue(valid))),LIMITS).orElseThrow().reply().field("code"));
        var pending = StockApplication.confirm(StockApplication.replace(start,ACCESS,start.fence(),List.of(entry()),LIMITS).state(),
            ACCESS,PLAYER,request(StockApplication.replace(start,ACCESS,start.fence(),List.of(entry()),LIMITS).state()),LIMITS).state();
        var plan = StockApplicationProtocol.planOutput(pending,Optional.of(ACCESS),TARGET,command("poll",args(pending)),LIMITS).orElseThrow();
        assertEquals(text("unsupported_operation"), plan.reply().field("code"));
        assertFalse(plan.reply().fields().containsKey("input"));
        assertSame(pending, plan.next());
    }
    @Test void absentApplicationProofProducesOnlyACorrelatedPermissionDenial() {
        var start=initial();
        for(var operation:List.of("status","replace","clear","poll","ack")) {
            var plan=StockApplicationProtocol.plan(start,Optional.empty(),TARGET,command(operation,record()),LIMITS).orElseThrow();
            assertSame(start,plan.next());assertEquals(text("permission"),plan.reply().field("code"));
            assertEquals(Set.of("kind","version","target","request","code"),plan.reply().fields().keySet());
            assertEquals(text("test:1"),plan.reply().field("request"));assertEquals(TARGET,plan.reply().field("target"));
        }
        assertTrue(StockApplicationProtocol.plan(start,Optional.empty(),TARGET,new DataValue.NbtValue(10,record()),LIMITS).isEmpty());
    }
    @Test void protocolIsOptInStrictPrivateAndCorrelatedWithNoProductionReceipt() {
        var start = initial();
        var entry = StockApplicationValues.entry(entry());
        var replace = command("replace", args(start,"entries",new DataValue.ListValue(List.of(entry))));
        var prepared = StockApplicationProtocol.plan(start,ACCESS,TARGET,replace,LIMITS).orElseThrow();
        assertEquals(text("accepted"),prepared.reply().field("code"));
        assertTrue(start.catalog().isEmpty()); assertEquals(1,prepared.next().catalog().size());
        assertEquals(text("test:1"),prepared.reply().field("request"));
        assertEquals(TARGET,prepared.reply().field("target"));
        assertFalse(prepared.reply().fields().containsKey("manufactured"));
        var denied = new StockApplication.Access(new StockApplication.Application(UUID.randomUUID(),UUID.randomUUID()),true,false);
        for (var peer : List.of(denied,new StockApplication.Access(APP,false,false),new StockApplication.Access(APP,true,true))) {
            var reply=StockApplicationProtocol.plan(prepared.next(),peer,TARGET,command("status",record()),LIMITS).orElseThrow();
            assertSame(prepared.next(),reply.next()); assertFalse(reply.reply().fields().containsKey("state"));
            assertFalse(reply.reply().fields().containsKey("input"));
        }
        assertTrue(StockApplicationProtocol.plan(start,ACCESS,record("x",number(0)),replace,LIMITS).isEmpty());
        assertTrue(StockApplicationProtocol.plan(start,ACCESS,TARGET,new DataValue.NbtValue(10,record()),LIMITS).isEmpty());
        var malformed = new LinkedHashMap<>(replace.fields()); malformed.put("peer",text(APP.computer().toString()));
        assertEquals(text("invalid_command"),StockApplicationProtocol.plan(start,ACCESS,TARGET,new DataValue.RecordValue(malformed),LIMITS).orElseThrow().reply().field("code"));
        malformed.remove("peer"); malformed.put("version",number(2));
        assertEquals(text("unsupported_version"),StockApplicationProtocol.plan(start,ACCESS,TARGET,new DataValue.RecordValue(malformed),LIMITS).orElseThrow().reply().field("code"));
        var pending=StockApplication.confirm(prepared.next(),ACCESS,PLAYER,request(prepared.next()),LIMITS).state();
        var poll=StockApplicationProtocol.plan(pending,ACCESS,TARGET,command("poll",args(pending)),LIMITS).orElseThrow();
        assertEquals(StockApplicationValues.input(pending.inputs().getFirst()),poll.reply().field("input")); assertSame(pending,poll.next());
        var ack=StockApplicationProtocol.plan(pending,ACCESS,TARGET,command("ack",args(pending,"sequence",number(1))),LIMITS).orElseThrow();
        assertTrue(ack.next().inputs().isEmpty());
        assertEquals(text("stale_revision"),StockApplicationProtocol.plan(prepared.next(),ACCESS,TARGET,replace,LIMITS).orElseThrow().reply().field("code"));
    }
}

