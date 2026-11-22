package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import static com.nobodiiiii.createbiotech.content.biocomputing.gametest.BiocomputingClusterXBusGameTests.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import java.util.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("create_biotech") @PrefixGameTestTemplate(false)
public final class FisherListenerGameTests {
    private static FlowInput delay(long ticks) { return new FlowInput.Literal(new DataValue.IntegerValue(ticks)); }
    private static ProgramDocument doc(FlowProgram p, DataValue.RecordValue inputs) {
        return new ProgramDocument(p.expressions(),Map.of(),Optional.empty(),Optional.of(FlowDocumentBody.fromProgram(p,inputs)));
    }

    @GameTest(templateNamespace="create_biotech_instruments",template="empty",timeoutTicks=120)
    public static void wiredOfferNotifiesFisherHatAndOnlyItsReadTransfers(GameTestHelper h) {
        var f=fixture(h,false,false,VillagerProfession.FISHERMAN,5,VillagerProfession.CLERIC,5);
        var empty=new DataValue.RecordValue(Map.of());
        var key=new ModuleDefinition.Key("receive",1);
        var rx=new FlowProgram(Map.of(),List.of(new FlowProgram.Sleep("main",delay(200)),
            new FlowProgram.Procedure("hat",key,Map.of(),Map.of(),Map.of(),FlowBlockRules.NONE,Optional.empty(),Optional.of("arrival")),
            new FlowProgram.Sleep("beforeRead",delay(15)),new FlowProgram.Read("read","x0","packet",DataType.Scalar.ANY,false),
            new FlowProgram.Sleep("afterRead",delay(30)),new FlowProgram.EndProcedure("end"))).withAssembly();
        var tx=new FlowProgram(Map.of("packet",BiocomputingFlowFixture.PACKET),List.of(new FlowProgram.Write("send","x1","packet")));
        installThroughMenu(h,f,"A",doc(rx,empty)); installThroughMenu(h,f,"B",doc(tx,BiocomputingFlowFixture.inputs(37)));
        var bind=new DataValue.RecordValue(Map.of("op",new DataValue.TextValue("bind"),"channel",new DataValue.IntegerValue(0),
            "pin",new DataValue.TextValue("x0"),"mode",new DataValue.TextValue("ready"),"entry",new DataValue.TextValue("arrival")));
        var id=UUID.randomUUID(); var menu=new ComputerControlSession(id); var action=ComputerControlSession.Action.CONFIGURE_INSTRUMENT;
        h.assertFalse(menu.apply(id,1,f.a().programRevision(),action,()->bind,f.a(),()->{throw new IllegalArgumentException("not_permitted");})
            .orElseThrow().code().equals("ok"),"Revoked control cannot arm the listener");
        h.assertFalse(menu.apply(id,2,f.b().programRevision(),action,()->bind,f.b()).orElseThrow().code().equals("ok"),"Cleric has no hardware listener");
        h.assertValueEqual(menu.apply(id,3,f.a().programRevision(),action,()->bind,f.a()).orElseThrow().code(),"ok","Public event bound");
        f.player().moveTo(Vec3.atCenterOf(f.controller().getBlockPos().west()));
        configureThroughMenu(h,f.controller(),f.player(),f.config());
        f.a().setPaused(false); f.b().setPaused(false);
        h.startSequence().thenWaitUntil(()->{
            var a=f.a().flowSnapshot().orElseThrow(); var b=f.b().flowSnapshot().orElseThrow();
            h.assertTrue(a.tasks().contexts().size()==1 && a.tasks().contexts().getFirst().status()==FlowRuntime.Status.SLEEPING,
                "Exactly one public event reached its delay on the real scheduler");
            h.assertTrue(a.receipts().isEmpty() && b.receipts().isEmpty() && b.state().status()==FlowRuntime.Status.WAITING_WRITE,
                "Notification does not fetch or acknowledge the writer");
        }).thenWaitUntil(()->{
            var a=f.a().flowSnapshot().orElseThrow(); var b=f.b().flowSnapshot().orElseThrow();
            h.assertTrue(b.state().status()==FlowRuntime.Status.COMPLETE && a.tasks().contexts().size()==1,"Sender completed on the handler read");
            var child=a.tasks().contexts().getFirst();
            h.assertValueEqual(child.locals().fields().get("packet"),BiocomputingFlowFixture.inputs(37).field("packet"),"Actual XBus payload in the event task");
            h.assertTrue(!a.state().locals().fields().containsKey("packet"),"Main did not poll or receive");
        }).thenSucceed();
    }
}
