package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.TypeArchiveCodec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bundled readable source templates. This is editor data, never a runtime dispatch table. */
final class FlowProcedureTemplates {
    private static final String ROOT="/assets/create_biotech/biocomputing/procedure_templates/";
    private static final class Loaded {
        static final List<FlowProgram.Procedure> VALUES=List.of(load("recipe_validate"),load("recipe_project"),load("scale_materials"),load("select_material"),load("text_next"));
    }
    static List<FlowProgram.Procedure> all(){return Loaded.VALUES;}
    private static String resource(String name){
        try(var input=FlowProcedureTemplates.class.getResourceAsStream(ROOT+name)){
            if(input==null)throw new IllegalArgumentException("Missing procedure template: "+name);
            var bytes=input.readNBytes(ProgramEditorWire.MAX_BYTES+1);
            if(bytes.length>ProgramEditorWire.MAX_BYTES)throw new IllegalArgumentException("Procedure template exceeds document limit");
            return new String(bytes,StandardCharsets.UTF_8);
        }catch(IOException invalid){throw new IllegalArgumentException("Cannot read procedure template",invalid);}
    }
    private static FlowProgram.Procedure load(String id){
        var data=(DataValue.RecordValue)EditorValueFormat.parse(EditorValueFormat.Kind.JSON,resource(id+".json"));
        if(!data.fields().keySet().equals(Set.of("id","revision","inputs","outputs","returns","assembly_version")))throw new IllegalArgumentException("Unexpected template fields");
        if(!((DataValue.TextValue)data.field("id")).value().equals(id))throw new IllegalArgumentException("Template identity mismatch");
        var inputs=types(data.field("inputs"));var outputs=types(data.field("outputs"));var returns=new LinkedHashMap<String,String>();
        ((DataValue.RecordValue)data.field("returns")).fields().forEach((key,value)->returns.put(key,((DataValue.TextValue)value).value()));
        var source=new AssemblySource(Math.toIntExact(((DataValue.IntegerValue)data.field("assembly_version")).value()),resource(id+".asm"));
        ProcedureAssembler.assemble(source,inputs,outputs,returns,ProgramEditorWire.MAX_BYTES,Long.MAX_VALUE);
        return new FlowProgram.Procedure("template_"+id,new ModuleDefinition.Key(id,((DataValue.IntegerValue)data.field("revision")).value()),
            inputs,outputs,returns,FlowBlockRules.NONE,Optional.of(source));
    }
    private static Map<String,DataType> types(DataValue value){
        var result=new LinkedHashMap<String,DataType>();
        ((DataValue.RecordValue)value).fields().forEach((key,type)->result.put(key,TypeArchiveCodec.decode(type)));
        return result;
    }
}
