package com.nobodiiiii.createbiotech.content.biocomputing.computation;

import java.util.*;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Closed, immutable data snapshots. No value can carry an executable or a world-object handle. */
public sealed interface DataValue {
	long storageUnits();
    /** Cached capability occurrence count. Portable boundaries never need to walk a payload. */
    default long localReferences() { return 0; }
    record LocalReference(UUID computer,long generation,String image,UUID context,int cell,DataType.RefType type) implements DataValue {
        public LocalReference { Objects.requireNonNull(computer);Objects.requireNonNull(image);Objects.requireNonNull(context);Objects.requireNonNull(type);if(generation<0||cell<0||!image.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid local reference identity"); }
        public long localReferences(){return 1;}
        /** Frozen ref-1 layout: record header, keys, UUID texts, image text, integer fields, target and permission. */
        public RecordValue descriptor(){return new RecordValue(Map.of("computer",new TextValue(computer.toString()),"generation",new IntegerValue(generation),"image",new TextValue(image),"context",new TextValue(context.toString()),"cell",new IntegerValue(cell),"target",new TextValue(type.target().name()),"writable",new BooleanValue(type.writable())));}
        public long storageUnits(){return descriptor().storageUnits();}
        public LocalReference readonly(){return new LocalReference(computer,generation,image,context,cell,new DataType.RefType(type.target(),false));}
        public boolean sameTarget(LocalReference other){return computer.equals(other.computer)&&generation==other.generation&&image.equals(other.image)&&context.equals(other.context)&&cell==other.cell;}
        @Override public String toString(){return "LocalReference["+type.target()+","+(type.writable()?"RW":"RO")+"]";}
    }

    /** Nominal versioned recipe snapshot; source metadata conveys no authority. */
    final class RecipeValue implements DataValue {
        public static final String FORMAT = "recipe";
        public static final int VERSION = 1;
        private final RecordValue projection;
        private final int version;
        public RecipeValue(String fmt, int version, RecordValue projection) {
            var proof = RecipeValidation.begin(fmt, version, projection);
            while (!proof.done()) proof = proof.advance();
            this.projection = proof.projection();
            this.version = proof.version();
        }
        RecipeValue(RecipeValidation proof) {
            this.projection = proof.projection();
            this.version = proof.version();
        }
        public String fmt() { return FORMAT; }
        public int version() { return version; }
        public RecordValue projection() { return projection; }
        public long storageUnits() { return addUnits(2L + FORMAT.length(), projection.storageUnits()); }
        @Override public boolean equals(Object other) { return other instanceof RecipeValue r && ValueTraversal.equal(this, r); }
        @Override public int hashCode() { return ValueTraversal.hash(this); }
        @Override public String toString() { return "RecipeValue[recipe@" + version + "]"; }
    }

	/** Native tag data only. Numeric tag IDs are NBT format constants, not Java/world handles.
	 * Floating payloads are raw bits so non-finite native numbers need no Decimal coercion. */
	final class NbtValue implements DataValue {
		private final int tagId;
		private final DataValue value;
		private NbtValue(int tagId, DataValue value, NativeValidation proof) {
            if (!proof.done() || proof.tag != tagId || proof.body != value) throw new IllegalArgumentException("Invalid native proof");
            this.tagId=tagId; this.value=value;
        }
		public NbtValue(int tagId, DataValue value) {
			this.tagId = tagId; this.value = Objects.requireNonNull(value);
			boolean valid = switch (tagId) {
				case 0 -> value == NullValue.INSTANCE;
				case 1 -> integerRange(value, Byte.MIN_VALUE, Byte.MAX_VALUE);
				case 2 -> integerRange(value, Short.MIN_VALUE, Short.MAX_VALUE);
				case 3, 5 -> integerRange(value, Integer.MIN_VALUE, Integer.MAX_VALUE);
				case 4, 6 -> value instanceof IntegerValue;
				case 7, 11, 12 -> value instanceof ListValue list && list.values().stream().allMatch(v ->
					integerRange(v, tagId == 7 ? Byte.MIN_VALUE : tagId == 11 ? Integer.MIN_VALUE : Long.MIN_VALUE,
						tagId == 7 ? Byte.MAX_VALUE : tagId == 11 ? Integer.MAX_VALUE : Long.MAX_VALUE));
				case 8 -> value instanceof TextValue;
				case 9 -> value instanceof ListValue list && list.values().stream().allMatch(v ->
					v instanceof NbtValue n && n.tagId != 0 && n.tagId == ((NbtValue)list.values().getFirst()).tagId);
				case 10 -> value instanceof RecordValue record && record.fields().values().stream().allMatch(v -> v instanceof NbtValue n && n.tagId != 0);
				default -> false;
			};
			if (!valid) throw new IllegalArgumentException("Invalid native NBT value");
		}
		private static boolean integerRange(DataValue value, long min, long max) {
			return value instanceof IntegerValue n && n.value() >= min && n.value() <= max;
		}
		public int tagId() { return tagId; }
		public DataValue value() { return value; }
		public long storageUnits() { return addUnits(1, value.storageUnits()); }
		@Override public boolean equals(Object other) { return other instanceof NbtValue n && ValueTraversal.equal(this, n); }
		@Override public int hashCode() { return ValueTraversal.hash(this); }
		@Override public String toString() { return "NbtValue[tag=" + tagId + "]"; }
	}

	record BooleanValue(boolean value) implements DataValue {
		public long storageUnits() { return 1; }
	}
	record IntegerValue(long value) implements DataValue {
		public long storageUnits() { return 1; }
	}
	record DecimalValue(double value) implements DataValue {
		public DecimalValue {
			if (!Double.isFinite(value))
				throw new IllegalArgumentException("Decimal must be finite");
		}
		public long storageUnits() { return 1; }
	}
	record TextValue(String value) implements DataValue {
		public TextValue { Objects.requireNonNull(value); }
		public long storageUnits() { return 1L + value.length(); }
	}
	enum NullValue implements DataValue {
		INSTANCE;
		public long storageUnits() { return 1; }
	}
	record ErrorValue(String code, String message) implements DataValue {
		public ErrorValue {
			Objects.requireNonNull(code);
			Objects.requireNonNull(message);
		}
		public long storageUnits() { return 1L + code.length() + message.length(); }
	}
	final class ListValue implements DataValue {
		private final List<DataValue> values;
		private final long units;
        private final long localReferences;
		public ListValue(List<DataValue> values) {
			this.values = List.copyOf(values);
			long size = 1;
			for (DataValue child : this.values) size = addUnits(size, child.storageUnits());
			this.units = size;
            this.localReferences=this.values.stream().mapToLong(DataValue::localReferences).reduce(0,Math::addExact);
		}
        private ListValue(Construction prefix) {
            this.values = Collections.unmodifiableList(prefix.values);
            this.units = prefix.units; this.localReferences=prefix.localReferences;
        }
		public List<DataValue> values() { return values; }
		public long storageUnits() { return units; }
        public long localReferences() { return localReferences; }
		@Override public boolean equals(Object other) {
			return other instanceof ListValue value && ValueTraversal.equal(this, value);
		}
		@Override public int hashCode() { return ValueTraversal.hash(this); }
		@Override public String toString() { return "ListValue[size=" + values.size() + "]"; }
	}
	final class RecordValue implements DataValue {
		private final Map<String, DataValue> fields;
		private final List<Map.Entry<String, DataValue>> indexedFields;
		private final long units;
        private final long localReferences;
		public RecordValue(Map<String, DataValue> fields) {
			this.fields = immutableMap(fields);
			this.indexedFields = List.copyOf(this.fields.entrySet());
			this.units = namedUnits(this.fields);
            this.localReferences=this.fields.values().stream().mapToLong(DataValue::localReferences).reduce(0,Math::addExact);
		}
        private RecordValue(Construction prefix) {
            this.units = prefix.units; this.localReferences=prefix.localReferences;
            var shape = prefix.shape;
            this.indexedFields = Collections.unmodifiableList(new AbstractList<>() {
                public int size() {
                    return shape.keys.size();
                }
                public Map.Entry<String, DataValue> get(int index) {
                    return Map.entry(shape.keys.get(index), prefix.values.get(index));
                }
            });
            this.fields = Collections.unmodifiableMap(new AbstractMap<>() {
                public int size() {
                    return shape.keys.size();
                }
                public DataValue get(Object key) {
                    Integer i = shape.index.get(key);
                    return i == null?null:prefix.values.get(i);
                }
                public Set<Map.Entry<String, DataValue>> entrySet() {
                    return Collections.unmodifiableSet(new AbstractSet<>() {
                        public int size() {
                            return shape.keys.size();
                        }
                        public Iterator<Map.Entry<String, DataValue>> iterator() {
                            return indexedFields.iterator();
                        }
                    });
                }
            });
        }
        private RecordValue(NamedConstruction prefix) {
            this.units=prefix.units; this.localReferences=prefix.localReferences;
            this.indexedFields=prefix.entries();
            this.fields=prefix.map();
        }
		public Map<String, DataValue> fields() { return fields; }
		/** Immutable representation index, sharing the already charged named occurrences. Entries cannot mutate values. */
		public List<Map.Entry<String, DataValue>> indexedFields() { return indexedFields; }
		public long storageUnits() { return units; }
        public long localReferences() { return localReferences; }
		public DataValue field(String name) { return requiredField(fields, name); }
		@Override public boolean equals(Object other) {
			return other instanceof RecordValue value && ValueTraversal.equal(this, value);
		}
		@Override public int hashCode() { return ValueTraversal.hash(this); }
		@Override public String toString() { return "RecordValue[fields=" + fields.size() + "]"; }
	}
	final class MapValue implements DataValue {
		private final Map<String, DataValue> entries;
		private final List<Map.Entry<String, DataValue>> indexedEntries;
		private final long units;
        private final long localReferences;
		public MapValue(Map<String, DataValue> entries) {
			this.entries = immutableMap(entries);
			this.indexedEntries = List.copyOf(this.entries.entrySet());
			this.units = namedUnits(this.entries);
            this.localReferences=this.entries.values().stream().mapToLong(DataValue::localReferences).reduce(0,Math::addExact);
		}
		public Map<String, DataValue> entries() { return entries; }
        private MapValue(NamedConstruction prefix) { units=prefix.units; localReferences=prefix.localReferences; indexedEntries=prefix.entries(); entries=prefix.map(); }
		/** Same frozen named occurrences, in insertion order, with constant-time cursor positioning. */
		public List<Map.Entry<String, DataValue>> indexedEntries() { return indexedEntries; }
		public long storageUnits() { return units; }
        public long localReferences() { return localReferences; }
		public DataValue field(String name) { return requiredField(entries, name); }
		@Override public boolean equals(Object other) {
			return other instanceof MapValue value && ValueTraversal.equal(this, value);
		}
		@Override public int hashCode() { return ValueTraversal.hash(this); }
		@Override public String toString() { return "MapValue[entries=" + entries.size() + "]"; }
	}

    /** Installed, frozen field shape. Keys are data, not program symbols. */
    final class RecordShape {
        private final List<String> keys;
        private final Map<String, Integer> index;
        private final long[] keyUnits;
        public RecordShape(List<String> keys) {
            this.keys = List.copyOf(keys);
            var lookup = new LinkedHashMap<String, Integer>();
            keyUnits = new long[keys.size()];
            for (int i = 0;i<keys.size();i++) {
                String key = this.keys.get(i);
                if (lookup.putIfAbsent(key, i) != null) throw new IllegalArgumentException("Duplicate record field: " + key);
                keyUnits[i] = 1L + key.length();
            }
            index = Collections.unmodifiableMap(lookup);
        }
        public List<String> keys() {
            return keys;
        }
    }
    /** Immutable private construction ownership; no caller can supply a backing list or cached units. */
    final class Construction {
        private final FrozenOccurrenceList values;
        private final RecordShape shape;
        private final long units;
        private final long localReferences;
        private Construction(FrozenOccurrenceList values, RecordShape shape, long units, long localReferences) {
            this.values = values;
            this.shape = shape;
            this.units = units; this.localReferences=localReferences;
        }
        public static Construction list() {
            return new Construction(FrozenOccurrenceList.EMPTY, null, 1, 0);
        }
        public static Construction record(RecordShape shape) {
            return new Construction(FrozenOccurrenceList.EMPTY, Objects.requireNonNull(shape), 1, 0);
        }
        public int size() {
            return values.size();
        }
        public long storageUnits() {
            return units;
        }
        public Construction append(DataValue value) {
            long next = addUnits(units, Objects.requireNonNull(value).storageUnits());
            if (shape != null) {
                if (size() >= shape.keys.size()) throw new IllegalStateException("Record shape exhausted");
                next = addUnits(next, shape.keyUnits[size()]);
            }
            return new Construction(values.append(value), shape, next,Math.addExact(localReferences,value.localReferences()));
        }
        public DataValue finish() {
            if (shape == null)return new ListValue(this);
            if (size() != shape.keys.size()) throw new IllegalStateException("Incomplete record");
            return new RecordValue(this);
        }
    }

    /** Dynamic named sealing. Character comparisons and AVL path rebuilding are explicit paid steps;
     * finish shares already validated immutable vectors and never copies/sorts/walks the members. */
    final class NamedConstruction {
        private record Node(String key,int index,Node left,Node right,int height) {
            Node(String key,int index,Node left,Node right) {this(key,index,left,right,1+Math.max(NamedConstruction.height(left),NamedConstruction.height(right)));}
        }
        private record Path(Node node,boolean left,Path parent) {}
        private final FrozenOccurrenceList keys,values;
        private final Node root;
        private final long units;
        private final long localReferences;
        private NamedConstruction(FrozenOccurrenceList keys,FrozenOccurrenceList values,Node root,long units,long localReferences) {this.keys=keys;this.values=values;this.root=root;this.units=units;this.localReferences=localReferences;}
        public static NamedConstruction empty(){return new NamedConstruction(FrozenOccurrenceList.EMPTY,FrozenOccurrenceList.EMPTY,null,1,0);}
        public int size(){return values.size();}
        public long storageUnits(){return units;}
        public Insertion insert(String key,DataValue value,boolean replace){return new Insertion(this,Objects.requireNonNull(key),Objects.requireNonNull(value),replace,root,null,0,null,-1,false);}
        public DataValue.RecordValue record(){return new RecordValue(this);}
        public DataValue.MapValue mapValue(){return new MapValue(this);}
        private List<Map.Entry<String,DataValue>> entries(){return Collections.unmodifiableList(new AbstractList<>() {
            public int size(){return values.size();}
            public Map.Entry<String,DataValue> get(int i){return Map.entry(((TextValue)keys.get(i)).value(),values.get(i));}
        });}
        private Map<String,DataValue> map(){var entries=entries();return Collections.unmodifiableMap(new AbstractMap<>() {
            public int size(){return values.size();}
            public DataValue get(Object key){if(!(key instanceof String text))return null;Node n=root;while(n!=null){int c=text.compareTo(n.key);if(c==0)return values.get(n.index);n=c<0?n.left:n.right;}return null;}
            public Set<Map.Entry<String,DataValue>> entrySet(){return Collections.unmodifiableSet(new AbstractSet<>() {public int size(){return values.size();}public Iterator<Map.Entry<String,DataValue>> iterator(){return entries.iterator();}});}
        });}
        public static final class Insertion {
            private final NamedConstruction before; private final String key; private final DataValue value; private final boolean replace;
            private final Node cursor,built;private final Path path;private final int offset,index;private final boolean rebuilding;
            private Insertion(NamedConstruction before,String key,DataValue value,boolean replace,Node cursor,Path path,int offset,Node built,int index,boolean rebuilding){this.before=before;this.key=key;this.value=value;this.replace=replace;this.cursor=cursor;this.path=path;this.offset=offset;this.built=built;this.index=index;this.rebuilding=rebuilding;}
            public boolean done(){return rebuilding&&path==null;}
            public long storageUnits(){return before.storageUnits()+key.length()+value.storageUnits()+128;}
            public Insertion advance(){
                if(done())throw new IllegalStateException("Named insertion complete");
                if(rebuilding){Node p=path.node;Node next=balance(path.left?new Node(p.key,p.index,built,p.right):new Node(p.key,p.index,p.left,built));return new Insertion(before,key,value,replace,null,path.parent,0,next,index,true);}
                if(cursor==null)return new Insertion(before,key,value,replace,null,path,0,new Node(key,before.size(),null,null),before.size(),true);
                int c;
                if(offset<key.length()&&offset<cursor.key.length()){c=Character.compare(key.charAt(offset),cursor.key.charAt(offset));if(c==0)return new Insertion(before,key,value,replace,cursor,path,offset+1,null,-1,false);}
                else c=Integer.compare(key.length(),cursor.key.length());
                if(c==0){if(!replace)throw new IllegalArgumentException("Duplicate named field");return new Insertion(before,key,value,replace,null,path,0,cursor,cursor.index,true);}
                return new Insertion(before,key,value,replace,c<0?cursor.left:cursor.right,new Path(cursor,c<0,path),0,null,-1,false);
            }
            public NamedConstruction finish(){if(!done())throw new IllegalStateException("Unchecked named insertion");boolean fresh=index==before.size();long size=fresh?addUnits(addUnits(before.units,1L+key.length()),value.storageUnits()):addUnits(before.units-before.values.get(index).storageUnits(),value.storageUnits());
                return new NamedConstruction(fresh?before.keys.append(new TextValue(key)):before.keys,fresh?before.values.append(value):before.values.replace(index,value),built,size,Math.addExact(before.localReferences-(fresh?0:before.values.get(index).localReferences()),value.localReferences()));}
        }
        private static int height(Node n){return n==null?0:n.height;}
        private static Node balance(Node n){int d=height(n.left)-height(n.right);if(d>1){Node l=n.left;if(height(l.left)<height(l.right))l=rotateLeft(l);return rotateRight(new Node(n.key,n.index,l,n.right));}if(d< -1){Node r=n.right;if(height(r.right)<height(r.left))r=rotateRight(r);return rotateLeft(new Node(n.key,n.index,n.left,r));}return n;}
        private static Node rotateLeft(Node n){Node r=n.right;return new Node(r.key,r.index,new Node(n.key,n.index,n.left,r.left),r.right);}
        private static Node rotateRight(Node n){Node l=n.left;return new Node(l.key,l.index,l.left,new Node(n.key,n.index,l.right,n.right));}
    }

    /** One member validation at a time, with an unforgeable sealing witness. */
    final class NativeValidation {
        private final int tag,index;private final DataValue body;
        private NativeValidation(int tag,DataValue body,int index){this.tag=tag;this.body=body;this.index=index;}
        public static NativeValidation begin(int tag,DataValue body){Objects.requireNonNull(body);if(tag==7||tag==9||tag==11||tag==12){if(!(body instanceof ListValue))throw new IllegalArgumentException("Native list body");}else if(tag==10){if(!(body instanceof RecordValue))throw new IllegalArgumentException("Native compound body");}else {new NbtValue(tag,body);return new NativeValidation(tag,body,1);}return new NativeValidation(tag,body,0);}
        private int size(){return body instanceof ListValue l?l.values().size():body instanceof RecordValue r?r.indexedFields().size():1;}
        public boolean done(){return index==size();}
        public NativeValidation advance(){if(done())throw new IllegalStateException("Native validation complete");DataValue child=body instanceof ListValue l?l.values().get(index):((RecordValue)body).indexedFields().get(index).getValue();
            boolean valid=switch(tag){case 7,11,12->NbtValue.integerRange(child,tag==7?Byte.MIN_VALUE:tag==11?Integer.MIN_VALUE:Long.MIN_VALUE,tag==7?Byte.MAX_VALUE:tag==11?Integer.MAX_VALUE:Long.MAX_VALUE);case 9->child instanceof NbtValue n&&n.tagId!=0&&n.tagId==((NbtValue)((ListValue)body).values().getFirst()).tagId;case 10->child instanceof NbtValue n&&n.tagId!=0;default->false;};
            if(!valid)throw new IllegalArgumentException("Invalid native member");return new NativeValidation(tag,body,index+1);}
        public NbtValue finish(){if(!done())throw new IllegalStateException("Native validation unfinished");return new NbtValue(tag,body,this);}
        public long storageUnits(){return 4+body.storageUnits();}
    }
	private static long namedUnits(Map<String, DataValue> fields) {
		long size = 1;
		for (var field : fields.entrySet()) {
			size = addUnits(size, 1L + field.getKey().length());
			size = addUnits(size, field.getValue().storageUnits());
		}
		return size;
	}

	private static long addUnits(long left, long right) {
		try {
			return Math.addExact(left, right);
		} catch (ArithmeticException overflow) {
			throw new ComputationException(ComputationException.Code.MEMORY_LIMIT, "value", "Logical size exceeds supported range");
		}
	}

	static <T> Map<String, T> immutableMap(Map<String, T> source) {
		var copy = new LinkedHashMap<String, T>();
		source.forEach((key, value) -> copy.put(Objects.requireNonNull(key), Objects.requireNonNull(value)));
		return Collections.unmodifiableMap(copy);
	}

	private static DataValue requiredField(Map<String, DataValue> fields, String name) {
		DataValue value = fields.get(name);
		if (value == null)
			throw new ComputationException(ComputationException.Code.MISSING_FIELD, name, "Field does not exist");
		return value;
	}
}
