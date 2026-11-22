package com.nobodiiiii.createbiotech.content.biocomputing.computation;

import static com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationException.Code.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.ProgramCompiler.failure;
import java.util.*;
import java.util.function.LongConsumer;

/**
 * A pure, resumable literal-text continuation. An advance performs one comparison,
 * one code-point append, one candidate movement, or one phase/list boundary.
 * Frozen inputs are borrowed; only retained output is included in liveUnits.
 */
public final class TextTask {
    private enum Phase { SCAN, SCAN_DONE, CONCAT_LEFT, CONCAT_RIGHT, CONCAT_DONE,
        COMPARE, COMPARE_DONE, SEARCH, MOVE, EMIT, SEARCH_DONE,
        JOIN_ITEM, JOIN_COPY, JOIN_SEPARATOR, JOIN_DONE }
    private final TextOperation operation;
    private final Map<String,DataValue> arguments;
    private final String location;
    private Phase phase;
    private int offset, other, candidate, copy, item;
    private long index, matched;
    private int order;
    private final StringBuilder buffer=new StringBuilder();
    private final ArrayList<DataValue> segments=new ArrayList<>();

    private TextTask(TextOperation operation,Map<String,DataValue> arguments,String location){
        this.operation=Objects.requireNonNull(operation);this.location=Objects.requireNonNull(location);
        operation.requireNames(arguments,location);
        // LIST<TEXT> is intentionally not traversed here: JOIN consumes dynamic items individually.
        operation.inputs().forEach((name,type)->{
            DataValue value=arguments.get(name);
            if(type instanceof DataType.ListType ? !(value instanceof DataValue.ListValue) : value==null||!type.accepts(value))
                throw failure(TYPE_MISMATCH,location,"Invalid text input: "+name);
        });
        this.arguments=Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
        if(operation==TextOperation.SPLIT&&text("delimiter").isEmpty()
            ||operation==TextOperation.REPLACE&&text("needle").isEmpty())
            throw failure(INVALID_ARGUMENT,location,"Literal edit patterns must not be empty");
        if(operation==TextOperation.CHARACTER_AT&&number("index")<0
            ||operation==TextOperation.SUBSTRING&&(number("start")<0||number("end")<number("start")))
            throw failure(INDEX_OUT_OF_BOUNDS,location,"Invalid code-point index range");
        phase=switch(operation){
            case LENGTH,CHARACTER_AT,SUBSTRING,LETTER_OF->Phase.SCAN;
            case CONCAT->Phase.CONCAT_LEFT;
            case EQUAL,COMPARE,ENDS_WITH->Phase.COMPARE;
            case JOIN->Phase.JOIN_ITEM;
            default->Phase.SEARCH;
        };
        if(operation==TextOperation.ENDS_WITH){offset=text("text").length();other=text("needle").length();}
    }
    public static TextTask start(TextOperation operation,Map<String,DataValue> arguments,String location,LongConsumer reserve){
        var task=new TextTask(operation,arguments,location);
        reserve.accept(operation==TextOperation.SPLIT?2:1);
        return task;
    }
    public boolean complete(){return switch(phase){case SCAN_DONE,CONCAT_DONE,COMPARE_DONE,SEARCH_DONE,JOIN_DONE->true;default->false;};}
    public void advance(LongConsumer reserve){
        Objects.requireNonNull(reserve);
        switch(phase){
            case SCAN->scan(reserve);
            case CONCAT_LEFT,CONCAT_RIGHT->concat(reserve);
            case COMPARE->compare();
            case SEARCH->search(reserve);
            case MOVE->move(reserve);
            case EMIT->emit(reserve);
            case JOIN_ITEM,JOIN_COPY,JOIN_SEPARATOR->join(reserve);
            default->{}
        }
    }
    private void scan(LongConsumer reserve){
        String source=text("text");
        if(operation==TextOperation.LETTER_OF){
            long target=number("index");
            if(target<=0||offset==source.length()){phase=Phase.SCAN_DONE;return;}
            int cp=source.codePointAt(offset);
            if(index+1==target){append(cp,reserve);phase=Phase.SCAN_DONE;}
            offset+=Character.charCount(cp);index++;
            return;
        }
        if(offset==source.length()){
            if(operation==TextOperation.CHARACTER_AT&&index<=number("index")
                ||operation==TextOperation.SUBSTRING&&index<number("end"))
                throw failure(INDEX_OUT_OF_BOUNDS,location,"Code-point index exceeds text length");
            phase=Phase.SCAN_DONE;return;
        }
        int cp=source.codePointAt(offset);
        if(operation==TextOperation.CHARACTER_AT&&index==number("index")
            ||operation==TextOperation.SUBSTRING&&index>=number("start")&&index<number("end"))append(cp,reserve);
        offset+=Character.charCount(cp);index++;
    }
    private void concat(LongConsumer reserve){
        String source=text(phase==Phase.CONCAT_LEFT?"left":"right");
        if(offset==source.length()){
            if(phase==Phase.CONCAT_LEFT){offset=0;phase=Phase.CONCAT_RIGHT;}else phase=Phase.CONCAT_DONE;
            return;
        }
        int cp=source.codePointAt(offset);append(cp,reserve);offset+=Character.charCount(cp);
    }
    private void compare(){
        boolean backwards=operation==TextOperation.ENDS_WITH;
        String left=text(backwards?"text":"left"),right=text(backwards?"needle":"right");
        if(backwards){
            if(other==0||offset==0){order=other==0?0:-1;phase=Phase.COMPARE_DONE;return;}
            int a=left.codePointBefore(offset),b=right.codePointBefore(other);
            if(a!=b){order=Integer.compare(a,b);phase=Phase.COMPARE_DONE;}
            else{offset-=Character.charCount(a);other-=Character.charCount(b);}
        }else{
            if(offset==left.length()||other==right.length()){
                order=Integer.compare(left.length()-offset,right.length()-other);phase=Phase.COMPARE_DONE;return;
            }
            int a=left.codePointAt(offset),b=right.codePointAt(other);
            if(a!=b){order=Integer.compare(a,b);phase=Phase.COMPARE_DONE;}
            else{offset+=Character.charCount(a);other+=Character.charCount(b);}
        }
    }
    private String needle(){return text(operation==TextOperation.SPLIT?"delimiter":"needle");}
    private boolean editing(){return operation==TextOperation.SPLIT||operation==TextOperation.REPLACE;}
    private void search(LongConsumer reserve){
        String source=text("text"),pattern=needle();
        if(other==pattern.length()){
            if(operation==TextOperation.SPLIT){
                // The next empty segment is charged before changing the current segment.
                reserve.accept(1);segments.add(new DataValue.TextValue(buffer.toString()));buffer.setLength(0);
                candidate=offset;index+=matched;other=0;matched=0;
            }else if(operation==TextOperation.REPLACE){copy=0;phase=Phase.EMIT;}
            else{order=1;phase=Phase.SEARCH_DONE;}
            return;
        }
        if(offset==source.length()){phase=Phase.MOVE;return;}
        int a=source.codePointAt(offset),b=pattern.codePointAt(other);
        boolean equal=operation==TextOperation.CONTAINS_IGNORE_CASE?TextOperation.foldCasePoint(a)==TextOperation.foldCasePoint(b):a==b;
        if(equal){offset+=Character.charCount(a);other+=Character.charCount(b);matched++;}
        else phase=Phase.MOVE;
    }
    private void move(LongConsumer reserve){
        String source=text("text");
        if(operation==TextOperation.STARTS_WITH||candidate==source.length()){
            if(operation==TextOperation.SPLIT){segments.add(new DataValue.TextValue(buffer.toString()));buffer.setLength(0);}
            order=0;phase=Phase.SEARCH_DONE;return;
        }
        int cp=source.codePointAt(candidate);
        if(editing())append(cp,reserve);
        candidate+=Character.charCount(cp);index++;offset=candidate;other=0;matched=0;phase=Phase.SEARCH;
    }
    private void emit(LongConsumer reserve){
        String replacement=text("replacement");
        if(copy==replacement.length()){
            candidate=offset;index+=matched;other=0;matched=0;copy=0;phase=Phase.SEARCH;return;
        }
        int cp=replacement.codePointAt(copy);append(cp,reserve);copy+=Character.charCount(cp);
    }
    private void join(LongConsumer reserve){
        var items=((DataValue.ListValue)arguments.get("items")).values();
        if(phase==Phase.JOIN_ITEM){
            if(item==items.size()){phase=Phase.JOIN_DONE;return;}
            if(!(items.get(item) instanceof DataValue.TextValue))throw failure(TYPE_MISMATCH,location,"JOIN item is not text");
            offset=0;phase=Phase.JOIN_COPY;return;
        }
        String source=phase==Phase.JOIN_COPY?((DataValue.TextValue)items.get(item)).value():text("separator");
        if(offset==source.length()){
            offset=0;
            if(phase==Phase.JOIN_COPY){item++;phase=item==items.size()?Phase.JOIN_ITEM:Phase.JOIN_SEPARATOR;}
            else phase=Phase.JOIN_ITEM;
            return;
        }
        int cp=source.codePointAt(offset);append(cp,reserve);offset+=Character.charCount(cp);
    }
    private void append(int cp,LongConsumer reserve){reserve.accept(Character.charCount(cp));buffer.appendCodePoint(cp);}
    private String text(String name){return ((DataValue.TextValue)arguments.get(name)).value();}
    private long number(String name){return ((DataValue.IntegerValue)arguments.get(name)).value();}
    public DataValue result(){
        if(!complete())throw new IllegalStateException("Text operation has not completed");
        return switch(operation){
            case LENGTH->new DataValue.IntegerValue(index);
            case EQUAL,ENDS_WITH->new DataValue.BooleanValue(order==0);
            case COMPARE->new DataValue.IntegerValue(order);
            case INDEX_OF->new DataValue.IntegerValue(order==1?index:-1);
            case CONTAINS,CONTAINS_IGNORE_CASE,STARTS_WITH->new DataValue.BooleanValue(order==1);
            case SPLIT->new DataValue.ListValue(segments);
            default->new DataValue.TextValue(buffer.toString());
        };
    }
    public long liveUnits(){
        if(operation!=TextOperation.SPLIT)return 1L+buffer.length();
        long units=complete()?1:2L+buffer.length();
        for(var segment:segments)units=Math.addExact(units,segment.storageUnits());
        return units;
    }

