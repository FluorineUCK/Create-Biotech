package com.nobodiiiii.createbiotech.content.biocomputing.network.serial;

import com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterEnclosure;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.ClusterMemberEditorAccess;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBehaviour;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CableDiscovery;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import net.minecraft.core.BlockPos;
import com.nobodiiiii.createbiotech.content.biocomputing.block.SerialPanelEntity;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.SerialControlData.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.*;
import net.minecraft.server.level.*;

/** Revalidates physical membership; no controller queue, program, addressing or hidden forwarding. */
public final class SerialGatewayAccess {
    private SerialGatewayAccess() {}
    public static SerialGatewayBinding authorize(ClusterMemberEditorAccess access, ServerPlayer player, String port, String alias) {
        return authorize(access, player, new GatewayConfiguration(port, alias));
    }
    private static boolean accessible(ServerLevel level, ServerPlayer player, BlockPos pos) {
        return level.hasChunkAt(pos) && level.getWorldBorder().isWithinBounds(pos) && level.mayInteract(player,pos);
    }
    /** One socket per response, in a stable spatial order. No force-loading and no scan outside the formed shell. */
    public static DataValue.RecordValue page(ClusterMemberEditorAccess access, ServerPlayer player, DataValue.RecordValue request) {
        if (!request.fields().keySet().equals(Set.of("page","port")) || !(request.field("page") instanceof DataValue.IntegerValue index)
                || index.value()<0 || !(request.field("port") instanceof DataValue.TextValue name)) throw new IllegalArgumentException("invalid_panel");
        if (access==null || !access.stillValid(player)) throw new IllegalArgumentException("open_from_controller");
        var controller=access.controller(); var geometry=controller.enclosure().enclosure().orElseThrow();
        var level=player.serverLevel();
        var panels=geometry.shell().stream().filter(pos -> accessible(level,player,pos))
            .map(level::getBlockEntity).filter(SerialPanelEntity.class::isInstance).map(SerialPanelEntity.class::cast)
            .filter(p -> p.onShell(geometry)).sorted(Comparator.comparing(SerialPanelEntity::getBlockPos)).toList();
        int i=(int)Math.min(index.value(),Math.max(0,panels.size()-1));
        Map<String,DataValue> fields=new HashMap<>();
        fields.put("page",new DataValue.IntegerValue(i)); fields.put("count",new DataValue.IntegerValue(panels.size()));
        if(!panels.isEmpty()){
            var p=panels.get(i); var socket=new PanelSocket(p.identity(),p.getBlockPos(),p.side());
            fields.put("panel",socket.encode());
            String state="available";
            try {unique(level,geometry,new SerialGatewayBinding(p.identity(),p.getBlockPos(),p.side(),"panel"),access.computer().getBlockPos(),name.value(),false);}
            catch(IllegalArgumentException ex){state=ex.getMessage();}
            fields.put("state",new DataValue.TextValue(state));
        }
        return new DataValue.RecordValue(fields);
    }
    public static SerialGatewayBinding authorize(ClusterMemberEditorAccess access, ServerPlayer player, GatewayConfiguration config) {
        var port=config.port(); var alias=config.alias();
        var grant = com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterPhysicalPortAccess.authorize(access, player, alias);
        var controller = access.controller(); var member = access.computer();
        var geometry = controller.enclosure().enclosure().orElseThrow(() -> new IllegalArgumentException("gateway_unformed"));
        var panel=config.panel().map(s -> {
            var level=player.serverLevel();
            if (!geometry.shell().contains(s.pos()) || !accessible(level,player,s.pos())
                    || !(level.getBlockEntity(s.pos()) instanceof SerialPanelEntity p)
                    || !p.identity().equals(s.id()) || p.side()!=s.side()) throw new IllegalArgumentException("gateway_changed");
            return p;
        }).orElseGet(() -> SerialPanelEntity.selected(player));
        if(!panel.onShell(geometry)) throw new IllegalArgumentException("panel_not_on_shell");
        var binding = new SerialGatewayBinding(panel.identity(),panel.getBlockPos(),panel.side(),grant.alias());
        unique(player.serverLevel(), geometry, binding, member.getBlockPos(), port, false);
        panel.bind(controller);
        return binding;
    }
    public static CableDiscovery.Connection resolve(ServerLevel level, BlockPos member, SerialDevice.Attachment port) {
        if (port.gateway().isEmpty()) return new CableDiscovery.Connection(member, port.side());
        var binding = port.gateway().orElseThrow(); var pos = binding.position();
        if(!level.hasChunkAt(pos)) throw failure("gateway_unloaded");
        if(!(level.getBlockEntity(pos) instanceof com.nobodiiiii.createbiotech.content.biocomputing.block.SerialPanelEntity panel) || !panel.identity().equals(binding.controller()) || panel.side()!=binding.side() || panel.owner()==null) throw failure("gateway_unavailable");
        var owner=panel.owner();
        var geometry = com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterPhysicalPortAccess.resolve(level, member,
            new com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterPortBinding(owner.controllerId(), owner.getBlockPos(), owner.getBlockState().getValue(com.nobodiiiii.createbiotech.content.biocomputing.block.ClusterControllerBlock.FACING), binding.alias()));
        if(!panel.onShell(geometry)) throw failure("gateway_changed");
        unique(level, geometry, binding, member, port.name(), true);
        return new CableDiscovery.Connection(pos, binding.side());
    }
    private static void unique(ServerLevel level, ClusterEnclosure geometry, SerialGatewayBinding binding,
                               BlockPos member, String port, boolean transport) {
        for (var pos : geometry.members()) {
            if (!level.hasChunkAt(pos)) throw failure("gateway_unloaded");
            if (!(level.getBlockEntity(pos) instanceof SmartBlockEntity owner) || owner.isRemoved()) throw failure("gateway_unavailable");
            var computer = owner.getBehaviour(ComputerBehaviour.TYPE);
            if (computer == null) throw failure("gateway_unavailable");
            var view = computer.serialDevice().view();
            if (view.diagnostic().isPresent()) throw failure("gateway_unavailable");
            for (var item : view.ports().entrySet()) {
                if (pos.equals(member) && item.getKey().equals(port)) continue;
                var route = item.getValue().gateway();
                if (route.isPresent() && route.orElseThrow().controller().equals(binding.controller())
                    && route.orElseThrow().position().equals(binding.position())) {
                    if (transport) throw failure("gateway_conflict");
                    throw new IllegalArgumentException("gateway_conflict");
                }
            }
        }
    }
    private static CableDiscovery.DiscoveryException failure(String code) { return new CableDiscovery.DiscoveryException(code); }
}
