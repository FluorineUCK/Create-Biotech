package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Shared, client-independent literal-frequency suggestion behavior. */
final class FrequencySuggestionBehavior {
	static final int MAX_SUGGESTIONS = 64;
	static final int MAX_CLIPBOARD_STACKS = 36;
	static final int MAX_CLIPBOARD_PAGES = 16;
	static final int MAX_CLIPBOARD_ENTRIES = 128;

	private FrequencySuggestionBehavior() {}

	static boolean clearGhostTextWhenDropdownHidden(boolean dropdownVisible) {
		return !dropdownVisible;
	}

	static boolean shouldClearOnRightClick(boolean mouseOver, int button) {
		return mouseOver && button == 1;
	}

	static int dropdownY(int textBoxY, int textBoxHeight, boolean anchorToBottom) {
		return textBoxY + (anchorToBottom ? 0 : textBoxHeight);
	}

	static int dropdownYOffset(int textBoxY, int textBoxHeight, boolean anchorToBottom) {
		return dropdownY(textBoxY, textBoxHeight, anchorToBottom) - 72;
	}

	static <P, E> List<String> boundedClipboardLines(Iterable<? extends P> pages,
		Function<? super P, ? extends Iterable<? extends E>> entries,
		Function<? super E, String> text) {
		pages = Objects.requireNonNull(pages, "pages");
		return boundedClipboardLines(List.<Iterable<? extends P>>of(pages), stack -> stack,
			entries, text);
	}

	/**
	 * Reads at most 36 stacks, 16 pages total, and 128 entries total before caller-side filtering;
	 * callers retain at most 64 literal frequency candidates.
	 */
	static <S, P, E> List<String> boundedClipboardLines(Iterable<? extends S> stacks,
		Function<? super S, ? extends Iterable<? extends P>> pages,
		Function<? super P, ? extends Iterable<? extends E>> entries,
		Function<? super E, String> text) {
		stacks = Objects.requireNonNull(stacks, "stacks");
		pages = Objects.requireNonNull(pages, "pages");
		entries = Objects.requireNonNull(entries, "entries");
		text = Objects.requireNonNull(text, "text");
		ArrayList<String> lines = new ArrayList<>(MAX_CLIPBOARD_ENTRIES);
		int stackCount = 0;
		int pageCount = 0;
		int entryCount = 0;
		Iterator<? extends S> stackIterator = stacks.iterator();
		while (stackCount < MAX_CLIPBOARD_STACKS && pageCount < MAX_CLIPBOARD_PAGES
			&& entryCount < MAX_CLIPBOARD_ENTRIES && stackIterator.hasNext()) {
			S stack = stackIterator.next();
			stackCount++;
			Iterator<? extends P> pageIterator = Objects.requireNonNull(pages.apply(stack), "stack pages")
				.iterator();
			while (pageCount < MAX_CLIPBOARD_PAGES && entryCount < MAX_CLIPBOARD_ENTRIES
				&& pageIterator.hasNext()) {
				P page = pageIterator.next();
				pageCount++;
				Iterator<? extends E> entryIterator = Objects.requireNonNull(entries.apply(page), "page entries")
					.iterator();
				while (entryCount < MAX_CLIPBOARD_ENTRIES && entryIterator.hasNext()) {
					E entry = entryIterator.next();
					entryCount++;
					String value = text.apply(entry);
					if (value != null) lines.add(value);
				}
			}
		}
		return List.copyOf(lines);
	}

	static List<Replacement> literalMatches(String value, int cursor,
		Iterable<CommunicationFrequency> candidates) {
		value = Objects.requireNonNull(value, "value");
		candidates = Objects.requireNonNull(candidates, "candidates");
		if (cursor < 0 || cursor > value.length())
			throw new IllegalArgumentException("cursor is outside frequency value");
		String prefix = value.substring(0, cursor);
		ArrayList<Replacement> matches = new ArrayList<>();
		int candidateCount = 0;
		Iterator<CommunicationFrequency> candidateIterator = candidates.iterator();
		while (candidateCount < MAX_SUGGESTIONS && candidateIterator.hasNext()) {
			CommunicationFrequency candidate = candidateIterator.next();
			candidateCount++;
			candidate = Objects.requireNonNull(candidate, "candidate");
			if (!candidate.value().equals(value) && candidate.value().startsWith(prefix))
				matches.add(new Replacement(value.length(), candidate.value()));
		}
		return List.copyOf(matches);
	}

	/** Immutable cache state shared by the live dropdown and its behavior tests. */
	record SuggestionState(boolean active, String previous, int previousCursor) {
		static SuggestionState inactive() {
			return new SuggestionState(false, null, -1);
		}

		SuggestionState withFocus(boolean focused) {
			if (!focused) return inactive();
			return active ? this : new SuggestionState(true, null, -1);
		}

		boolean shouldClearDropdown() {
			return !active;
		}

		boolean needsRefresh(String value, int cursor) {
			value = Objects.requireNonNull(value, "value");
			if (cursor < 0 || cursor > value.length())
				throw new IllegalArgumentException("cursor is outside frequency value");
			return active && (!value.equals(previous) || cursor != previousCursor);
		}

		SuggestionState refreshed(String value, int cursor) {
			if (!needsRefresh(value, cursor)) return this;
			return new SuggestionState(true, value, cursor);
		}
	}

	record Replacement(int rangeEnd, String text) {
		Replacement {
			if (rangeEnd < 0) throw new IllegalArgumentException("negative replacement end");
			text = Objects.requireNonNull(text, "text");
		}

		String applyTo(String value) {
			value = Objects.requireNonNull(value, "value");
			if (rangeEnd != value.length())
				throw new IllegalArgumentException("replacement must cover the whole frequency value");
			return text;
		}
	}
}
