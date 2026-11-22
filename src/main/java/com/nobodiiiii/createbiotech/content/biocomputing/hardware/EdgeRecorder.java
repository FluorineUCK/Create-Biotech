package com.nobodiiiii.createbiotech.content.biocomputing.hardware;

import java.util.*;
import net.minecraft.nbt.*;

/** Passive SIO instrument. A missing sample breaks continuity, it never drives a pin or reads XBus. */
public final class EdgeRecorder {
    public static final int CHANNELS=4, DEPTH=64;
    public record Edge(long tick,String pin,int from,int to) {}
    private final List<Edge> trace=new ArrayList<>();
    private final Map<String,Integer> last=new HashMap<>();
    private List<String> pins=List.of();
    private boolean armed, overflow, invalid;
    private long lastTick=Long.MIN_VALUE;
    public boolean armed(){return armed;}
    public boolean overflow(){return overflow;}
    public boolean invalid(){return invalid;}
    public List<String> pins(){return pins;}
    public List<Edge> edges(){return List.copyOf(trace);}
    private static List<String> channels(List<String> pins){
        if(pins.isEmpty() || pins.size()>CHANNELS) throw new IllegalArgumentException("invalid_channels");
        return HardwarePins.validated(pins);
    }
    public void arm(List<String> values){pins=channels(values);trace.clear();last.clear();armed=true;overflow=false;invalid=false;}
    public void freeze(){armed=false;last.clear();}
    public void resume(){
        if(invalid || pins.isEmpty() || overflow)throw new IllegalArgumentException("start_new_capture");
        last.clear();armed=true;
    }
    public void clear(){freeze();trace.clear();pins=List.of();overflow=false;invalid=false;}
    public void gap(){last.clear();lastTick=Long.MIN_VALUE;}
    public boolean sample(long tick,Map<String,Integer> levels,int depth){
        if(!armed)return false;
        if(tick!=lastTick && tick-1!=lastTick)last.clear();
        lastTick=tick;
        boolean changed=false;
        for(String pin:pins){
            Integer n=levels.get(pin);
            if(n==null || n<0 || n>15){last.remove(pin);continue;}
            var prev=last.put(pin,n);
            if(prev==null || prev.equals(n))continue;
            if(trace.size()>=Math.min(depth,DEPTH)){overflow=true;freeze();return true;}
            trace.add(new Edge(tick,pin,prev,n));changed=true;
        }
        return changed;
    }
    public CompoundTag save(){
        if(pins.isEmpty() && !invalid)return null;
        var tag=new CompoundTag();tag.putInt("version",1);tag.putBoolean("armed",armed);
        tag.putBoolean("overflow",overflow);tag.putBoolean("invalid",invalid);
        var channels=new ListTag();pins.forEach(p->channels.add(StringTag.valueOf(p)));tag.put("pins",channels);
        var rows=new ListTag();
        for(var e:trace){var row=new CompoundTag();row.putLong("tick",e.tick);row.putString("pin",e.pin);row.putInt("from",e.from);row.putInt("to",e.to);rows.add(row);}
        tag.put("edges",rows);return tag;
    }
    public void load(Tag raw){
        clear();if(raw==null)return;
        try{
            if(!(raw instanceof CompoundTag t) || t.getInt("version")!=1 || t.getBoolean("invalid")
                || !(t.get("pins") instanceof ListTag ps) || ps.size()>CHANNELS || ps.getElementType()!=Tag.TAG_STRING
                || !(t.get("edges") instanceof ListTag rows) || rows.size()>DEPTH)throw new IllegalArgumentException();
            pins=channels(ps.stream().map(Tag::getAsString).toList());
            for(var item:rows){
                if(!(item instanceof CompoundTag row) || !row.contains("tick",Tag.TAG_LONG)
                    || !row.contains("from",Tag.TAG_INT) || !row.contains("to",Tag.TAG_INT))throw new IllegalArgumentException();
                var edge=new Edge(row.getLong("tick"),row.getString("pin"),row.getInt("from"),row.getInt("to"));
                if(!pins.contains(edge.pin)||edge.from<0||edge.from>15||edge.to<0||edge.to>15||edge.from==edge.to)throw new IllegalArgumentException();
                trace.add(edge);
            }
            overflow=t.getBoolean("overflow");armed=t.getBoolean("armed") && !overflow;
        }catch(IllegalArgumentException e){clear();invalid=true;}
    }
}
