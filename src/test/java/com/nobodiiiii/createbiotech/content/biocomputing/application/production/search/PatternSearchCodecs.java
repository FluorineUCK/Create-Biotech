package com.nobodiiiii.createbiotech.content.biocomputing.application.production.search;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternRecord;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternDataCodecs;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternItemCodecs;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** Strict production-query persistence, including the exact pre-directory local envelopes. */
public final class PatternSearchCodecs {

	private PatternSearchCodecs() {}

	public static CompoundTag saveQuery(PatternQuery value, HolderLookup.Provider registries) {
		CompoundTag tag = new CompoundTag();
		tag.putUUID("QueryId", value.queryId());
		tag.putUUID("RequesterComputerId", value.requesterComputerId());
		tag.putUUID("LogisticsId", value.logisticsId());
		tag.put("RequestedOutput", PatternItemCodecs.saveStackKey(value.requestedStackKey(), registries));
		tag.putLong("Generation", value.queryGeneration());
		tag.putInt("Cursor", value.pageCursor());
		if (value.targetsLibrary()) {
			tag.putUUID("LibraryId", value.libraryId());
			tag.putInt("MaxReplyBatch", value.maxReplyBatch());
			tag.putLong("FormationGeneration", value.formationGeneration());
			tag.putLong("SearchGeneration", value.searchGeneration());
			tag.putUUID("AttemptId", value.attemptId());
		}
		return tag;
	}

	public static Optional<PatternQuery> loadQuery(CompoundTag tag, HolderLookup.Provider registries) {
		boolean addressed = tag.contains("LibraryId");
		String[] keys = addressed ? new String[] { "QueryId", "RequesterComputerId",
			"LogisticsId", "LibraryId", "RequestedOutput", "Generation", "Cursor",
			"MaxReplyBatch", "FormationGeneration", "SearchGeneration", "AttemptId" } : new String[] { "QueryId", "RequesterComputerId",
			"LogisticsId", "RequestedOutput", "Generation", "Cursor" };
		if (!hasExactKeys(tag, keys)
			|| !hasUuid(tag, "QueryId") || !hasUuid(tag, "RequesterComputerId") || !hasUuid(tag, "LogisticsId")
			|| !hasType(tag, "RequestedOutput", Tag.TAG_COMPOUND) || !hasType(tag, "Generation", Tag.TAG_LONG)
			|| !hasType(tag, "Cursor", Tag.TAG_INT) || tag.getInt("Cursor") < 0
			|| addressed && (!hasUuid(tag, "LibraryId")
				|| !hasUuid(tag, "AttemptId")
				|| !hasType(tag, "FormationGeneration", Tag.TAG_LONG)
				|| !hasType(tag, "SearchGeneration", Tag.TAG_LONG)
				|| !hasType(tag, "MaxReplyBatch", Tag.TAG_INT)
				|| tag.getInt("MaxReplyBatch") <= 0 || tag.getInt("MaxReplyBatch") > PatternQuery.MAX_REPLY_BATCH))
			return Optional.empty();
		return PatternItemCodecs.loadStackKey(tag.getCompound("RequestedOutput"), registries).flatMap(output ->
			addressed ? safely(() -> new PatternQuery(tag.getUUID("QueryId"),
				tag.getUUID("RequesterComputerId"), tag.getUUID("LogisticsId"),
				tag.getUUID("LibraryId"), output, tag.getLong("Generation"),
				tag.getInt("Cursor"), tag.getInt("MaxReplyBatch"),
				tag.getLong("FormationGeneration"), tag.getLong("SearchGeneration"),
				tag.getUUID("AttemptId")))
				: safely(() -> new PatternQuery(tag.getUUID("QueryId"),
					tag.getUUID("RequesterComputerId"), tag.getUUID("LogisticsId"), output,
					tag.getLong("Generation"), tag.getInt("Cursor"))));
	}

	public static CompoundTag saveReply(PatternReply value, HolderLookup.Provider registries) {
		CompoundTag tag = new CompoundTag();
		tag.putUUID("QueryId", value.queryId());
		tag.putUUID("RequesterComputerId", value.requesterComputerId());
		tag.putUUID("LogisticsId", value.logisticsId());
		tag.putLong("Generation", value.generation());
		if (value.libraryId().equals(new UUID(0, 0))) {
			tag.putString("Status", switch (value.status()) {
				case MATCH_BATCH -> LegacyStatus.MATCH.name();
				case GROUP_END -> LegacyStatus.NOT_FOUND.name();
				case RESTART_REQUIRED, UNAVAILABLE -> LegacyStatus.INVALID_LIBRARY.name();
			});
			if (value.pattern() != null)
				tag.put("Pattern", PatternDataCodecs.saveRecord(value.pattern(), registries));
			return tag;
		}
		tag.putUUID("LibraryId", value.libraryId());
		tag.putString("Status", value.status().name());
		tag.putInt("NextPageCursor", value.nextPageCursor());
		tag.putLong("FormationGeneration", value.formationGeneration());
		tag.putLong("SearchGeneration", value.searchGeneration());
		tag.putUUID("AttemptId", value.attemptId());
		ListTag matches = new ListTag();
		value.matches().forEach(pattern -> matches.add(PatternDataCodecs.saveRecord(pattern, registries)));
		tag.put("Matches", matches);
		return tag;
	}

