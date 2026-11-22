package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import net.minecraft.nbt.Tag;
import java.util.*;

/** The trap's single physical XBus operation. Reuse the normal arbiter, saved receipts and
 * generation retirement, not a second network or an unbounded hardware mailbox. The user CPU
 * is off while this resident is installed. No editable program can run with the ROM's types. */
final class BreakpointBus {
    private static final DataType.RecordType TEXT = new DataType.RecordType(Map.of("text",DataType.Scalar.TEXT));
    private static final DataValue.RecordValue EMPTY = new DataValue.RecordValue(Map.of());
    final ComputerBlockHost host;
    private boolean saved, verified;
    private String pin, failure;
    private ComputerHostLimits failedWith;
    private FlowProgram read, send;
    BreakpointBus(Runnable dirty){ host=new ComputerBlockHost(dirty); }
    static ComputerHostLimits limits(ComputerHostLimits h) {
        return new ComputerHostLimits(h.hardware(),h.archive(),h.mailbox(),h.pins(),SerialHardware.NONE,1,true,true);
    }
    void load(Tag tag){host.read(tag);saved=tag!=null;verified=false;failure=null;pin=null;}
    Tag save(){return host.write();}
    String failure(){return failure;}
    boolean verified(){return verified;}
    boolean open(ComputerHostLimits h,ResidentBreakpoint latch) {
        var pins=h.pins().xbus();
        if(pins.isEmpty()){verified=false;return false;}
        String next=pins.getFirst();
        if(!next.equals(pin)){
            pin=next;verified=false;failure=null;
            read=new FlowProgram(Map.of(),List.of(new FlowProgram.Read("receive",pin,"capture",DataType.Scalar.ANY,false))).withAssembly();
            send=new FlowProgram(Map.of("packet",TEXT),List.of(new FlowProgram.Write("offer",pin,"packet"))).withAssembly();
        }
        if (failure != null && h.equals(failedWith)) return false;
        failure = null;
        var limits=limits(h);
        try{
            if(!saved){host.installFlow(read,EMPTY,limits,true);host.setPaused(false,limits,true);saved=true;}
            if(!host.synchronize(limits,true)) {verified=false;return false;}
            // Source and initial packet are immutable. Only load, pin change or our own ROM
            // transition can replace them. Don't reconstruct a captured packet every tick.
            if(verified)return true;
            var program=host.flowSnapshot().orElseThrow().program();
            if(!program.equals(read) && !program.equals(send))throw new IllegalArgumentException("invalid_breakpoint_rom");
            // An output continuation is only valid with the corresponding retained input and route.
            if(program.equals(send) && (!latch.halted() || !pin.equals(latch.output()) ||
                !host.flowSnapshot().orElseThrow().initialInputs().equals(packet(latch.captured().orElseThrow()))))
                throw new IllegalArgumentException("invalid_breakpoint_offer");
            verified=true;return true;
        }catch(IllegalArgumentException|ComputationException|NoSuchElementException|ComputerBlockHost.HostException invalid){
            verified=false;failure="breakpoint_rom_unavailable";failedWith=h;return false;
        }
    }
    private static DataValue.RecordValue packet(String text) {
        return new DataValue.RecordValue(Map.of("packet",new DataValue.RecordValue(Map.of("text",new DataValue.TextValue(text)))));
    }
    Optional<DataValue.RecordValue> input(){
        if(!verified)return Optional.empty();
        var s=host.flowSnapshot().orElseThrow();
        return s.program().equals(read) && s.state().status()==FlowRuntime.Status.COMPLETE && s.state().locals().fields().get("capture") instanceof DataValue.RecordValue value
            ?Optional.of(value):Optional.empty();
    }
    boolean canRun(ResidentBreakpoint latch) {
        if(!verified)return false;
        return !latch.halted() || host.flowSnapshot().filter(s->s.program().equals(send)).isPresent();
    }
    boolean output(ResidentBreakpoint latch,ComputerHostLimits h) {
        if (!verified || !latch.halted() || !pin.equals(latch.output()))return false;
        var current=host.flowSnapshot().orElseThrow();
        if(current.program().equals(send)) {
            if(current.state().status()==FlowRuntime.Status.COMPLETE)return latch.report("xbus_complete");
            if(current.state().status()==FlowRuntime.Status.FAILED)return latch.report("xbus_failed");
            return false;
        }
        if (!latch.outcome().equals("no_output") || latch.captured().isEmpty())return false;
        // One offer only. Never reinstall this WRITE after a rejection or after restoring it.
        var limits=limits(h);
        try{verified=false;host.reset(limits,true);host.installFlow(send,packet(latch.captured().orElseThrow()),limits,true);
            host.setPaused(false,limits,true);verified=true;return latch.report("xbus_waiting");
        }catch(IllegalArgumentException|ComputationException|ComputerBlockHost.HostException invalid){return latch.report("xbus_rejected");}
    }
    void rearm(ComputerHostLimits h,ResidentBreakpoint latch){
        if(!saved)return;
        if(!open(h,latch))throw new IllegalArgumentException("breakpoint_rom_unavailable");
        var limits=limits(h);
        verified=false;host.reset(limits,true);host.installFlow(read,EMPTY,limits,true);host.setPaused(false,limits,true);verified=true;
    }
}
