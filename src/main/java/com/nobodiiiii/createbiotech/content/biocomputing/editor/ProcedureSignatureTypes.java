package com.nobodiiiii.createbiotech.content.biocomputing.editor;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;

/** Signature choices are types, not new literal parsers. Arbitrary retained schemas stay explicit. */
final class ProcedureSignatureTypes {
    enum Choice { INTEGER,DECIMAL,TEXT,BOOLEAN,JSON,NBT,ANY,RECIPE,OPTIONAL_RECIPE,RECIPE_CHECK,RECIPE_V2,OPTIONAL_RECIPE_V2,RECIPE_CHECK_V2,REF_INTEGER_RW,REF_INTEGER_RO,REF_BOOLEAN_RW,REF_BOOLEAN_RO,REF_TEXT_RW,REF_TEXT_RO,REF_NBT_RW,REF_NBT_RO,RETAINED;
        boolean literal(){return ordinal()<=NBT.ordinal();}
        Choice next(){return this==RETAINED||this==REF_NBT_RO?INTEGER:values()[ordinal()+1];}
    }
    static final DataType OPTIONAL_RECIPE=new DataType.OptionalType(DataType.Semantic.RECIPE);
    static final DataType RECIPE_CHECK=new DataType.RecordType(Map.of("ok",DataType.Scalar.BOOLEAN,"value",OPTIONAL_RECIPE));
    static final DataType OPTIONAL_RECIPE_V2=new DataType.OptionalType(DataType.Semantic.RECIPE_V2);
    static final DataType RECIPE_CHECK_V2=new DataType.RecordType(Map.of("ok",DataType.Scalar.BOOLEAN,"value",OPTIONAL_RECIPE_V2));
    static Choice choice(DataType type){
        if(type instanceof DataType.RefType ref)return Choice.valueOf("REF_"+ref.target().name()+"_"+(ref.writable()?"RW":"RO"));
        if(type==DataType.Semantic.RECIPE)return Choice.RECIPE;
        if(type.equals(OPTIONAL_RECIPE))return Choice.OPTIONAL_RECIPE;
        if(type.equals(RECIPE_CHECK))return Choice.RECIPE_CHECK;
        if(type==DataType.Semantic.RECIPE_V2)return Choice.RECIPE_V2;
        if(type.equals(OPTIONAL_RECIPE_V2))return Choice.OPTIONAL_RECIPE_V2;
        if(type.equals(RECIPE_CHECK_V2))return Choice.RECIPE_CHECK_V2;
        if(type==DataType.Scalar.ANY)return Choice.ANY;
        for(var value:List.of(Choice.INTEGER,Choice.DECIMAL,Choice.TEXT,Choice.BOOLEAN,Choice.NBT))
            if(type==DataType.Scalar.valueOf(value.name()))return value;
        return Choice.RETAINED;
    }
    static DataType fixed(Choice choice){if(choice.name().startsWith("REF_")){var parts=choice.name().split("_");return new DataType.RefType(DataType.Scalar.valueOf(parts[1]),parts[2].equals("RW"));}return switch(choice){
        case ANY->DataType.Scalar.ANY;case RECIPE->DataType.Semantic.RECIPE;case OPTIONAL_RECIPE->OPTIONAL_RECIPE;
        case RECIPE_CHECK->RECIPE_CHECK;case RECIPE_V2->DataType.Semantic.RECIPE_V2;
        case OPTIONAL_RECIPE_V2->OPTIONAL_RECIPE_V2;case RECIPE_CHECK_V2->RECIPE_CHECK_V2;default->throw new IllegalArgumentException("Choice requires its retained schema or sample");
    };}
}