	public static Optional<PatternReply> loadReply(CompoundTag tag, HolderLookup.Provider registries) {
		if (!hasUuid(tag, "QueryId") || !hasUuid(tag, "RequesterComputerId")
			|| !hasUuid(tag, "LogisticsId") || !hasType(tag, "Generation", Tag.TAG_LONG)
			|| !hasType(tag, "Status", Tag.TAG_STRING))
			return Optional.empty();
		if (tag.contains("LibraryId")) {
			if (!hasExactKeys(tag, "QueryId", "RequesterComputerId", "LogisticsId", "LibraryId",
				"Generation", "Status", "Matches", "NextPageCursor", "FormationGeneration",
				"SearchGeneration", "AttemptId")
				|| !hasUuid(tag, "LibraryId") || !hasType(tag, "Matches", Tag.TAG_LIST)
				|| !hasUuid(tag, "AttemptId")
				|| !hasType(tag, "FormationGeneration", Tag.TAG_LONG)
				|| !hasType(tag, "SearchGeneration", Tag.TAG_LONG)
				|| !hasType(tag, "NextPageCursor", Tag.TAG_INT)
				|| tag.getInt("NextPageCursor") < 0)
				return Optional.empty();
			ListTag savedMatches = (ListTag) tag.get("Matches");
			if (savedMatches == null || savedMatches.size() > PatternQuery.MAX_REPLY_BATCH
				|| (!savedMatches.isEmpty() && savedMatches.getElementType() != Tag.TAG_COMPOUND))
				return Optional.empty();
			return enumValue(PatternReply.Status.class, tag.getString("Status")).flatMap(status -> {
				List<PatternRecord> matches = new ArrayList<>(savedMatches.size());
				for (Tag value : savedMatches) {
					if (!(value instanceof CompoundTag child))
						return Optional.empty();
					Optional<PatternRecord> pattern = PatternDataCodecs.loadRecord(child, registries);
					if (pattern.isEmpty())
						return Optional.empty();
					matches.add(pattern.orElseThrow());
				}
				return safely(() -> new PatternReply(tag.getUUID("QueryId"),
					tag.getUUID("RequesterComputerId"), tag.getUUID("LogisticsId"),
					 tag.getUUID("LibraryId"), tag.getLong("Generation"), status, matches,
					tag.getInt("NextPageCursor"), tag.getLong("FormationGeneration"),
					tag.getLong("SearchGeneration"), tag.getUUID("AttemptId")));
			});
		}
		return enumValue(LegacyStatus.class, tag.getString("Status")).flatMap(status -> {
			boolean containsPattern = tag.contains("Pattern");
			if ((status == LegacyStatus.MATCH) != containsPattern
				|| !hasExactKeys(tag, containsPattern ? new String[] { "QueryId", "RequesterComputerId",
					"LogisticsId", "Generation", "Status", "Pattern" }
					: new String[] { "QueryId", "RequesterComputerId", "LogisticsId", "Generation", "Status" })
				|| (containsPattern && !hasType(tag, "Pattern", Tag.TAG_COMPOUND)))
				return Optional.empty();
			Optional<PatternRecord> pattern = containsPattern
				? PatternDataCodecs.loadRecord(tag.getCompound("Pattern"), registries) : Optional.empty();
			return containsPattern ? pattern.flatMap(value -> safely(() -> new PatternReply(tag.getUUID("QueryId"),
				tag.getUUID("RequesterComputerId"), tag.getUUID("LogisticsId"), tag.getLong("Generation"),
				status.current, value))) : safely(() -> new PatternReply(tag.getUUID("QueryId"),
					tag.getUUID("RequesterComputerId"), tag.getUUID("LogisticsId"), tag.getLong("Generation"),
					status.current, null));
		});
	}

	private static boolean hasUuid(CompoundTag tag, String key) {
		return hasType(tag, key, Tag.TAG_INT_ARRAY) && tag.hasUUID(key);
	}

	private static boolean hasType(CompoundTag tag, String key, int type) {
		Tag value = tag.get(key);
		return value != null && value.getId() == type;
	}

	private static boolean hasExactKeys(CompoundTag tag, String... keys) {
		return tag.getAllKeys().size() == keys.length && java.util.Set.of(keys).equals(tag.getAllKeys());
	}

	private static <T> Optional<T> safely(java.util.function.Supplier<T> supplier) {
		try {
			return Optional.of(supplier.get());
		} catch (IllegalArgumentException exception) {
			return Optional.empty();
		}
	}

	private static <E extends Enum<E>> Optional<E> enumValue(Class<E> type, String value) {
		try {
			return Optional.of(Enum.valueOf(type, value));
		} catch (IllegalArgumentException exception) {
			return Optional.empty();
		}
	}

	/** Old on-disk spellings only; current application replies use one status model. */
	private enum LegacyStatus {
		MATCH(PatternReply.Status.MATCH_BATCH), NOT_FOUND(PatternReply.Status.GROUP_END),
		INVALID_LIBRARY(PatternReply.Status.UNAVAILABLE);
		private final PatternReply.Status current;
		LegacyStatus(PatternReply.Status current) { this.current = current; }
	}
}
