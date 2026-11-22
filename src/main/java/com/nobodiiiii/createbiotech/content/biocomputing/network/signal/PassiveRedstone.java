package com.nobodiiiii.createbiotech.content.biocomputing.network.signal;

import com.nobodiiiii.createbiotech.content.biocomputing.block.DataCableBlock;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** Loaded cables and at most fourteen dust hops around their open contacts. Reconstruct dust
 * from independent sources: yesterday's dust POWER must never become today's battery. */
final class PassiveRedstone {
    // Cache only a fully observed circuit. Check world states and live emitter strengths before
    // reuse (modded block entities may change output without replacing their block state).
    static final class Cache {
        private PassiveRedstone circuit;
        private Map<BlockPos,Integer> drives=Map.of(),result=Map.of();
        Map<BlockPos,Integer> sample(ServerLevel level,Collection<BlockPos> cables,Map<BlockPos,Integer> next){
            if(circuit!=null && drives.equals(next) && circuit.unchanged())return result;
            circuit=new PassiveRedstone(level);drives=next;
            result=Map.copyOf(circuit.solve(cables,next));return result;
        }
        void invalidate(){circuit=null;}
    }
    private record Contact(BlockPos pos,Direction face){}
    private final Map<Contact,Integer> emitters=new HashMap<>();
    private boolean unchanged(){
        for(var entry:states.entrySet())if(current(entry.getKey())!=entry.getValue())return false;
        for(var entry:emitters.entrySet()){
            var c=entry.getKey();
            if(source(c.pos(),states.get(c.pos()),c.face())!=entry.getValue())return false;
        }
        return true;
    }
    private final ServerLevel level;
    private final Map<BlockPos,BlockState> states=new HashMap<>();
    private final Map<BlockPos,List<BlockPos>> edges=new HashMap<>();
    private final Map<BlockPos,Set<BlockPos>> strongDustOutputs=new HashMap<>();
    private final Map<BlockPos,Integer> values=new HashMap<>();
    private final ArrayDeque<BlockPos> frontier=new ArrayDeque<>();
    private PassiveRedstone(ServerLevel level){this.level=level;}
    private BlockState state(BlockPos p){
        return states.computeIfAbsent(p,this::current);
    }
    private BlockState current(BlockPos p){
        return level.isLoaded(p)?level.getBlockState(p):Blocks.AIR.defaultBlockState();
    }
    private static boolean dust(BlockState s){return s.is(Blocks.REDSTONE_WIRE);}
    private static boolean cable(BlockState s){return s.getBlock() instanceof DataCableBlock;}
    private static boolean open(BlockState s,Direction d){return s.getValue(DataCableBlock.connection(d));}
    private static boolean emitsToward(BlockState dust,Direction side){
        return switch(side){
            case DOWN -> true; case UP -> false;
            case NORTH -> dust.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.NORTH_REDSTONE).isConnected();
            case SOUTH -> dust.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.SOUTH_REDSTONE).isConnected();
            case WEST -> dust.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WEST_REDSTONE).isConnected();
            case EAST -> dust.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.EAST_REDSTONE).isConnected();
        };
    }
    private void offer(BlockPos p,int v){
        if(v>values.getOrDefault(p,0)){values.put(p,v);frontier.add(p);}
    }
    private int source(BlockPos p,BlockState s,Direction side){
        if(s.isAir() || dust(s) || cable(s))return 0;
        int v=s.getSignal(level,p,side);
        if(s.shouldCheckWeakPower(level,p,side)) for(var dir:Direction.values()){
            var n=p.relative(dir);var other=state(n);
            if(!dust(other)&&!cable(other))v=Math.max(v,other.getDirectSignal(level,n,dir));
        }
        return Math.min(15,v);
    }
    private Map<BlockPos,Integer> solve(Collection<BlockPos> cables,Map<BlockPos,Integer> drives){
        var distance=new HashMap<BlockPos,Integer>();var visit=new ArrayDeque<BlockPos>();
        for(var p:cables) if(cable(state(p))){visit.add(p); distance.put(p,0);}
        while(!visit.isEmpty()){
            var p=visit.remove();var s=state(p);boolean wire=cable(s); int steps=distance.get(p);
            var out=new ArrayList<BlockPos>();edges.put(p,out);int src=drives.getOrDefault(p,0);
            for(var dir:Direction.values()){
                if(wire&&!open(s,dir))continue;
                var n=p.relative(dir);var ns=state(n);
                if(cable(ns)){
                    if(open(ns,dir.getOpposite()) && (wire||emitsToward(s,dir)))out.add(n);
                    continue;
                }
                if(!dust(ns)&&!ns.isAir()){
                    int strength=source(n,ns,dir);
                    emitters.put(new Contact(n,dir),strength);src=Math.max(src,strength);
                    // Vanilla dust can strongly power a support block. Reconstruct this last hop
                    // from the independent dust solve, never from the support's old indirect power.
                    if(wire && ns.shouldCheckWeakPower(level,n,dir))for(var from:Direction.values()){
                        var at=n.relative(from);var candidate=state(at);
                        if(!dust(candidate)||!emitsToward(candidate,from.getOpposite()))continue;
                        strongDustOutputs.computeIfAbsent(at,k->new HashSet<>()).add(p);
                        if(distance.getOrDefault(at,16)>0){distance.put(at,0);visit.add(at);}
                    }
                }
                if(!wire && dir.getAxis().isVertical())continue;
                if(!wire && !dust(ns)){
                    if(ns.isRedstoneConductor(level,n)){
                        if(state(p.above()).isRedstoneConductor(level,p.above()))continue;
                        n=n.above();
                    }else n=n.below();
                    ns=state(n);
                }
                if(!dust(ns))continue;
                out.add(n);
                int next=wire?0:steps+1;
                if(next<15 && next<distance.getOrDefault(n,16)){distance.put(n,next);visit.add(n);}
            }
            offer(p,src);
        }
        strongDustOutputs.forEach((dust,outputs)->edges.get(dust).addAll(outputs));
        while(!frontier.isEmpty()){
            var p=frontier.remove();var next=edges.get(p);if(next==null)continue;
            for(var n:next)offer(n,values.get(p)-(dust(state(p))&&dust(state(n))?1:0));
        }
        var result=new HashMap<BlockPos,Integer>();
        for(var p:cables)if(values.containsKey(p))result.put(p,values.get(p));
        return result;
    }
}
