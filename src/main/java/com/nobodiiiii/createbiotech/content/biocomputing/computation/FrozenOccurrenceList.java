package com.nobodiiiii.createbiotech.content.biocomputing.computation;

import java.util.*;

/** Dedicated immutable occurrence vector. Each append copies exactly 32 nodes, independent of prefix size. */
final class FrozenOccurrenceList extends AbstractList<DataValue> implements RandomAccess {
    private static final int TOP_BIT = Integer.SIZE - 2;
    private record Node(Node left, Node right, DataValue value) {}

    static final FrozenOccurrenceList EMPTY = new FrozenOccurrenceList(null, 0);
    private final Node root;
    private final int size;

    private FrozenOccurrenceList(Node root, int size) {
        this.root = root;
        this.size = size;
    }

    FrozenOccurrenceList append(DataValue value) {
        Objects.requireNonNull(value);
        if (size == Integer.MAX_VALUE)
            throw new ComputationException(ComputationException.Code.MEMORY_LIMIT, "construction", "Too many occurrences");
        return new FrozenOccurrenceList(put(root, TOP_BIT, size, value), size + 1);
    }

    FrozenOccurrenceList replace(int index, DataValue value) {
        Objects.checkIndex(index, size);
        return new FrozenOccurrenceList(put(root, TOP_BIT, index, Objects.requireNonNull(value)), size);
    }

    private static Node put(Node node, int bit, int index, DataValue value) {
        if (bit < 0) return new Node(null, null, value);
        Node left = node == null ? null : node.left();
        Node right = node == null ? null : node.right();
        if ((index & (1 << bit)) == 0) left = put(left, bit - 1, index, value);
        else right = put(right, bit - 1, index, value);
        return new Node(left, right, null);
    }

    @Override public int size() { return size; }

    @Override public DataValue get(int index) {
        Objects.checkIndex(index, size);
        Node node = root;
        for (int bit = TOP_BIT; bit >= 0; bit--)
            node = (index & (1 << bit)) == 0 ? node.left() : node.right();
        return node.value();
    }

    @Override public Iterator<DataValue> iterator() {
        return new Iterator<>() {
            private final Deque<Node> pending = new ArrayDeque<>();
            { if (root != null) pending.push(root); }

            @Override public boolean hasNext() { return !pending.isEmpty(); }

            @Override public DataValue next() {
                while (!pending.isEmpty()) {
                    Node node = pending.pop();
                    if (node.value() != null) return node.value();
                    if (node.right() != null) pending.push(node.right());
                    if (node.left() != null) pending.push(node.left());
                }
                throw new NoSuchElementException();
            }
        };
    }
}
