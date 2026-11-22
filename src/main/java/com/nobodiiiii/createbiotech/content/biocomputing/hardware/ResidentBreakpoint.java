package com.nobodiiiii.createbiotech.content.biocomputing.hardware;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DiagnosticValueText;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialDevice;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import java.util.*;

/** A resident-owned one-shot latch, independent of application execution and receive FIFO lifetime. */
public final class ResidentBreakpoint {
    private String output = "", captured, outcome = "";
    private Tag opaque;
    // The saved panel text must fit one NBT string even in modified UTF-8.
    static final int MAX_CHARS = 20000;
    public boolean halted() { return captured != null || opaque != null; }
    public String output() { return output; }
    public String outcome() { return outcome; }
    public Optional<String> captured() { return Optional.ofNullable(captured); }
    public void select(String pin) {
        if (halted()) throw new IllegalArgumentException("breakpoint_halted");
        HardwarePins.validated(List.of(pin)); output = pin;
    }
    public void reset() { captured=null; opaque=null; outcome=""; }

    public boolean report(String status) {if(outcome.equals(status))return false;outcome=status;return true;}
    public boolean receiveBus(DataValue.RecordValue packet,SerialDevice serial,List<String> wired) {
        if(halted())return false;
        String text=DiagnosticValueText.render(packet,MAX_CHARS).orElse(null);
        if(text==null){captured="";outcome="frame_too_large";return true;}
        capture(text,serial,wired);return true;
    }
    private void capture(String message,SerialDevice serial,List<String> wired) {
        captured=message; outcome="no_output";
        if (!wired.contains(output)) return;
        var status=serial.status(output);
        if (!"ready".equals(status.connection())) { outcome=status.connection(); return; }
        var send=serial.prepareSend(output,new DataValue.RecordValue(Map.of("text",new DataValue.TextValue(message))));
        outcome=send.publish()?((DataValue.TextValue)send.result().field("status")).value():"changed";
    }
    public boolean sample(SerialDevice serial, List<String> wired) {
        if (halted()) return false;
        for (String pin : wired) {
            var head = serial.beginReceive(pin);
            if (head.error()!=null || head.head()==null) continue;
            var claim = serial.prepareReceive(pin);
            // An untyped line has no dialect bit. Prefer a valid typed envelope for display,
            // otherwise show the native NBT representation; undecodable frames remain exact hex.
            if(!(claim.result().field("ok") instanceof DataValue.BooleanValue yes && yes.value()))claim=serial.prepareReceive(pin,true);
            String message;
            if (claim.result().field("ok") instanceof DataValue.BooleanValue ok && ok.value()) {
                message = DiagnosticValueText.render(claim.result().field("value"),MAX_CHARS).orElse(null);
            } else message=null;
            if (message==null || message.length()>MAX_CHARS) {
                // Keep an oversized head intact for inspection; never pretend a truncated value was sent.
                if (head.head().size()>(MAX_CHARS-4)/2) {
                    captured=""; outcome="frame_too_large";return true;
                }
                var text=new StringBuilder("hex:");
                for (int i=0;i<head.head().size();i++) {
                    int v=head.head().byteAt(i);
                    text.append(Character.forDigit(v>>>4,16)).append(Character.forDigit(v&15,16));
                }
                message=text.toString();
                claim=serial.prepareDiscard(pin);
            }
            if (!claim.publish()) return false;
            capture(message,serial,wired);
            return true;
        }
        return false;
    }
    public Tag save() {
        if (opaque!=null) return opaque.copy();
        if (output.isEmpty() && captured==null) return null;
        var tag=new CompoundTag();tag.putInt("Version",1);tag.putString("Port",output);
        if (captured!=null) {tag.putString("Text",captured);tag.putString("Outcome",outcome);}
        return tag;
    }
    public void load(Tag raw) {
        reset();output="";
        if(raw==null)return;
        if(raw instanceof CompoundTag tag && tag.contains("Version",Tag.TAG_INT) && tag.getInt("Version")==1
                && tag.contains("Port",Tag.TAG_STRING)) {
            boolean set=tag.contains("Text",Tag.TAG_STRING);
            if(tag.getAllKeys().equals(set?Set.of("Version","Port","Text","Outcome"):Set.of("Version","Port"))
                    && tag.getString("Port").length()<=HardwarePins.MAX_NAME && (!set ||
                    tag.contains("Outcome",Tag.TAG_STRING) && tag.getString("Text").length()<=MAX_CHARS)) {
                String port=tag.getString("Port");try {if(!port.isEmpty())HardwarePins.validated(List.of(port));}
                catch (IllegalArgumentException bad){opaque=raw.copy();outcome="invalid_save";return;}
                output=port;if(set){captured=tag.getString("Text");outcome=tag.getString("Outcome");}return;
            }
        }
        opaque=raw.copy();outcome="invalid_save";
    }
}
