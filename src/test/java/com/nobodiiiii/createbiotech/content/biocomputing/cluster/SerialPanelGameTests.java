package com.nobodiiiii.createbiotech.content.biocomputing.cluster;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.neoforged.neoforge.gametest.*;
import com.nobodiiiii.createbiotech.registry.CBBlocks;
import com.nobodiiiii.createbiotech.content.biocomputing.block.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CableDiscovery;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import java.util.*;
@GameTestHolder("create_biotech") @PrefixGameTestTemplate(false)
public final class SerialPanelGameTests {
    @GameTest(template="empty")
    public static void outwardPanelsNotControllers(GameTestHelper h){
        var level=h.getLevel();var min=h.absolutePos(new BlockPos(1,2,1));var max=min.offset(4,4,4);
        var bounds=new ClusterEnclosure.Bounds(min,max);
        for(var p:BlockPos.betweenClosed(min,max))if(bounds.boundary(p))level.setBlock(p,CBBlocks.COMPUTER_CASING.get().defaultBlockState(),2);
        var cp=min.offset(0,1,2);level.setBlock(cp,CBBlocks.CLUSTER_CONTROLLER.get().defaultBlockState().setValue(ClusterControllerBlock.FACING,Direction.WEST),2);
        var c=(ClusterControllerBlockEntity)level.getBlockEntity(cp);
        var first=min.offset(4,1,2);var second=min.offset(2,1,4);
        for(var pos:List.of(first,second)){
            var side=pos.equals(first)?Direction.EAST:Direction.SOUTH;
            var state=CBBlocks.SERIAL_PANEL.get().defaultBlockState().setValue(SerialPanelBlock.FACING,side);
            level.setBlock(pos,state,2);
            h.assertTrue(CBBlocks.SERIAL_PANEL.get().acceptsCable(state,side) && !CBBlocks.SERIAL_PANEL.get().acceptsCable(state,side.getOpposite()),"Only outward cable face");
        }
        var lower=min.offset(1,1,1);var other=min.offset(3,1,2);
        for(var m:List.of(lower,other)){
            var state=CBBlocks.COMPUTER.get().defaultBlockState();
            level.setBlock(m,state,2);
            level.setBlock(m.above(),state.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF,net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER),2);
        }
        var shape=c.enclosure().enclosure().orElseThrow();
        var a=(SerialPanelEntity)level.getBlockEntity(first);var b=(SerialPanelEntity)level.getBlockEntity(second);
        h.assertTrue(a.onShell(shape)&&b.onShell(shape)&&!a.identity().equals(b.identity()),"Two independent panel identities, one valid controller");
        var actor=net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(level);
        actor.moveTo(cp.getX()+.5,cp.getY()+.5,cp.getZ()+.5);
        var m1=(com.simibubi.create.foundation.blockEntity.SmartBlockEntity)level.getBlockEntity(lower);
        var m2=(com.simibubi.create.foundation.blockEntity.SmartBlockEntity)level.getBlockEntity(other);
        var ac1=new com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.ClusterMemberEditorAccess(c,m1);
        var ac2=new com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.ClusterMemberEditorAccess(c,m2);
        var page=new com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue.RecordValue(Map.of(
            "page",new com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue.IntegerValue(0),
            "port",new com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue.TextValue("s0")));
        var listing=SerialGatewayAccess.page(ac1,actor,page);
        h.assertTrue(((com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue.IntegerValue)listing.field("count")).value()==2,
            "Only the two external shell sockets are listed; casing and controller are not sockets");
        var pick=new com.nobodiiiii.createbiotech.content.biocomputing.editor.SerialControlData.PanelSocket(a.identity(),first,Direction.EAST);
        var config=new com.nobodiiiii.createbiotech.content.biocomputing.editor.SerialControlData.GatewayConfiguration("s0","east_line",Optional.of(pick));
        var wrong=new com.nobodiiiii.createbiotech.content.biocomputing.editor.SerialControlData.PanelSocket(b.identity(),first,Direction.EAST);
        try { SerialGatewayAccess.authorize(ac1,actor,new com.nobodiiiii.createbiotech.content.biocomputing.editor.SerialControlData.GatewayConfiguration("s0","east_line",Optional.of(wrong)));
            throw new AssertionError("Replaced panel accepted"); }
        catch(IllegalArgumentException ex){h.assertTrue("gateway_changed".equals(ex.getMessage()),"Stale panel identity rejected");}
        var r1=SerialGatewayAccess.authorize(ac1,actor,config);
        b.select(actor);var r2=SerialGatewayAccess.authorize(ac2,actor,"s0","south_line");
        h.assertTrue(r1.position().equals(first)&&r2.position().equals(second)&&a.owner()==c&&b.owner()==c,"Member binding terminates at selected panel");
        var legacy=new SerialGatewayBinding(c.controllerId(),cp,Direction.WEST,"old");
        try { SerialGatewayAccess.resolve(level,min,new SerialDevice.Attachment(null,"s0",UUID.randomUUID(),Direction.WEST,null,null,Optional.of(legacy)));throw new AssertionError("Legacy controller exit still connected"); }
        catch(CableDiscovery.DiscoveryException expected){h.assertTrue(expected.code().equals("gateway_unavailable"),"Fail closed");}
        var view=new SerialControlAccess(level,first,null);
        var target=new CableDiscovery.Connection(second,Direction.SOUTH);
        var cable=CBBlocks.DATA_CABLE.get().defaultBlockState();
        for(var face:Direction.Plane.HORIZONTAL) cable=cable.setValue(DataCableBlock.connection(face),true);
        level.setBlock(first.east(),cable,3);level.setBlock(second.south(),cable,3);
        h.assertFalse(CableDiscovery.scanEndpointConnections(view,first,128,16).contains(target),"Separate wires are separate media");
        for(int z=3;z<=5;z++)level.setBlock(min.offset(5,1,z),cable,3);
        for(int x=3;x<=4;x++)level.setBlock(min.offset(x,1,5),cable,3);
        h.assertTrue(CableDiscovery.scanEndpointConnections(view,first,128,16).contains(target),"Outside cable physically joins panels");
        h.succeed();
    }
}
