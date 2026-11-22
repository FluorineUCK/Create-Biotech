package com.nobodiiiii.createbiotech.content.biocomputing.application.production.search;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Iterator;
import java.util.Objects;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.function.Predicate;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.ProductionLibraryQueries.RestartTraversalStats;

enum RestartQueue { QUERY, REPLY }

/** Measures element visits only while a generation transition is in progress. */
final class RestartTraversalProbe {
	private boolean active;
	private int queryVisits;
	private int replyVisits;

	void begin() {
		if (active)
			throw new IllegalStateException("Restart traversal measurement is already active");
		queryVisits = 0;
		replyVisits = 0;
		active = true;
	}

	RestartTraversalStats finish() {
		if (!active)
			throw new IllegalStateException("Restart traversal measurement is not active");
		active = false;
		return new RestartTraversalStats(queryVisits, replyVisits);
	}

	boolean active() { return active; }

	void visit(RestartQueue queue, int units) {
		if (!active || units <= 0)
			return;
		if (queue == RestartQueue.QUERY)
			queryVisits += units;
		else
			replyVisits += units;
	}
}

/**
 * Queue wrapper used by production restart logic so eager stream/remove/copy/clear
 * regressions become observable operation counts rather than dead test counters.
 */
final class RestartTrackedDeque<E> extends ArrayDeque<E> {
	private static final long serialVersionUID = 1L;
	private final RestartTraversalProbe probe;
	private final RestartQueue queue;

	RestartTrackedDeque(RestartTraversalProbe probe, RestartQueue queue) {
		this.probe = Objects.requireNonNull(probe, "probe");
		this.queue = Objects.requireNonNull(queue, "queue");
	}

	@Override
	public E removeFirst() {
		E value = super.pollFirst();
		if (value == null)
			throw new java.util.NoSuchElementException();
		probe.visit(queue, 1);
		return value;
	}

	@Override
	public E removeLast() {
		E value = super.pollLast();
		if (value == null)
			throw new java.util.NoSuchElementException();
		probe.visit(queue, 1);
		return value;
	}

	@Override
	public E pollFirst() {
		E value = super.pollFirst();
		if (value != null)
			probe.visit(queue, 1);
		return value;
	}

	@Override
	public E pollLast() {
		E value = super.pollLast();
		if (value != null)
			probe.visit(queue, 1);
		return value;
	}

	@Override
	public boolean removeFirstOccurrence(Object candidate) {
		if (!probe.active())
			return super.removeFirstOccurrence(candidate);
		Iterator<E> iterator = iterator();
		while (iterator.hasNext()) {
			if (!Objects.equals(candidate, iterator.next()))
				continue;
			iterator.remove();
			return true;
		}
		return false;
	}

	@Override
	public boolean removeLastOccurrence(Object candidate) {
		if (!probe.active())
			return super.removeLastOccurrence(candidate);
		Iterator<E> iterator = descendingIterator();
		while (iterator.hasNext()) {
			if (!Objects.equals(candidate, iterator.next()))
				continue;
			iterator.remove();
			return true;
		}
		return false;
	}

	@Override
	public boolean contains(Object candidate) {
		if (!probe.active())
			return super.contains(candidate);
		Iterator<E> iterator = iterator();
		while (iterator.hasNext()) {
			if (Objects.equals(candidate, iterator.next()))
				return true;
		}
		return false;
	}

	@Override
	public boolean removeAll(Collection<?> candidates) {
		Objects.requireNonNull(candidates, "candidates");
		if (!probe.active())
			return super.removeAll(candidates);
		boolean removed = false;
		Iterator<E> iterator = iterator();
		while (iterator.hasNext()) {
			if (!candidates.contains(iterator.next()))
				continue;
			iterator.remove();
			removed = true;
		}
		return removed;
	}

	@Override
	public boolean retainAll(Collection<?> retained) {
		Objects.requireNonNull(retained, "retained");
		if (!probe.active())
			return super.retainAll(retained);
		boolean removed = false;
		Iterator<E> iterator = iterator();
		while (iterator.hasNext()) {
			if (retained.contains(iterator.next()))
				continue;
			iterator.remove();
			removed = true;
		}
		return removed;
	}

	@Override
	@SuppressWarnings("unchecked")
	public RestartTrackedDeque<E> clone() {
		probe.visit(queue, size());
		return (RestartTrackedDeque<E>) super.clone();
	}

	@Override
	public Iterator<E> iterator() {
		return counting(super.iterator());
	}

	@Override
	public Iterator<E> descendingIterator() {
		return counting(super.descendingIterator());
	}

	@Override
	public Spliterator<E> spliterator() {
		return counting(super.spliterator());
	}

	@Override
	public void forEach(Consumer<? super E> action) {
		Objects.requireNonNull(action, "action");
		if (!probe.active()) {
			super.forEach(action);
			return;
		}
		Iterator<E> iterator = iterator();
		while (iterator.hasNext())
			action.accept(iterator.next());
	}

	@Override
	public boolean removeIf(Predicate<? super E> filter) {
		Objects.requireNonNull(filter, "filter");
		if (!probe.active())
			return super.removeIf(filter);
		boolean removed = false;
		Iterator<E> iterator = iterator();
		while (iterator.hasNext()) {
			if (!filter.test(iterator.next()))
				continue;
			iterator.remove();
			removed = true;
		}
		return removed;
	}

	@Override
	public Object[] toArray() {
		probe.visit(queue, size());
		return super.toArray();
	}

	@Override
	public <T> T[] toArray(T[] destination) {
		probe.visit(queue, size());
		return super.toArray(destination);
	}

	@Override
	public void clear() {
		probe.visit(queue, size());
		super.clear();
	}

	private Iterator<E> counting(Iterator<E> delegate) {
		return new Iterator<>() {
			@Override public boolean hasNext() { return delegate.hasNext(); }
			@Override public E next() {
				E value = delegate.next();
				probe.visit(queue, 1);
				return value;
			}
			@Override public void remove() { delegate.remove(); }
			@Override public void forEachRemaining(Consumer<? super E> action) {
				Objects.requireNonNull(action, "action");
				delegate.forEachRemaining(value -> {
					probe.visit(queue, 1);
					action.accept(value);
				});
			}
		};
	}

	private Spliterator<E> counting(Spliterator<E> delegate) {
		return new Spliterator<>() {
			@Override public boolean tryAdvance(Consumer<? super E> action) {
				Objects.requireNonNull(action, "action");
				return delegate.tryAdvance(value -> {
					probe.visit(queue, 1);
					action.accept(value);
				});
			}
			@Override public void forEachRemaining(Consumer<? super E> action) {
				Objects.requireNonNull(action, "action");
				delegate.forEachRemaining(value -> {
					probe.visit(queue, 1);
					action.accept(value);
				});
			}
			@Override public Spliterator<E> trySplit() {
				Spliterator<E> split = delegate.trySplit();
				return split == null ? null : counting(split);
			}
			@Override public long estimateSize() { return delegate.estimateSize(); }
			@Override public int characteristics() { return delegate.characteristics(); }
			@Override public java.util.Comparator<? super E> getComparator() {
				return delegate.getComparator();
			}
		};
	}
}
