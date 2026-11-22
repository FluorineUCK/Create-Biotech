package com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationException;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.ProgramDefinition;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.ScalarOperation;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.Collections;
import java.util.Comparator;

/** Immutable local memory. A transaction reads its begin-time root, never its staged writes. */
public final class RegisterMemory {
	public static final DataType.RecordType RESULT_TYPE = new DataType.RecordType(Map.of(
		"ok", DataType.Scalar.BOOLEAN, "code", DataType.Scalar.TEXT));
	public static final RegisterMemory EMPTY = new RegisterMemory(List.of(), new Bank(null, 0), null);
	private final List<Map.Entry<String, DataType>> schema;
	private final long namesUnits;
	private final Bank bank;
	private final Owner owners;

	private RegisterMemory(List<Map.Entry<String, DataType>> schema, Bank bank, Owner owners) {
		this(schema, schema.stream().mapToLong(entry -> 1L + entry.getKey().length()).reduce(0, RegisterMemory::add), bank, owners);
	}
	private RegisterMemory(List<Map.Entry<String, DataType>> schema, long namesUnits, Bank bank, Owner owners) {
		this.schema = schema;
		this.namesUnits = namesUnits;
		this.bank = bank;
		this.owners = owners;
	}

	public static RegisterMemory initial(Map<String, ProgramDefinition.Cell> declarations) {
		var entries = new ArrayList<Map.Entry<String, DataType>>();
		var values = new ArrayList<Cell>();
		declarations.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
			var cell = Objects.requireNonNull(entry.getValue());
			entries.add(Map.entry(entry.getKey(), cell.type()));
			values.add(new Cell(cell.initial(), 0));
		});
		if (entries.isEmpty()) return EMPTY;
		return new RegisterMemory(List.copyOf(entries), new Bank(build(values, 0, values.size()), 0), null);
	}

	public int size() { return schema.size(); }
    public DataType cellType(int index){checkIndex(index);return schema.get(index).getValue();}
    @Override public boolean equals(Object other) {
        return this == other || other instanceof RegisterMemory m && schema.equals(m.schema) && value().equals(m.value());
    }
    @Override public int hashCode() { return Objects.hash(schema, value()); }
	public boolean active(UUID owner) { return find(Objects.requireNonNull(owner)) != null; }

	public DataValue publicLoad(UUID owner, int index) {
		publicAccess(owner, index);
		return get(bank.cells, index, 0, size()).value;
	}

	/** Defensive boundary for arbitrary callers. Structural type validation may traverse the value. */
	public RegisterMemory publicStore(UUID owner, int index, DataValue value) {
        com.nobodiiiii.createbiotech.content.biocomputing.computation.LocalReferences.portable(value);
		publicAccess(owner, index);
		if (!schema.get(index).getValue().accepts(Objects.requireNonNull(value)))
			throw error(ComputationException.Code.TYPE_MISMATCH, "Public STORE does not fit declared cell");
		return publish(index, value);
	}

	/** Only the installed ISA's statically assignable, already validated register may use this seam.
	 * Logical reference assignment shares the immutable payload, without a repeated deep type scan. */
	RegisterMemory publicStoreValidated(UUID owner, int index, DataValue value) {
        com.nobodiiiii.createbiotech.content.biocomputing.computation.LocalReferences.portable(value);
		publicAccess(owner, index);
		return publish(index, Objects.requireNonNull(value));
	}

	private RegisterMemory publish(int index, DataValue value) {
		long version = nextVersion(bank.version);
		return new RegisterMemory(schema, namesUnits,
			new Bank(put(bank.cells, index, new Cell(value, version), 0, size()), version), owners);
	}

	/** One numeric read-modify-write candidate; no transaction or full-bank preparation. */
	public RegisterMemory publicAdd(UUID owner, int index, DataValue delta) {
		publicAccess(owner, index);
		var type = schema.get(index).getValue();
		if (type != DataType.Scalar.INTEGER && type != DataType.Scalar.DECIMAL)
			throw error(ComputationException.Code.TYPE_MISMATCH, "Public ADD requires a numeric cell");
		var operation = type == DataType.Scalar.INTEGER ? ScalarOperation.ADD_INTEGER : ScalarOperation.ADD_DECIMAL;
		var current = get(bank.cells, index, 0, size()).value;
		var value = operation.evaluate(Map.of("left", current, "right", Objects.requireNonNull(delta)), "memory");
		return publish(index, value);
	}

	private void publicAccess(UUID owner, int index) {
		if (active(owner)) throw error(ComputationException.Code.INVALID_ARGUMENT, "Public access is not allowed during this task's transaction");
		checkIndex(index);
	}

	/** Capturing the immutable bank is constant-time and does not inspect or copy any cell. */
	public RegisterMemory begin(UUID owner) {
		Objects.requireNonNull(owner);
		if (active(owner)) throw error(ComputationException.Code.INVALID_ARGUMENT, "Transaction already active");
		return new RegisterMemory(schema, namesUnits, bank, new Owner(owner, new Transaction(bank, null, null), owners));
	}

	public DataValue read(UUID owner, int index) {
		var tx = required(owner);
		checkIndex(index);
		return get(tx.snapshot.cells, index, 0, size()).value;
	}

	public RegisterMemory write(UUID owner, int index, DataValue value) {
        com.nobodiiiii.createbiotech.content.biocomputing.computation.LocalReferences.portable(value);
		var tx = required(owner);
		mutable(tx);
		checkIndex(index);
		if (!schema.get(index).getValue().accepts(Objects.requireNonNull(value)))
			throw error(ComputationException.Code.TYPE_MISMATCH, "Write does not fit declared cell");
		var previous = get(tx.touches, index, 0, size());
		if (previous != null && previous.write != null)
			throw error(ComputationException.Code.WRITE_CONFLICT, "Cell was already written in this transaction");
		var touches = put(tx.touches, index, new Touch(previous != null && previous.read, value), 0, size());
		return replace(owner, new Transaction(tx.snapshot, touches, null));
	}

	public RegisterMemory readMark(UUID owner, int index) {
		var tx = required(owner);
		mutable(tx);
		checkIndex(index);
		var previous = get(tx.touches, index, 0, size());
		if (previous != null && previous.read) return this;
		var touches = put(tx.touches, index, new Touch(true, previous == null ? null : previous.write), 0, size());
		return replace(owner, new Transaction(tx.snapshot, touches, null));
	}

	public boolean preparing(UUID owner) {
		var tx = find(Objects.requireNonNull(owner));
		return tx != null && tx.preparation != null;
	}
	public int preparationIndex(UUID owner) {
		var tx = find(Objects.requireNonNull(owner));
		return tx == null || tx.preparation == null ? -1 : tx.preparation.progress;
	}

	/** Freezes the current root in O(1); validation and candidate construction are metered per cell. */
	public RegisterMemory prepare(UUID owner) {
		var tx = required(owner);
		mutable(tx);
		return replace(owner, new Transaction(tx.snapshot, tx.touches, new Preparation(bank, bank.cells, 0, false)));
	}

	public RegisterMemory prepareCell(UUID owner, int index) {
		var tx = required(owner);
		checkIndex(index);
		var prep = tx.preparation;
		if (prep == null || index != prep.progress)
			throw error(ComputationException.Code.INVALID_ARGUMENT, "Preparation cells must be visited in order");
		var touch = get(tx.touches, index, 0, size());
		boolean conflict = prep.conflict || touch != null
			&& get(tx.snapshot.cells, index, 0, size()).version != get(prep.baseline.cells, index, 0, size()).version;
		var candidate = prep.candidate;
		if (touch != null && touch.write != null) {
			long version = nextVersion(prep.baseline.version);
			candidate = put(candidate, index, new Cell(touch.write, version), 0, size());
		}
		return replace(owner, new Transaction(tx.snapshot, tx.touches,
			new Preparation(prep.baseline, candidate, index + 1, conflict)));
	}

	public record Commit(RegisterMemory memory, DataValue.RecordValue result) {}

	public Commit commit(UUID owner) {
		var tx = required(owner);
		var prep = tx.preparation;
		if (prep == null || prep.progress != size())
			throw error(ComputationException.Code.INVALID_ARGUMENT, "Transaction is not fully prepared");
		var withoutOwner = replace(owner, null);
		if (prep.conflict || bank.version != prep.baseline.version)
			return new Commit(withoutOwner, result(false, "write_conflict"));
		boolean written = writes(tx.touches) != 0;
		var published = written ? new Bank(prep.candidate, nextVersion(bank.version)) : bank;
		return new Commit(new RegisterMemory(schema, namesUnits, published, withoutOwner.owners), result(true, "committed"));
	}

	public RegisterMemory rollback(UUID owner) { required(owner); return replace(owner, null); }

	public Set<UUID> owners() {
		var result = new LinkedHashSet<UUID>();
		for (Owner owner = owners; owner != null; owner = owner.next) result.add(owner.id);
		return Set.copyOf(result);
	}

	public RegisterMemory retain(Set<UUID> retained) {
		Objects.requireNonNull(retained);
		var kept = new ArrayList<Owner>();
		boolean removed = false;
		for (Owner owner = owners; owner != null; owner = owner.next)
			if (retained.contains(owner.id)) kept.add(owner); else removed = true;
		if (!removed) return this;
		Owner result = null;
		for (int i = kept.size() - 1; i >= 0; i--) {
			var owner = kept.get(i);
			result = new Owner(owner.id, owner.tx, result);
		}
		return new RegisterMemory(schema, namesUnits, bank, result);
	}

	private static long nextVersion(long version) {
		if (version == Long.MAX_VALUE) throw error(ComputationException.Code.ARITHMETIC_OVERFLOW, "Memory version exhausted");
		return version + 1;
	}
	private static void mutable(Transaction tx) {
		if (tx.preparation != null) throw error(ComputationException.Code.INVALID_ARGUMENT, "Transaction is already preparing");
	}
	private static DataValue.RecordValue result(boolean ok, String code) {
		return new DataValue.RecordValue(Map.of("ok", new DataValue.BooleanValue(ok), "code", new DataValue.TextValue(code)));
	}

	public DataValue.RecordValue cells() {
		var values = new LinkedHashMap<String, DataValue>();
		for (int i = 0; i < size(); i++) values.put(schema.get(i).getKey(), get(bank.cells, i, 0, size()).value);
		return new DataValue.RecordValue(values);
	}

	private Transaction find(UUID id) {
		for (Owner owner = owners; owner != null; owner = owner.next) if (owner.id.equals(id)) return owner.tx;
		return null;
	}
	private Transaction required(UUID id) {
		var tx = find(Objects.requireNonNull(id));
		if (tx == null) throw error(ComputationException.Code.INVALID_ARGUMENT, "No active transaction");
		return tx;
	}
	private RegisterMemory replace(UUID id, Transaction tx) {
		var prefix = new ArrayList<Owner>();
		Owner cursor = owners;
		while (cursor != null && !cursor.id.equals(id)) { prefix.add(cursor); cursor = cursor.next; }
		if (cursor == null) throw error(ComputationException.Code.INVALID_ARGUMENT, "No active transaction");
		Owner result = tx == null ? cursor.next : new Owner(id, tx, cursor.next);
		for (int i = prefix.size() - 1; i >= 0; i--) {
			var old = prefix.get(i);
			result = new Owner(old.id, old.tx, result);
		}
		return new RegisterMemory(schema, namesUnits, bank, result);
	}
	private void checkIndex(int index) {
		if (index < 0 || index >= size()) throw error(ComputationException.Code.INDEX_OUT_OF_BOUNDS, "Unknown logical cell");
	}
	private static ComputationException error(ComputationException.Code code, String message) {
		return new ComputationException(code, "memory", message);
	}

	/** Fixed-depth persistent index trees; a cell update shares every unaffected branch. */
	private record Tree<T>(Tree<T> left, Tree<T> right, T value, long units, int writes) {
		private Tree(Tree<T> left, Tree<T> right, T value) {
			this(left, right, value, add(add(RegisterMemory.units(left), RegisterMemory.units(right)), weight(value)),
				Math.addExact(Math.addExact(RegisterMemory.writes(left), RegisterMemory.writes(right)), value instanceof Touch touch && touch.write != null ? 1 : 0));
		}
	}
	private static long units(Tree<?> tree) { return tree == null ? 0 : tree.units; }
	private static int writes(Tree<?> tree) { return tree == null ? 0 : tree.writes; }
	private static long weight(Object value) {
		return switch (value) {
			case Cell cell -> add(1, cell.value.storageUnits());
			case Touch touch -> add(3, touch.write == null ? 0 : touch.write.storageUnits());
			case null -> 0;
			default -> throw new IllegalArgumentException("Unknown memory tree value");
		};
	}
	private static long add(long left, long right) {
		try { return Math.addExact(left, right); }
		catch (ArithmeticException overflow) { throw error(ComputationException.Code.MEMORY_LIMIT, "Memory accounting overflow"); }
	}
	private static <T> Tree<T> build(List<T> values, int start, int end) {
		if (start == end) return null;
		if (end - start == 1) return values.get(start) == null ? null : new Tree<>(null, null, values.get(start));
		int middle = start + (end - start) / 2;
		var left = build(values, start, middle);
		var right = build(values, middle, end);
		return left == null && right == null ? null : new Tree<>(left, right, null);
	}
	private static <T> T get(Tree<T> tree, int index, int start, int end) {
		if (tree == null) return null;
		if (end - start == 1) return tree.value;
		int middle = start + (end - start) / 2;
		return index < middle ? get(tree.left, index, start, middle) : get(tree.right, index, middle, end);
	}
	private static <T> Tree<T> put(Tree<T> tree, int index, T value, int start, int end) {
		if (end - start == 1) return new Tree<>(null, null, value);
		int middle = start + (end - start) / 2;
		var left = tree == null ? null : tree.left;
		var right = tree == null ? null : tree.right;
		return index < middle ? new Tree<>(put(left, index, value, start, middle), right, null)
			: new Tree<>(left, put(right, index, value, middle, end), null);
	}
	private record Cell(DataValue value, long version) {}
	private record Bank(Tree<Cell> cells, long version) {}
	private record Touch(boolean read, DataValue write) {}
	private record Preparation(Bank baseline, Tree<Cell> candidate, int progress, boolean conflict) {}
	private record Transaction(Bank snapshot, Tree<Touch> touches, Preparation preparation) {}
	private record Owner(UUID id, Transaction tx, Owner next, long units) {
		private Owner(UUID id, Transaction tx, Owner next) {
			this(id, tx, next, add(add(38, transactionUnits(tx)), next == null ? 0 : next.units));
		}
	}
	private static long transactionUnits(Transaction tx) {
		long result = add(add(2, units(tx.snapshot.cells)), units(tx.touches));
		if (tx.preparation != null) result = add(result,
			add(add(4, units(tx.preparation.baseline.cells)), units(tx.preparation.candidate)));
		return result;
	}

	/** Charges every logical retained occurrence, even when immutable physical roots are shared. */
	public long storageUnits() {
		if (size() == 0 && owners == null) return 0;
		return add(add(add(1, namesUnits), units(bank.cells)), owners == null ? 0 : owners.units);
	}

	/** Native data archive only: UUIDs are identifiers encoded as text, never value references. */
	public DataValue.RecordValue value() {
		var transactions = new ArrayList<Owner>();
		for (Owner owner = owners; owner != null; owner = owner.next) transactions.add(owner);
		transactions.sort(Comparator.comparing(owner -> owner.id.toString()));
		var encoded = new ArrayList<DataValue>();
		for (Owner owner : transactions) {
			var tx = owner.tx;
			var touches = new ArrayList<DataValue>();
			var indexed = flatten(tx.touches, size());
			for (int i = 0; i < size(); i++) {
				var touch = indexed.get(i);
				if (touch != null) touches.add(obj("index", i, "read", touch.read,
					"hasWrite", touch.write != null, "write", touch.write == null ? DataValue.NullValue.INSTANCE : touch.write));
			}
			DataValue prep = DataValue.NullValue.INSTANCE;
			if (tx.preparation != null) {
				var p = tx.preparation;
				prep = obj("baseline", encodeBank(p.baseline), "progress", p.progress,
					"conflict", p.conflict, "candidate", encodeCells(p.candidate));
			}
			encoded.add(obj("owner", owner.id.toString(), "snapshot", encodeBank(tx.snapshot),
				"touches", new DataValue.ListValue(touches), "preparation", prep));
		}
		return obj("version", 1, "bank", encodeBank(bank), "transactions", new DataValue.ListValue(encoded));
	}
	private DataValue.RecordValue encodeBank(Bank source) {
		return obj("version", source.version, "cells", encodeCells(source.cells));
	}
	private DataValue.ListValue encodeCells(Tree<Cell> source) {
		var values = flatten(source, size());
		var encoded = new ArrayList<DataValue>();
		for (int i = 0; i < size(); i++) {
			var cell = values.get(i);
			encoded.add(obj("name", schema.get(i).getKey(), "value", cell.value, "version", cell.version));
		}
		return new DataValue.ListValue(encoded);
	}

	/** Recovery may scan the full archive. Normal preparation never does. */
	public static RegisterMemory restore(DataValue value, Map<String, ProgramDefinition.Cell> declarations) {
		exact(value, "version", "bank", "transactions");
		if (number(at(value, "version")) != 1)
			throw new ArchiveException(ArchiveException.Code.UNSUPPORTED_VERSION, "Unsupported memory archive version");
		var template = initial(declarations);
		var current = template.decodeBank(at(value, "bank"), Long.MAX_VALUE, declarations);
		var currentCells = flatten(current.cells, template.size());
		Owner owners = null;
		var seen = new LinkedHashSet<UUID>();
		for (var encoded : list(at(value, "transactions"))) {
			exact(encoded, "owner", "snapshot", "touches", "preparation");
			String identifier = string(at(encoded, "owner"));
			UUID id;
			try { id = UUID.fromString(identifier); }
			catch (IllegalArgumentException invalid) { throw invalid("Malformed transaction UUID"); }
			if (!identifier.equals(id.toString()) || !seen.add(id)) throw invalid("Noncanonical or duplicate transaction UUID");
			var snapshot = template.decodeBank(at(encoded, "snapshot"), current.version, declarations);
			var snapshotCells = flatten(snapshot.cells, template.size());
			validateHistory(snapshot, snapshotCells, current, currentCells);
			var touches = new ArrayList<Touch>(Collections.nCopies(template.size(), null));
			int previous = -1;
			for (var touch : list(at(encoded, "touches"))) {
				exact(touch, "index", "read", "hasWrite", "write");
				int index = index(at(touch, "index"));
				if (index <= previous || index >= template.size()) throw invalid("Unordered or invalid touched cell");
				previous = index;
				boolean read = bool(at(touch, "read")), hasWrite = bool(at(touch, "hasWrite"));
				DataValue write = at(touch, "write");
				if (!read && !hasWrite || !hasWrite && write != DataValue.NullValue.INSTANCE
					|| hasWrite && !template.schema.get(index).getValue().accepts(write)) throw invalid("Invalid touched cell");
				touches.set(index, new Touch(read, hasWrite ? write : null));
			}
			var touchTree = build(touches, 0, touches.size());
			Preparation prep = null;
			var encodedPrep = at(encoded, "preparation");
			if (encodedPrep != DataValue.NullValue.INSTANCE) {
				exact(encodedPrep, "baseline", "progress", "conflict", "candidate");
				var baseline = template.decodeBank(at(encodedPrep, "baseline"), current.version, declarations);
				var baselineCells = flatten(baseline.cells, template.size());
				validateHistory(snapshot, snapshotCells, baseline, baselineCells);
				validateHistory(baseline, baselineCells, current, currentCells);
				int progress = index(at(encodedPrep, "progress"));
				if (progress > template.size()) throw invalid("Preparation progress exceeds bank");
				long candidateVersion = baseline.version;
				boolean processedWrite = false;
				for (int i = 0; i < progress; i++) if (touches.get(i) != null && touches.get(i).write != null) processedWrite = true;
				if (processedWrite) {
					if (baseline.version == Long.MAX_VALUE) throw invalid("Prepared write overflows bank version");
					candidateVersion++;
				}
				var candidate = template.decodeCells(at(encodedPrep, "candidate"), candidateVersion, declarations);
				boolean conflict = false;
				for (int i = 0; i < template.size(); i++) {
					var touch = touches.get(i);
					var expected = baselineCells.get(i);
					if (i < progress && touch != null) {
						conflict |= snapshotCells.get(i).version != expected.version;
						if (touch.write != null) expected = new Cell(touch.write, baseline.version + 1);
					}
					if (!expected.equals(candidate.get(i))) throw invalid("Forged preparation candidate");
				}
				if (bool(at(encodedPrep, "conflict")) != conflict) throw invalid("Forged preparation conflict flag");
				prep = new Preparation(baseline, build(candidate, 0, candidate.size()), progress, conflict);
			}
			owners = new Owner(id, new Transaction(snapshot, touchTree, prep), owners);
		}
		if (template.size() == 0 && owners == null) return EMPTY;
		return new RegisterMemory(template.schema, template.namesUnits, current, owners);
	}
	private Bank decodeBank(DataValue value, long maximumVersion, Map<String, ProgramDefinition.Cell> declarations) {
		exact(value, "version", "cells");
		long version = number(at(value, "version"));
		if (version < 0 || version > maximumVersion) throw invalid("Invalid bank version");
		var cells = decodeCells(at(value, "cells"), version, declarations);
		long maximum = 0;
		for (var cell : cells) maximum = Math.max(maximum, cell.version);
		if (maximum != version) throw invalid("Bank version has no corresponding published cell");
		return new Bank(build(cells, 0, cells.size()), version);
	}
	private List<Cell> decodeCells(DataValue value, long maximumVersion, Map<String, ProgramDefinition.Cell> declarations) {
		var encoded = list(value);
		if (encoded.size() != size()) throw invalid("Memory layout length differs from schema");
		var cells = new ArrayList<Cell>();
		for (int i = 0; i < size(); i++) {
			var cell = encoded.get(i);
			exact(cell, "name", "value", "version");
			String name = string(at(cell, "name"));
			DataValue stored = at(cell, "value");
			long version = number(at(cell, "version"));
			if (!name.equals(schema.get(i).getKey()) || version < 0 || version > maximumVersion
				|| !schema.get(i).getValue().accepts(stored)
				|| version == 0 && !stored.equals(declarations.get(name).initial())) throw invalid("Invalid archived cell");
			cells.add(new Cell(stored, version));
		}
		return cells;
	}
	private static void validateHistory(Bank older, List<Cell> oldCells, Bank newer, List<Cell> newCells) {
		if (older.version > newer.version) throw invalid("Snapshot is newer than its baseline");
		for (int i = 0; i < oldCells.size(); i++) {
			var old = oldCells.get(i);
			var current = newCells.get(i);
			if (old.version > current.version || old.version == current.version && !old.value.equals(current.value)
				|| older.version == newer.version && !old.equals(current)) throw invalid("Inconsistent cell history");
		}
	}
	private static <T> List<T> flatten(Tree<T> root, int size) {
		var result = new ArrayList<T>(Collections.nCopies(size, null));
		flatten(root, 0, size, result);
		return result;
	}
	private static <T> void flatten(Tree<T> root, int start, int end, List<T> result) {
		if (root == null) return;
		if (end - start == 1) { result.set(start, root.value); return; }
		int middle = start + (end - start) / 2;
		flatten(root.left, start, middle, result);
		flatten(root.right, middle, end, result);
	}

	// ArchiveData is package-private; these helpers follow its closed native-data schema conventions.
	private static DataValue.RecordValue obj(Object... pairs) {
		var fields = new LinkedHashMap<String, DataValue>();
		for (int i = 0; i < pairs.length; i += 2) {
			DataValue value = switch (pairs[i + 1]) {
				case DataValue data -> data;
				case String text -> new DataValue.TextValue(text);
				case Integer integer -> new DataValue.IntegerValue(integer);
				case Long integer -> new DataValue.IntegerValue(integer);
				case Boolean bool -> new DataValue.BooleanValue(bool);
				default -> throw new IllegalArgumentException("Not an archive scalar");
			};
			fields.put((String) pairs[i], value);
		}
		return new DataValue.RecordValue(fields);
	}
	private static ArchiveException invalid(String message) { return new ArchiveException(ArchiveException.Code.INVALID_FORMAT, message); }
	private static Map<String, DataValue> fields(DataValue value) {
		if (value instanceof DataValue.RecordValue record) return record.fields();
		throw invalid("Expected record");
	}
	private static void exact(DataValue value, String... keys) {
		if (!fields(value).keySet().equals(Set.of(keys))) throw invalid("Unexpected archive fields");
	}
	private static DataValue at(DataValue value, String key) {
		var child = fields(value).get(key);
		if (child == null) throw invalid("Missing field: " + key);
		return child;
	}
	private static String string(DataValue value) {
		if (value instanceof DataValue.TextValue text) return text.value();
		throw invalid("Expected text");
	}
	private static long number(DataValue value) {
		if (value instanceof DataValue.IntegerValue integer) return integer.value();
		throw invalid("Expected integer");
	}
	private static int index(DataValue value) {
		long number = number(value);
		if (number < 0 || number > Integer.MAX_VALUE) throw invalid("Invalid index");
		return (int) number;
	}
	private static boolean bool(DataValue value) {
		if (value instanceof DataValue.BooleanValue bool) return bool.value();
		throw invalid("Expected boolean");
	}
	private static List<DataValue> list(DataValue value) {
		if (value instanceof DataValue.ListValue list) return list.values();
		throw invalid("Expected list");
	}
}
