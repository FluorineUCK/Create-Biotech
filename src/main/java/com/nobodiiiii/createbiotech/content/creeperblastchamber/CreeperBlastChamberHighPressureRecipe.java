package com.nobodiiiii.createbiotech.content.creeperblastchamber;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.nobodiiiii.createbiotech.registry.CBRecipeTypes;
import com.simibubi.create.content.processing.recipe.ProcessingOutput;
import com.simibubi.create.content.processing.recipe.ProcessingRecipe;
import com.simibubi.create.content.processing.recipe.ProcessingRecipeParams;
import com.simibubi.create.foundation.recipe.IRecipeTypeInfo;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.wrapper.RecipeWrapper;

public class CreeperBlastChamberHighPressureRecipe
	extends ProcessingRecipe<RecipeWrapper, CreeperBlastChamberHighPressureRecipe.Params> {

	private static final IRecipeTypeInfo TYPE_INFO = new IRecipeTypeInfo() {
		@Override
		public ResourceLocation getId() {
			return CBRecipeTypes.CREEPER_BLAST_CHAMBER_HIGH_PRESSURE_TYPE.getId();
		}

		@Override
		@SuppressWarnings("unchecked")
		public <T extends RecipeSerializer<?>> T getSerializer() {
			return (T) CBRecipeTypes.CREEPER_BLAST_CHAMBER_HIGH_PRESSURE_SERIALIZER.get();
		}

		@Override
		@SuppressWarnings("unchecked")
		public <I extends net.minecraft.world.item.crafting.RecipeInput,
			R extends net.minecraft.world.item.crafting.Recipe<I>>
			net.minecraft.world.item.crafting.RecipeType<R> getType() {
			return (net.minecraft.world.item.crafting.RecipeType<R>)
				CBRecipeTypes.CREEPER_BLAST_CHAMBER_HIGH_PRESSURE_TYPE.get();
		}
	};

	private static final RandomSource RANDOM = RandomSource.create();

	private final List<ResultCountRange> resultCountRanges;
	private final List<IndexedResultCountRange> indexedResultCountRanges;
	private final boolean exclusiveResults;

	public CreeperBlastChamberHighPressureRecipe(Params params) {
		super(TYPE_INFO, params);
		exclusiveResults = params.exclusiveResults;
		indexedResultCountRanges = List.copyOf(params.resultCountRanges);
		resultCountRanges = new ArrayList<>();
		for (int i = 0; i < results.size(); i++)
			resultCountRanges.add(null);
		for (IndexedResultCountRange indexedRange : indexedResultCountRanges) {
			if (indexedRange.index() >= 0 && indexedRange.index() < resultCountRanges.size())
				resultCountRanges.set(indexedRange.index(),
					new ResultCountRange(indexedRange.min(), indexedRange.max()));
		}
	}

	@Override
	public boolean matches(RecipeWrapper inv, Level level) {
		return !inv.isEmpty() && !ingredients.isEmpty() && ingredients.getFirst().test(inv.getItem(0));
	}

	@Override
	protected int getMaxInputCount() {
		return 1;
	}

	@Override
	protected int getMaxOutputCount() {
		return 8;
	}

	@Override
	protected boolean canSpecifyDuration() {
		return true;
	}

	@Override
	public List<String> validate() {
		List<String> errors = super.validate();
		for (IndexedResultCountRange range : indexedResultCountRanges) {
			if (range.index() < 0 || range.index() >= results.size())
				errors.add("Result count range index is outside the result list: " + range.index());
			if (range.min() <= 0 || range.max() < range.min())
				errors.add("Invalid result count range: " + range.min() + "-" + range.max());
		}
		return errors;
	}

	public List<ItemStack> rollResults() {
		return rollResults(getRollableResults(), RANDOM);
	}

	@Override
	public List<ItemStack> rollResults(List<ProcessingOutput> rollableResults, RandomSource random) {
		if (exclusiveResults)
			return rollExclusiveResult(rollableResults, random);

		List<ItemStack> rolledResults = new ArrayList<>();
		for (int i = 0; i < rollableResults.size(); i++) {
			ProcessingOutput output = rollableResults.get(i);
			ResultCountRange range = getResultCountRange(i);

			ItemStack stack;
			if (range == null) {
				stack = output.rollOutput(random);
			} else {
				if (random.nextFloat() > output.getChance())
					continue;
				stack = output.getStack().copy();
				stack.setCount(range.roll(random));
			}

			if (!stack.isEmpty())
				rolledResults.add(stack);
		}
		return rolledResults;
	}

	private List<ItemStack> rollExclusiveResult(List<ProcessingOutput> rollableResults, RandomSource random) {
		float totalWeight = 0;
		for (ProcessingOutput output : rollableResults)
			totalWeight += Math.max(0, output.getChance());
		if (totalWeight <= 0)
			return List.of();

		float selectedWeight = random.nextFloat() * totalWeight;
		for (int i = 0; i < rollableResults.size(); i++) {
			ProcessingOutput output = rollableResults.get(i);
			float weight = Math.max(0, output.getChance());
			if (selectedWeight >= weight) {
				selectedWeight -= weight;
				continue;
			}

			ItemStack stack = output.getStack().copy();
			ResultCountRange range = getResultCountRange(i);
			if (range != null)
				stack.setCount(range.roll(random));
			return stack.isEmpty() ? List.of() : List.of(stack);
		}
		return List.of();
	}

	public ResultCountRange getResultCountRange(int index) {
		return index >= 0 && index < resultCountRanges.size() ? resultCountRanges.get(index) : null;
	}

	public boolean hasExclusiveResults() {
		return exclusiveResults;
	}

	public static class Params extends ProcessingRecipeParams {
		public static final MapCodec<Params> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
			codec(Params::new).forGetter(Function.identity()),
			Codec.BOOL.optionalFieldOf("exclusiveResults", false).forGetter(params -> params.exclusiveResults),
			IndexedResultCountRange.CODEC.listOf().optionalFieldOf("result_count_ranges", List.of())
				.forGetter(params -> params.resultCountRanges)
		).apply(instance, (params, exclusiveResults, resultCountRanges) -> {
			params.exclusiveResults = exclusiveResults;
			params.resultCountRanges = resultCountRanges;
			return params;
		}));
		public static final StreamCodec<RegistryFriendlyByteBuf, Params> STREAM_CODEC = streamCodec(Params::new);

		private boolean exclusiveResults;
		private List<IndexedResultCountRange> resultCountRanges = List.of();

		@Override
		protected void encode(RegistryFriendlyByteBuf buffer) {
			super.encode(buffer);
			ByteBufCodecs.BOOL.encode(buffer, exclusiveResults);
			buffer.writeVarInt(resultCountRanges.size());
			resultCountRanges.forEach(range -> IndexedResultCountRange.STREAM_CODEC.encode(buffer, range));
		}

		@Override
		protected void decode(RegistryFriendlyByteBuf buffer) {
			super.decode(buffer);
			exclusiveResults = ByteBufCodecs.BOOL.decode(buffer);
			int size = buffer.readVarInt();
			List<IndexedResultCountRange> ranges = new ArrayList<>(size);
			for (int i = 0; i < size; i++)
				ranges.add(IndexedResultCountRange.STREAM_CODEC.decode(buffer));
			resultCountRanges = ranges;
		}
	}

	public static class Serializer implements RecipeSerializer<CreeperBlastChamberHighPressureRecipe> {
		private final MapCodec<CreeperBlastChamberHighPressureRecipe> codec =
			ProcessingRecipe.codec(CreeperBlastChamberHighPressureRecipe::new, Params.CODEC);
		private final StreamCodec<RegistryFriendlyByteBuf, CreeperBlastChamberHighPressureRecipe> streamCodec =
			ProcessingRecipe.streamCodec(CreeperBlastChamberHighPressureRecipe::new, Params.STREAM_CODEC);

		@Override
		public MapCodec<CreeperBlastChamberHighPressureRecipe> codec() {
			return codec;
		}

		@Override
		public StreamCodec<RegistryFriendlyByteBuf, CreeperBlastChamberHighPressureRecipe> streamCodec() {
			return streamCodec;
		}
	}

	public record IndexedResultCountRange(int index, int min, int max) {
		public static final Codec<IndexedResultCountRange> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.INT.fieldOf("index").forGetter(IndexedResultCountRange::index),
			Codec.INT.fieldOf("min").forGetter(IndexedResultCountRange::min),
			Codec.INT.fieldOf("max").forGetter(IndexedResultCountRange::max)
		).apply(instance, IndexedResultCountRange::new));
		public static final StreamCodec<RegistryFriendlyByteBuf, IndexedResultCountRange> STREAM_CODEC =
			StreamCodec.of(
				(buffer, range) -> {
					buffer.writeVarInt(range.index());
					buffer.writeVarInt(range.min());
					buffer.writeVarInt(range.max());
				},
				buffer -> new IndexedResultCountRange(
					buffer.readVarInt(),
					buffer.readVarInt(),
					buffer.readVarInt()));
	}

	public record ResultCountRange(int min, int max) {
		public int roll(RandomSource random) {
			return min + random.nextInt(max - min + 1);
		}
	}
}
