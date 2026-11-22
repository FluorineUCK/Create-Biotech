package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

import com.mojang.brigadier.context.StringRange;
import com.mojang.brigadier.suggestion.Suggestion;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.equipment.clipboard.ClipboardContent;
import com.simibubi.create.content.equipment.clipboard.ClipboardEntry;
import com.simibubi.create.content.logistics.AddressEditBox;
import com.simibubi.create.content.trains.schedule.DestinationSuggestions;

import net.createmod.catnip.data.IntAttached;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Client-only literal frequency editor.
 *
 * <p>Create 6.0.10 keeps its {@link DestinationSuggestions} in a private
 * {@code AddressEditBox} field. Replacing that exact live instance lets the
 * inherited render, keyboard, mouse, right-click-clear, and tick paths retain
 * Create's dropdown behaviour while changing only matching to literal text.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class CommunicationFrequencyEditBox extends AddressEditBox {
	private static final Field DESTINATION_SUGGESTIONS = destinationSuggestionsField();
	private static final Field COMMAND_SUGGESTIONS = commandSuggestionsField();
	private static final Constructor<?> SUGGESTIONS_LIST = suggestionsListConstructor();

	private final List<CommunicationFrequency> knownSuggestions;

	public CommunicationFrequencyEditBox(Screen screen, Font font, int x, int y, int width,
		int height, boolean anchorToBottom, Collection<CommunicationFrequency> authorizedKnown) {
		super(Objects.requireNonNull(screen, "screen"), Objects.requireNonNull(font, "font"),
			x, y, width, height, anchorToBottom);
		setMaxLength(CommunicationFrequency.MAX_LENGTH);
		knownSuggestions = literalSuggestions(clipboardLines(),
			Objects.requireNonNull(authorizedKnown, "authorizedKnown"));
		installLiteralSuggestions(new LiteralDestinationSuggestions(Minecraft.getInstance(), screen, this,
			font, options(knownSuggestions), anchorToBottom));
	}

	/** Used by the client-only AddressEditBox constructor redirect before this subclass is initialized. */
	public static DestinationSuggestions inertSuperclassSuggestions(Screen screen, EditBox textBox,
		boolean anchorToBottom) {
		Minecraft minecraft = Minecraft.getInstance();
		return new DestinationSuggestions(minecraft, screen, textBox, minecraft.font, List.of(),
			anchorToBottom, FrequencySuggestionBehavior.dropdownYOffset(textBox.getY(),
				textBox.getHeight(), anchorToBottom));
	}

	private static Field destinationSuggestionsField() {
		try {
			Field field = AddressEditBox.class.getDeclaredField("destinationSuggestions");
			field.setAccessible(true);
			return field;
		} catch (ReflectiveOperationException unavailable) {
			throw new IllegalStateException("Create 6.0.10 AddressEditBox suggestion API changed", unavailable);
		}
	}

	private static Field commandSuggestionsField() {
		try {
			Field field = CommandSuggestions.class.getDeclaredField("suggestions");
			field.setAccessible(true);
			return field;
		} catch (ReflectiveOperationException unavailable) {
			throw new IllegalStateException("Minecraft CommandSuggestions API changed", unavailable);
		}
	}

	private static Constructor<?> suggestionsListConstructor() {
		for (Constructor<?> constructor : CommandSuggestions.SuggestionsList.class.getDeclaredConstructors()) {
			int parameterCount = constructor.getParameterCount();
			if (parameterCount == 5 || parameterCount == 6) {
				constructor.setAccessible(true);
				return constructor;
			}
		}
		throw new IllegalStateException("Minecraft suggestion dropdown API changed");
	}

	private void installLiteralSuggestions(DestinationSuggestions suggestions) {
		try {
			DESTINATION_SUGGESTIONS.set(this, suggestions);
		} catch (IllegalAccessException unavailable) {
			throw new IllegalStateException("Cannot install literal Create destination suggestions", unavailable);
		}
	}

	private static void setDropdown(CommandSuggestions suggestions, Object dropdown) {
		try {
			COMMAND_SUGGESTIONS.set(suggestions, dropdown);
		} catch (IllegalAccessException unavailable) {
			throw new IllegalStateException("Cannot update literal frequency dropdown", unavailable);
		}
	}

	private static Object dropdown(CommandSuggestions owner, int x, int y, int width,
		List<Suggestion> suggestions) {
		try {
			return SUGGESTIONS_LIST.getParameterCount() == 5
				? SUGGESTIONS_LIST.newInstance(x, y, width, suggestions, false)
				: SUGGESTIONS_LIST.newInstance(owner, x, y, width, suggestions, false);
		} catch (ReflectiveOperationException unavailable) {
			throw new IllegalStateException("Cannot create literal frequency dropdown", unavailable);
		}
	}

	private static List<IntAttached<String>> options(List<CommunicationFrequency> values) {
		List<IntAttached<String>> options = new ArrayList<>(values.size());
		for (CommunicationFrequency value : values)
			options.add(IntAttached.withZero(value.value()));
		return List.copyOf(options);
	}

	private static List<String> clipboardLines() {
		Player player = Minecraft.getInstance().player;
		if (player == null) return List.of();
		return FrequencySuggestionBehavior.boundedClipboardLines(player.getInventory().items,
			stack -> clipboardPages(stack), page -> page, entry -> entry.text.getString());
	}

	private static List<List<ClipboardEntry>> clipboardPages(ItemStack stack) {
		ClipboardContent content = stack.getComponents().get(AllDataComponents.CLIPBOARD_CONTENT);
		return content == null ? List.of() : content.pages();
	}

	/**
	 * Builds bounded literal suggestions. A leading clipboard {@code #} is only
	 * the clipboard marker; every later character, including {@code #}, is literal.
	 */
	public static List<CommunicationFrequency> literalSuggestions(Iterable<String> clipboardLines,
		Iterable<CommunicationFrequency> authorizedKnown) {
		Objects.requireNonNull(clipboardLines, "clipboardLines");
		Objects.requireNonNull(authorizedKnown, "authorizedKnown");
		LinkedHashSet<CommunicationFrequency> values = new LinkedHashSet<>();
		int linesRead = 0;
		Iterator<String> clipboardIterator = clipboardLines.iterator();
		while (linesRead < FrequencySuggestionBehavior.MAX_CLIPBOARD_ENTRIES
			&& values.size() < FrequencySuggestionBehavior.MAX_SUGGESTIONS
			&& clipboardIterator.hasNext()) {
			String line = clipboardIterator.next();
			linesRead++;
			if (line == null || !line.startsWith("#")) continue;
			try {
				values.add(CommunicationFrequency.of(line.substring(1)));
			} catch (IllegalArgumentException ignored) {
				// Invalid clipboard text cannot become an editable frequency.
			}
		}
		int knownRead = 0;
		Iterator<CommunicationFrequency> authorizedIterator = authorizedKnown.iterator();
		while (knownRead < FrequencySuggestionBehavior.MAX_SUGGESTIONS
			&& values.size() < FrequencySuggestionBehavior.MAX_SUGGESTIONS
			&& authorizedIterator.hasNext()) {
			CommunicationFrequency frequency = authorizedIterator.next();
			knownRead++;
			values.add(Objects.requireNonNull(frequency, "authorized frequency"));
		}
		return List.copyOf(values);
	}

	public List<CommunicationFrequency> knownSuggestions() {
		return knownSuggestions;
	}

	private static final class LiteralDestinationSuggestions extends DestinationSuggestions {
		private final EditBox textBox;
		private final Font font;
		private final List<IntAttached<String>> frequencies;
		private final boolean anchorToBottom;
		private FrequencySuggestionBehavior.SuggestionState state =
			FrequencySuggestionBehavior.SuggestionState.inactive();
		private List<Suggestion> currentSuggestions = List.of();

		private LiteralDestinationSuggestions(Minecraft minecraft, Screen screen, EditBox textBox,
			Font font, List<IntAttached<String>> frequencies, boolean anchorToBottom) {
			super(minecraft, screen, textBox, font, frequencies, anchorToBottom,
				FrequencySuggestionBehavior.dropdownYOffset(textBox.getY(), textBox.getHeight(),
					anchorToBottom));
			this.textBox = textBox;
			this.font = font;
			this.frequencies = frequencies;
			this.anchorToBottom = anchorToBottom;
		}

		@Override
		public void tick() {
			if (FrequencySuggestionBehavior.clearGhostTextWhenDropdownHidden(
				dropdownVisible() != null)) textBox.setSuggestion("");
			state = state.withFocus(textBox.isFocused());
			updateCommandInfo();
		}

		@Override
		public void updateCommandInfo() {
			String value = textBox.getValue();
			int cursor = textBox.getCursorPosition();
			if (state.shouldClearDropdown()) {
				currentSuggestions = List.of();
				setDropdown(this, null);
				return;
			}
			if (!state.needsRefresh(value, cursor)) return;
			state = state.refreshed(value, cursor);
			List<CommunicationFrequency> candidates = frequencies.stream()
				.map(candidate -> CommunicationFrequency.of(candidate.getValue())).toList();
			List<Suggestion> matches = FrequencySuggestionBehavior.literalMatches(value, cursor, candidates)
				.stream().map(match -> new Suggestion(new StringRange(0, match.rangeEnd()),
					match.text())).toList();
			currentSuggestions = List.copyOf(matches);
			showSuggestions(false);
		}

		@Override
		public void showSuggestions(boolean narrateFirstSuggestion) {
			if (currentSuggestions.isEmpty()) {
				setDropdown(this, null);
				return;
			}
			int width = 0;
			for (Suggestion suggestion : currentSuggestions)
				width = Math.max(width, font.width(suggestion.getText()));
			int x = Mth.clamp(textBox.getScreenX(0), 0,
				textBox.getScreenX(0) + textBox.getInnerWidth() - width);
			setDropdown(this, dropdown(this, x, FrequencySuggestionBehavior.dropdownY(textBox.getY(),
				textBox.getHeight(), anchorToBottom), width, currentSuggestions));
		}

		@Override
		public boolean isEmpty() {
			return frequencies.isEmpty();
		}

		private Object dropdownVisible() {
			try {
				return COMMAND_SUGGESTIONS.get(this);
			} catch (IllegalAccessException unavailable) {
				throw new IllegalStateException("Cannot inspect literal frequency dropdown", unavailable);
			}
		}
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (FrequencySuggestionBehavior.shouldClearOnRightClick(isMouseOver(mouseX, mouseY), button)) {
			setValue("");
			return true;
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}
}