    /** Closed schemas are selected by the operation family; phase must belong to that family. */
    public DataValue.RecordValue checkpoint(){
        var fields=new LinkedHashMap<String,DataValue>();put(fields,"operation",operation.name());put(fields,"phase",phase.name());
        switch(operation){
            case LENGTH,CHARACTER_AT,SUBSTRING,LETTER_OF->{put(fields,"offset",offset);put(fields,"index",index);if(operation!=TextOperation.LENGTH)put(fields,"buffer",buffer.toString());}
            case CONCAT->{put(fields,"offset",offset);put(fields,"buffer",buffer.toString());}
            case EQUAL,COMPARE,ENDS_WITH->{put(fields,"offset",offset);put(fields,"other",other);put(fields,"order",order);}
            case JOIN->{put(fields,"item",item);put(fields,"offset",offset);put(fields,"buffer",buffer.toString());}
            default->{
                put(fields,"candidate",candidate);put(fields,"index",index);put(fields,"offset",offset);
                put(fields,"other",other);put(fields,"matched",matched);put(fields,"order",order);
                if(editing())put(fields,"buffer",buffer.toString());
                if(operation==TextOperation.REPLACE)put(fields,"copy",copy);
                if(operation==TextOperation.SPLIT)fields.put("segments",new DataValue.ListValue(segments));
            }
        }
        return new DataValue.RecordValue(fields);
    }
    public static TextTask restore(TextOperation operation,Map<String,DataValue> arguments,DataValue.RecordValue progress,String location){
        try{
            var task=new TextTask(operation,arguments,location);
            require(progress.fields().keySet().equals(task.checkpoint().fields().keySet()),"Unexpected progress fields");
            require(str(progress,"operation").equals(operation.name()),"Progress belongs to another operation");
            task.phase=Phase.valueOf(str(progress,"phase"));
            for(String key:List.of("offset","other","candidate","copy","item")){
                if(!progress.fields().containsKey(key))continue;
                long n=num(progress,key);require(n>=0&&n<=Integer.MAX_VALUE,"Invalid "+key);
                switch(key){case "offset"->task.offset=(int)n;case "other"->task.other=(int)n;case "candidate"->task.candidate=(int)n;case "copy"->task.copy=(int)n;case "item"->task.item=(int)n;}
            }
            if(progress.fields().containsKey("index"))task.index=num(progress,"index");
            if(progress.fields().containsKey("matched"))task.matched=num(progress,"matched");
            if(progress.fields().containsKey("order")){long n=num(progress,"order");require(n>=-1&&n<=1,"Invalid comparison order");task.order=(int)n;}
            if(progress.fields().containsKey("buffer"))task.buffer.append(str(progress,"buffer"));
            if(progress.fields().containsKey("segments")){
                require(progress.field("segments") instanceof DataValue.ListValue,"Invalid split segments");
                for(var segment:((DataValue.ListValue)progress.field("segments")).values()){
                    require(segment instanceof DataValue.TextValue,"Non-text split segment");task.segments.add(segment);
                }
            }
            task.validate();return task;
        }catch(ComputationException e){if(e.code()==INVALID_CHECKPOINT)throw e;throw failure(INVALID_CHECKPOINT,location,e.getMessage());}
        catch(IllegalArgumentException|ClassCastException e){throw failure(INVALID_CHECKPOINT,location,"Invalid text progress: "+e.getMessage());}
    }
    private void validate(){
        switch(operation){
            case LETTER_OF->{
                String source=text("text");long target=number("index");
                require(phase==Phase.SCAN||phase==Phase.SCAN_DONE,"Invalid letter phase");
                boundary(source,offset);
                require(index==source.codePointCount(0,offset),"Invalid letter cursor");
                if(phase==Phase.SCAN)require(buffer.isEmpty()&&(target>index||offset==0&&target<=0),"Invalid pending letter");
                else if(target>0&&index==target){
                    int start=offset-Character.charCount(source.codePointBefore(offset));
                    require(buffer.toString().equals(source.substring(start,offset)),"Letter differs from source");
                }else require(buffer.isEmpty()&&(target<=0?offset==0:offset==source.length()&&index<target),"Invalid absent letter");
            }
            case LENGTH,CHARACTER_AT,SUBSTRING->{
                require(phase==Phase.SCAN||phase==Phase.SCAN_DONE,"Invalid scan phase");
                boundary(text("text"),offset);count(index,offset);
                require(phase!=Phase.SCAN_DONE||offset==text("text").length(),"Unfinished scan");
                if(operation==TextOperation.CHARACTER_AT){
                    long target=number("index");
                    require(index>target?!buffer.isEmpty()&&buffer.length()==Character.charCount(buffer.codePointAt(0)):buffer.isEmpty(),"Invalid character progress");
                    require(phase!=Phase.SCAN_DONE||index>target,"Completed out-of-range character");
                }else if(operation==TextOperation.SUBSTRING){
                    long copied=Math.max(0,Math.min(index,number("end"))-number("start"));count(copied,buffer.length());
                    require(phase!=Phase.SCAN_DONE||index>=number("end"),"Completed out-of-range substring");
                }
            }
            case CONCAT->{
                require(phase==Phase.CONCAT_LEFT||phase==Phase.CONCAT_RIGHT||phase==Phase.CONCAT_DONE,"Invalid concat phase");
                boundary(text(phase==Phase.CONCAT_LEFT?"left":"right"),offset);
                require(buffer.length()==(phase==Phase.CONCAT_LEFT?offset:(long)text("left").length()+offset),"Invalid concat buffer size");
                require(phase!=Phase.CONCAT_DONE||offset==text("right").length(),"Unfinished concat");
            }
            case EQUAL,COMPARE,ENDS_WITH->validateCompare();
            case JOIN->{
                require(phase==Phase.JOIN_ITEM||phase==Phase.JOIN_COPY||phase==Phase.JOIN_SEPARATOR||phase==Phase.JOIN_DONE,"Invalid join phase");
                var items=((DataValue.ListValue)arguments.get("items")).values();require(item<=items.size(),"Invalid join item");
                if(phase==Phase.JOIN_COPY){
                    require(item<items.size()&&items.get(item) instanceof DataValue.TextValue,"Invalid active join item");
                    boundary(((DataValue.TextValue)items.get(item)).value(),offset);
                }else if(phase==Phase.JOIN_SEPARATOR){require(item>0&&item<items.size(),"Impossible separator");boundary(text("separator"),offset);}
                else require(offset==0,"Inactive join cursor");
                require(phase!=Phase.JOIN_DONE||item==items.size(),"Unfinished join");
                require(item!=0||phase==Phase.JOIN_COPY||buffer.isEmpty(),"Invalid initial join buffer");
                if(item==0&&phase==Phase.JOIN_COPY)require(buffer.length()==offset,"Invalid initial join copy");
            }
            default->validateSearch();
        }
    }
    private void validateCompare(){
        require(phase==Phase.COMPARE||phase==Phase.COMPARE_DONE,"Invalid compare phase");
        boolean backwards=operation==TextOperation.ENDS_WITH;
        String a=text(backwards?"text":"left"),b=text(backwards?"needle":"right");
        boundary(a,offset);boundary(b,other);
        require(backwards?a.length()-offset==b.length()-other:offset==other,"Comparison cursors did not advance together");
        if(!complete()){require(order==0,"Unfinished comparison has result");return;}
        int expected;
        if(backwards)expected=other==0?0:offset==0?-1:Integer.compare(a.codePointBefore(offset),b.codePointBefore(other));
        else expected=offset==a.length()||other==b.length()?Integer.compare(a.length()-offset,b.length()-other):Integer.compare(a.codePointAt(offset),b.codePointAt(other));
        require(order==expected,"Comparison result does not match terminal cursors");
        require(backwards?other==0||offset==0||expected!=0:offset==a.length()||other==b.length()||expected!=0,"Premature compare completion");
    }
    private void validateSearch(){
        require(phase==Phase.SEARCH||phase==Phase.MOVE||phase==Phase.SEARCH_DONE||operation==TextOperation.REPLACE&&phase==Phase.EMIT,"Invalid search phase");
        String source=text("text"),pattern=needle();boundary(source,candidate);boundary(source,offset);boundary(pattern,other);
        if(pattern.isEmpty())
            require(candidate==0&&offset==0&&index==0&&other==0&&matched==0,"Empty pattern must match the initial candidate");
        require(offset>=candidate,"Match cursor precedes candidate");count(index,candidate);count(matched,offset-candidate);count(matched,other);
        require(order>=0,"Invalid search result");
        require(phase==Phase.SEARCH_DONE||order==0,"Unfinished search has result");
        if(operation==TextOperation.STARTS_WITH)require(candidate==0&&index==0,"Prefix candidate moved");
        if(phase==Phase.EMIT){require(other==pattern.length(),"Replacement before match");boundary(text("replacement"),copy);}
        else if(operation==TextOperation.REPLACE)require(copy==0,"Inactive replacement cursor");
        if(complete()){
            if(order==1)require(!editing()&&other==pattern.length(),"Impossible found result");
            else {
                require(!pattern.isEmpty(),"Empty search pattern cannot fail");
                require(operation==TextOperation.STARTS_WITH
                    ? mismatch(source,pattern)
                    : candidate==source.length()&&other==0&&matched==0,"Unfinished search exhaustion");
            }
            if(operation==TextOperation.SPLIT)require(buffer.isEmpty()&&!segments.isEmpty(),"Invalid final split segment");
        }
        if(phase==Phase.MOVE)require(mismatch(source,pattern),"Moving without mismatch or source exhaustion");
        if(operation==TextOperation.SPLIT){
            long retained=buffer.length();for(var segment:segments)retained+=((DataValue.TextValue)segment).value().length();
            long delimiters=segments.size()-(complete()?1:0);
            require(retained+delimiters*pattern.length()==candidate,"Split output does not match consumed prefix size");
        }else if(operation==TextOperation.REPLACE&&candidate==0)
            require(buffer.length()==(phase==Phase.EMIT?copy:0),"Invalid initial replacement buffer");
    }
    /** A fixed, local witness only: this never replays the already matched prefix. */
    private boolean mismatch(String source,String pattern){
        if(other==pattern.length())return false;
        if(offset==source.length())return true;
        int a=source.codePointAt(offset),b=pattern.codePointAt(other);
        return operation==TextOperation.CONTAINS_IGNORE_CASE?TextOperation.foldCasePoint(a)!=TextOperation.foldCasePoint(b):a!=b;
    }
    private static void count(long codepoints,long units){require(codepoints>=0&&codepoints<=units&&units<=2*codepoints,"Invalid code-point count");}
    private static void boundary(String text,int cursor){require(cursor>=0&&cursor<=text.length(),"Cursor outside source");require(cursor==0||cursor==text.length()||!Character.isHighSurrogate(text.charAt(cursor-1))||!Character.isLowSurrogate(text.charAt(cursor)),"Cursor splits a surrogate pair");}
    private static void require(boolean condition,String message){if(!condition)throw failure(INVALID_CHECKPOINT,"text",message);}
    private static String str(DataValue.RecordValue value,String key){require(value.field(key) instanceof DataValue.TextValue,"Expected text field "+key);return ((DataValue.TextValue)value.field(key)).value();}
    private static long num(DataValue.RecordValue value,String key){require(value.field(key) instanceof DataValue.IntegerValue,"Expected integer field "+key);return ((DataValue.IntegerValue)value.field(key)).value();}
    private static void put(Map<String,DataValue> fields,String key,String value){fields.put(key,new DataValue.TextValue(value));}
    private static void put(Map<String,DataValue> fields,String key,long value){fields.put(key,new DataValue.IntegerValue(value));}
}
