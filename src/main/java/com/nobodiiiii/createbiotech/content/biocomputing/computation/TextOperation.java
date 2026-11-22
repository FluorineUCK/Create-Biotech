package com.nobodiiiii.createbiotech.content.biocomputing.computation;

import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;
import java.util.*;

/** Literal text primitives; player indices count Unicode code points, never UTF-16 units. */
public enum TextOperation {
    LENGTH(Category.VALUE, INTEGER, "text", TEXT),
    CONCAT(Category.VALUE, TEXT, "left", TEXT, "right", TEXT),
    CHARACTER_AT(Category.VALUE, TEXT, "text", TEXT, "index", INTEGER),
    SUBSTRING(Category.VALUE, TEXT, "text", TEXT, "start", INTEGER, "end", INTEGER),
    EQUAL(Category.MATCH, BOOLEAN, "left", TEXT, "right", TEXT),
    COMPARE(Category.MATCH, INTEGER, "left", TEXT, "right", TEXT),
    INDEX_OF(Category.MATCH, INTEGER, "text", TEXT, "needle", TEXT),
    CONTAINS(Category.MATCH, BOOLEAN, "text", TEXT, "needle", TEXT),
    CONTAINS_IGNORE_CASE(Category.MATCH, BOOLEAN, "text", TEXT, "needle", TEXT),
    STARTS_WITH(Category.MATCH, BOOLEAN, "text", TEXT, "needle", TEXT),
    ENDS_WITH(Category.MATCH, BOOLEAN, "text", TEXT, "needle", TEXT),
    SPLIT(Category.EDIT, new DataType.ListType(TEXT), "text", TEXT, "delimiter", TEXT),
    JOIN(Category.VALUE, TEXT, "items", new DataType.ListType(TEXT), "separator", TEXT),
    REPLACE(Category.EDIT, TEXT, "text", TEXT, "needle", TEXT, "replacement", TEXT),
    LETTER_OF(Category.VALUE, TEXT, "text", TEXT, "index", INTEGER);

    public enum Category { VALUE, MATCH, EDIT }
    private final Category category;
    private final DataType output;
    private final Map<String,DataType> inputs;
    TextOperation(Category category,DataType output,Object... signature) {
        this.category=category;this.output=output;
        var names=new LinkedHashMap<String,DataType>();
        for(int i=0;i<signature.length;i+=2)names.put((String)signature[i],(DataType)signature[i+1]);
        inputs=Collections.unmodifiableMap(names);
    }
    public Category category(){return category;}
    /** Simple, locale-independent Unicode case folding shared by text search backends. */
    public static int foldCasePoint(int point){return Character.toLowerCase(Character.toUpperCase(point));}
    public Map<String,DataType> inputs(){return inputs;}
    public DataType outputType(Map<String,DataType> arguments,String location){
        requireNames(arguments,location);
        inputs.forEach((name,type)->{
            if(arguments.get(name)==null||!type.compatibleWith(arguments.get(name)))
                throw ProgramCompiler.failure(ComputationException.Code.TYPE_MISMATCH,location,"Invalid text input: "+name);
        });
        return output;
    }
    void requireNames(Map<?,?> arguments,String location){
        if(arguments==null||!arguments.keySet().equals(inputs.keySet()))
            throw ProgramCompiler.failure(ComputationException.Code.INVALID_GRAPH,location,"Text arguments do not match the operation signature");
    }
}
