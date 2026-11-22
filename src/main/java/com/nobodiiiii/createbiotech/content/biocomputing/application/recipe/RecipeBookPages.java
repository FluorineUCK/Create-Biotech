package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternPageKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternJsonParser;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.ParseResult;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternEditData;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.WritableBookContent;

/** Pure copy-and-validate operation used before the world shelf is mutated. */
public final class RecipeBookPages {
	private RecipeBookPages() {}
	public static EditResult replacePage(ItemStack book, PatternPageKey source, String expectedRaw,
		PatternEditData edited, HolderLookup.Provider registries) {
		try { return replacePageJson(book, source, expectedRaw, edited.toJson(registries), registries); }
		catch (RuntimeException exception) { return EditResult.failure("invalid", book); }
	}
	public static EditResult replacePageJson(ItemStack book, PatternPageKey source, String expectedRaw,
		String json, HolderLookup.Provider registries) {
		Objects.requireNonNull(book); Objects.requireNonNull(source); Objects.requireNonNull(expectedRaw);
		Objects.requireNonNull(json); Objects.requireNonNull(registries);
		if (!book.is(Items.WRITABLE_BOOK)) return EditResult.failure("conflict", book);
		WritableBookContent content = book.get(DataComponents.WRITABLE_BOOK_CONTENT);
		if (content == null || source.page() >= content.pages().size()
			|| !content.pages().get(source.page()).raw().equals(expectedRaw)) return EditResult.failure("conflict", book);
		if (json.length() > WritableBookContent.PAGE_EDIT_LENGTH
			|| !(new PatternJsonParser(registries).parse(source, json) instanceof ParseResult.Valid))
			return EditResult.failure("invalid", book);
		ArrayList<Filterable<String>> pages = new ArrayList<>(content.pages());
		pages.set(source.page(), Filterable.passThrough(json));
		ItemStack copy = book.copy();
		try { copy.set(DataComponents.WRITABLE_BOOK_CONTENT, new WritableBookContent(pages)); }
		catch (RuntimeException exception) { return EditResult.failure("invalid", book); }
		return new EditResult("success", copy);
	}

	/** Creates a Pattern only in an existing blank page or exactly at the append position. */
	public static EditResult createPageJson(ItemStack book, PatternPageKey target, String json,
		HolderLookup.Provider registries) {
		Objects.requireNonNull(book); Objects.requireNonNull(target); Objects.requireNonNull(json);
		Objects.requireNonNull(registries);
		if (!book.is(Items.WRITABLE_BOOK)) return EditResult.failure("conflict", book);
		WritableBookContent content = book.get(DataComponents.WRITABLE_BOOK_CONTENT);
		if (content == null) return EditResult.failure("conflict", book);
		List<Filterable<String>> originalPages = content.pages();
		if (originalPages.size() >= WritableBookContent.MAX_PAGES
			&& originalPages.stream().noneMatch(page -> page.raw().isBlank()))
			return EditResult.failure("no_capacity", book);
		if (target.page() > originalPages.size()) return EditResult.failure("conflict", book);
		if (target.page() < originalPages.size() && !originalPages.get(target.page()).raw().isBlank())
			return EditResult.failure("conflict", book);
		if (json.length() > WritableBookContent.PAGE_EDIT_LENGTH
			|| !(new PatternJsonParser(registries).parse(target, json) instanceof ParseResult.Valid))
			return EditResult.failure("invalid", book);
		ArrayList<Filterable<String>> pages = new ArrayList<>(originalPages);
		if (target.page() == pages.size()) pages.add(Filterable.passThrough(json));
		else pages.set(target.page(), Filterable.passThrough(json));
		ItemStack copy = book.copy();
		try { copy.set(DataComponents.WRITABLE_BOOK_CONTENT, new WritableBookContent(pages)); }
		catch (RuntimeException exception) { return EditResult.failure("invalid", book); }
		return new EditResult("success", copy);
	}
	/** Existing recipe index range: every possible page of each writable book, not generic entry capacity. */
	public static List<PatternPageKey> pageKeys(RecipeSourceLocation address, List<ItemStack> slots) {
		Objects.requireNonNull(address); Objects.requireNonNull(slots);
		var keys = new ArrayList<PatternPageKey>();
		for (int slot = 0; slot < Math.min(6, slots.size()); slot++) {
			if (!Objects.requireNonNull(slots.get(slot)).is(Items.WRITABLE_BOOK)) continue;
			for (int page = 0; page < WritableBookContent.MAX_PAGES; page++)
				keys.add(new PatternPageKey(address, slot, page));
		}
		return List.copyOf(keys);
	}

	public static String readPage(List<ItemStack> slots, PatternPageKey key) {
		Objects.requireNonNull(slots); Objects.requireNonNull(key);
		if (key.slot() >= Math.min(6, slots.size())) return "";
		var stack = Objects.requireNonNull(slots.get(key.slot()));
		if (!stack.is(Items.WRITABLE_BOOK)) return "";
		var book = stack.getOrDefault(DataComponents.WRITABLE_BOOK_CONTENT, WritableBookContent.EMPTY);
		return key.page() < book.pages().size() ? book.pages().get(key.page()).raw() : "";
	}

	public record EditResult(String resultKey, ItemStack book) {
		public EditResult { Objects.requireNonNull(resultKey); book = Objects.requireNonNull(book).copy(); }
		private static EditResult failure(String key, ItemStack book) { return new EditResult(key, book); }
	}
}
