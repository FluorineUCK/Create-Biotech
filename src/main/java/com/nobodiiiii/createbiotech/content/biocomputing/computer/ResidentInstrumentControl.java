package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import java.util.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue.*;

final class ResidentInstrumentControl {
    private ResidentInstrumentControl(){}
    private static ListValue names(List<String> pins){
        return new ListValue(pins.stream().<DataValue>map(TextValue::new).toList());
    }
    static RecordValue status(String model,HardwarePins pins,RecordValue state){
        return new RecordValue(Map.of("instrument",new TextValue(model),"xbus",names(pins.xbus()),"sio",names(pins.simpleIo()),"details",state));
    }
    static RecordValue analyzerStatus(EdgeRecorder scope){
        return new RecordValue(Map.of("state",new TextValue(scope.invalid()?"invalid_save":scope.overflow()?"overflow":scope.armed()?"recording":scope.pins().isEmpty()?"idle":"frozen"),
            "pins",names(scope.pins()),"edges",new ListValue(scope.edges().stream().<DataValue>map(e->new RecordValue(Map.of(
            "tick",new IntegerValue(e.tick()),"pin",new TextValue(e.pin()),"from",new IntegerValue(e.from()),"to",new IntegerValue(e.to())))).toList())));
    }
    static void configureAnalyzer(EdgeRecorder scope,HardwarePins hardware,RecordValue request){
        if(!(request.fields().get("op") instanceof TextValue op))throw new IllegalArgumentException("invalid_operation");
        if(op.value().equals("start")){
            if(!request.fields().keySet().equals(Set.of("op","pins")) || !(request.fields().get("pins") instanceof ListValue list)
                || list.values().size()>EdgeRecorder.CHANNELS)throw new IllegalArgumentException("invalid_channels");
            var pins=new ArrayList<String>();
            for(var v:list.values()){
                if(!(v instanceof TextValue pin) || !hardware.hasSimpleIo(pin.value()))throw new IllegalArgumentException("missing_pin");
                pins.add(pin.value());
            }
            scope.arm(pins);return;
        }
        if(request.fields().size()!=1)throw new IllegalArgumentException("invalid_operation");
        switch(op.value()){
            case "freeze"->scope.freeze();
            case "resume"->scope.resume();
            case "clear"->scope.clear();
            default->throw new IllegalArgumentException("invalid_operation");
        }
    }
}
