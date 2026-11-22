package com.nobodiiiii.createbiotech.content.factorycluster.panel.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.nobodiiiii.createbiotech.content.factorycluster.SpaceAddress;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ClusterServiceRole;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsNetworkProjection.NetworkView;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsNetworkProjection.RemoteCapability;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelOpening;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelView;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.network.ClusterCardProjection;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.network.CommunicationNetworkProjection;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.network.CommunicationNetworkResponsePacket;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.network.CommunicationPage;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.network.ListenerEditorProjection;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.network.LocalCommunicationEditProjection;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.network.LogisticsNetworkResponsePacket;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

class PanelEditRefreshPreservationTest {
	private static final FactoryPanelOpening OPENING = new FactoryPanelOpening(
		new SpaceAddress(Level.OVERWORLD, null, BlockPos.ZERO), id(1), id(2), id(3), id(4));
	private static final String PLANT_WEST = "plant-west";

	@org.junit.jupiter.api.Test
	void communicationClicksGiveOneEligibleFieldKeyboardFocusAndClearItWhenHidden() {
		CommunicationNetworkView view = new CommunicationNetworkView(
			new FactoryPanelViewState().forView(FactoryPanelView.NETWORK_COMMUNICATION));
		HeadlessEditBox primary = box(94, 183, 126);
		HeadlessEditBox listener = box(10, 211, 210);
		attach(view, "primary", primary);
		attach(view, "listener", listener);
		HeadlessPanelScreen screen = screen(view);
		set(view, "owner", screen);
		CommunicationEditState edits = field(view, "edits", CommunicationEditState.class);
		primary.setResponder(edits::editPrimary);
		listener.setResponder(edits::editListener);

		assertTrue(accept(view, overview("primary", 3), view.beginRequest()));
		assertFalse(primary.isFocused());
		assertFalse(listener.isFocused());
		assertTrue(view.mouseClicked(screen, 100, 184, 0));
		assertTrue(primary.isFocused());
		assertEquals(primary, screen.getFocused());
		assertEquals(1, primary.clicks());
		assertEquals(2, primary.getCursorPosition(), "click dispatch places the caret");
		assertTrue(screen.charTyped('x', 0));
		assertEquals("prximary", primary.getValue());
		assertTrue(screen.keyPressed(69, 0, 0), "typing inventory key is consumed while focused");

		assertTrue(accept(view, overview("primary", 3), view.beginRefreshRequest()));
		assertEquals("prximary", primary.getValue(), "periodic refresh retains the dirty draft");
		assertFalse(view.mouseClicked(screen, 223, 70, 0),
			"view dispatch now only clears focus before the registered widget handles the tab");
		selectPage(view, screen, CommunicationPage.LISTENERS);
		assertEquals(CommunicationPage.LISTENERS, view.communicationPage());
		assertTrue(screen.requested());
		assertFalse(primary.isFocused(), "tab navigation releases text focus");
		assertTrue(accept(view, listeners("listener", 3), view.beginRefreshRequest()));
		assertTrue(view.mouseClicked(screen, 11, 212, 0));
		assertFalse(primary.isFocused(), "switching fields releases the former field");
		assertTrue(listener.isFocused());
		assertEquals(listener, screen.getFocused());
		assertTrue(screen.charTyped('z', 0));
		assertEquals("prximary", primary.getValue());
		assertEquals("zlistener", listener.getValue());

		assertFalse(view.mouseClicked(screen, 1, 1, 0));
		assertFalse(listener.isFocused(), "outside click releases text focus");
		assertFalse(view.charTyped(screen, 'q', 0));
		assertEquals("zlistener", listener.getValue());

		assertTrue(view.mouseClicked(screen, 11, 212, 0));
		view.deactivated(screen);
		assertFalse(listener.isFocused(), "view navigation releases text focus");
		assertTrue(view.mouseClicked(screen, 11, 212, 0));
		assertTrue(accept(view, CommunicationNetworkProjection.stale(OPENING.clusterId(),
			OPENING.epochId(), 1, 3, 2), view.beginRefreshRequest()));
		assertFalse(listener.isFocused(), "stale capability loss releases text focus");
		assertFalse(view.charTyped(screen, 'q', 0));
	}

	private static void selectPage(CommunicationNetworkView view, FactoryPanelScreen screen,
		CommunicationPage page) {
		try {
			var method = CommunicationNetworkView.class.getDeclaredMethod("selectPage",
				FactoryPanelScreen.class, CommunicationPage.class);
			method.setAccessible(true);
			method.invoke(view, screen, page);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("registered Communication page button callback changed", exception);
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void equivalentCurrentRefreshPreservesPrimaryEditBoxSelection(boolean dirty) {
		CommunicationNetworkView view = new CommunicationNetworkView(
			new FactoryPanelViewState().forView(FactoryPanelView.NETWORK_COMMUNICATION));
		EditBox box = box();
		attach(view, "primary", box);
		attach(view, "listener", box());
		CommunicationEditState edits = field(view, "edits", CommunicationEditState.class);
		box.setResponder(edits::editPrimary);
		String authoritative = dirty ? "server" : PLANT_WEST;

		assertTrue(accept(view, overview(authoritative, 3), view.beginRequest()));
		if (dirty) box.setValue(PLANT_WEST);
		selectWest(box);

		assertTrue(accept(view, overview(authoritative, 3), view.beginRequest()));
		assertReplacement(box);
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void equivalentCurrentRefreshPreservesListenerEditBoxSelection(boolean dirty) {
		CommunicationNetworkView view = new CommunicationNetworkView(
			new FactoryPanelViewState().forView(FactoryPanelView.NETWORK_COMMUNICATION));
		set(view, "page", CommunicationPage.LISTENERS);
		EditBox box = box();
		attach(view, "listener", box);
		attach(view, "primary", box());
		CommunicationEditState edits = field(view, "edits", CommunicationEditState.class);
		box.setResponder(edits::editListener);
		String authoritative = dirty ? "server" : PLANT_WEST;

		assertTrue(accept(view, listeners(authoritative, 3), view.beginRequest()));
		if (dirty) box.setValue(PLANT_WEST);
		selectWest(box);

		assertTrue(accept(view, listeners(authoritative, 3), view.beginRequest()));
		assertReplacement(box);
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void equivalentCurrentRefreshPreservesAliasEditBoxSelection(boolean dirty) {
		LogisticsNetworkView view = new LogisticsNetworkView(
			new FactoryPanelViewState().forView(FactoryPanelView.NETWORK_LOGISTICS));
		EditBox box = box();
		attach(view, "alias", box);
		LogisticsAliasEditState edits = field(view, "aliasEdit", LogisticsAliasEditState.class);
		box.setResponder(edits::edit);
		String authoritative = dirty ? "server" : PLANT_WEST;

		assertTrue(accept(view, logistics(authoritative, 3), view.beginRequest()));
		if (dirty) box.setValue(PLANT_WEST);
		selectWest(box);

		assertTrue(accept(view, logistics(authoritative, 3), view.beginRequest()));
		assertReplacement(box);
	}

	@ParameterizedTest
	@ValueSource(strings = {"plant-east", "plant-north"})
	void authoritativeRebaseStillUpdatesPrimaryEditBox(String authoritative) {
		CommunicationNetworkView view = new CommunicationNetworkView(
			new FactoryPanelViewState().forView(FactoryPanelView.NETWORK_COMMUNICATION));
		EditBox box = box();
		attach(view, "primary", box);
		attach(view, "listener", box());
		CommunicationEditState edits = field(view, "edits", CommunicationEditState.class);
		box.setResponder(edits::editPrimary);
		assertTrue(accept(view, overview(PLANT_WEST, 3), view.beginRequest()));
		selectWest(box);

		assertTrue(accept(view, overview(authoritative, 4), view.beginRefreshRequest()));
		assertEquals(authoritative, box.getValue());
		assertEquals(authoritative.length(), box.getCursorPosition());
		assertEquals("", box.getHighlighted());
	}

	@org.junit.jupiter.api.Test
	void staleAndSupersededCommunicationPacketsDoNotTouchThePrimaryEditBox() {
		CommunicationNetworkView view = new CommunicationNetworkView(
			new FactoryPanelViewState().forView(FactoryPanelView.NETWORK_COMMUNICATION));
		EditBox box = box();
		attach(view, "primary", box);
		attach(view, "listener", box());
		CommunicationEditState edits = field(view, "edits", CommunicationEditState.class);
		box.setResponder(edits::editPrimary);
		assertTrue(accept(view, overview(PLANT_WEST, 3), view.beginRequest()));
		selectWest(box);

		long staleIntent = view.beginRequest();
		assertFalse(accept(view, CommunicationNetworkProjection.stale(OPENING.clusterId(),
			OPENING.epochId(), 1, 3, 2), staleIntent));
		long superseded = view.beginRefreshRequest();
		long fresh = view.beginRefreshRequest();
		assertFalse(accept(view, overview(PLANT_WEST, 3), superseded));
		assertTrue(accept(view, overview(PLANT_WEST, 3), fresh));
		assertReplacement(box);
	}

	private static void selectWest(EditBox box) {
		assertEquals(PLANT_WEST, box.getValue());
		box.setCursorPosition(6);
		box.setHighlightPos(10);
		assertEquals(6, box.getCursorPosition());
		assertEquals("west", box.getHighlighted());
	}

	private static void assertReplacement(EditBox box) {
		assertEquals(6, box.getCursorPosition());
		assertEquals("west", box.getHighlighted());
		box.insertText("east");
		assertEquals("plant-east", box.getValue());
	}

	private static EditBox box() {
		return box(0, 0, 220);
	}

	private static HeadlessEditBox box(int x, int y, int width) {
		HeadlessEditBox box = new HeadlessEditBox(new HeadlessFont(), x, y, width);
		box.setMaxLength(64);
		box.setEditable(true);
		return box;
	}

	private static HeadlessPanelScreen screen(CommunicationNetworkView view) {
		HeadlessPanelScreen screen = allocate(HeadlessPanelScreen.class);
		setInHierarchy(screen, "views", Map.of(FactoryPanelView.NETWORK_COMMUNICATION, view));
		setInHierarchy(screen, "selectedView", FactoryPanelView.NETWORK_COMMUNICATION);
		return screen;
	}

	private static EditBox editBox(Font font, int x, int y, int width) {
		try {
			Object message = Class.forName("net.minecraft.network.chat.Component")
				.getMethod("empty").invoke(null);
			for (var constructor : EditBox.class.getConstructors())
				if (constructor.getParameterCount() == 6)
					return (EditBox) constructor.newInstance(font, x, y, width, 20, message);
			throw new AssertionError("EditBox constructor unavailable");
		} catch (ReflectiveOperationException failure) {
			throw new AssertionError(failure);
		}
	}

	private static NetworkView networkView(UUID network, String alias, long revision) {
		try {
			Object displayName = Class.forName("net.minecraft.network.chat.Component")
				.getMethod("literal", String.class).invoke(null, "Network");
			for (var constructor : NetworkView.class.getConstructors())
				if (constructor.getParameterCount() == 4)
					return (NetworkView) constructor.newInstance(network, displayName,
						Optional.of(alias), revision);
			throw new AssertionError("NetworkView constructor unavailable");
		} catch (ReflectiveOperationException failure) {
			throw new AssertionError(failure);
		}
	}

	private static CommunicationNetworkProjection overview(String primary, long revision) {
		ClusterCardProjection local = ClusterCardProjection.cluster(OPENING.clusterId(),
			ClusterServiceRole.STANDARD, ClusterCardProjection.FormationState.FORMED,
			ClusterCardProjection.OperationalState.ONLINE, 0, 1, 0, 0,
			ClusterCardProjection.LinkType.ADJACENT, CommunicationFrequency.of(primary), List.of(),
			false, false);
		return new CommunicationNetworkProjection(OPENING.clusterId(), OPENING.epochId(), 1, revision,
			CommunicationNetworkProjection.Status.CURRENT, 0, 1, List.of(local), 2,
			Optional.of(new LocalCommunicationEditProjection(0, List.of())), CommunicationPage.OVERVIEW);
	}

	private static CommunicationNetworkProjection listeners(String listener, long revision) {
		ListenerEditorProjection editor = new ListenerEditorProjection("listener-0000000000000001",
			List.of(CommunicationFrequency.of(listener)), 0);
		return new CommunicationNetworkProjection(OPENING.clusterId(), OPENING.epochId(), 1, revision,
			CommunicationNetworkProjection.Status.CURRENT, 0, 1, List.of(), 2,
			Optional.of(new LocalCommunicationEditProjection(0, List.of(editor))),
			CommunicationPage.LISTENERS);
	}

	private static LogisticsNetworkResponsePacket.Projection logistics(String alias, long revision) {
		UUID network = id(5);
		return new LogisticsNetworkResponsePacket.Projection(OPENING.clusterId(), OPENING.epochId(),
			revision, LogisticsNetworkResponsePacket.Status.CURRENT, Optional.of(network), 0, 0,
			List.of(networkView(network, alias, revision)),
			List.of(), Set.of(RemoteCapability.VIEW_LOGISTICS_DIRECTORY), 1);
	}

	private static boolean accept(CommunicationNetworkView view, CommunicationNetworkProjection projection,
		long intent) {
		return view.accept(new CommunicationNetworkResponsePacket(OPENING, projection, intent), OPENING);
	}

	private static boolean accept(LogisticsNetworkView view,
		LogisticsNetworkResponsePacket.Projection projection, long intent) {
		return view.accept(new LogisticsNetworkResponsePacket(OPENING, projection, intent), OPENING);
	}

	private static UUID id(long value) { return new UUID(0, value); }

	private static void attach(Object view, String name, EditBox box) { set(view, name, box); }

	private static void set(Object target, String name, Object value) {
		try {
			Field field = target.getClass().getDeclaredField(name);
			field.setAccessible(true);
			field.set(target, value);
		} catch (ReflectiveOperationException failure) {
			throw new AssertionError(failure);
		}
	}

	private static <T> T field(Object target, String name, Class<T> type) {
		try {
			Field field = target.getClass().getDeclaredField(name);
			field.setAccessible(true);
			return type.cast(field.get(target));
		} catch (ReflectiveOperationException failure) {
			throw new AssertionError(failure);
		}
	}

	@SuppressWarnings("unchecked")
	private static <T> T allocate(Class<T> type) {
		try {
			Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
			unsafeField.setAccessible(true);
			return (T) ((sun.misc.Unsafe) unsafeField.get(null)).allocateInstance(type);
		} catch (ReflectiveOperationException failure) {
			throw new AssertionError(failure);
		}
	}

	private static void setInHierarchy(Object target, String name, Object value) {
		try {
			for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
				try {
					Field field = type.getDeclaredField(name);
					field.setAccessible(true);
					field.set(target, value);
					return;
				} catch (NoSuchFieldException ignored) {}
			}
			throw new NoSuchFieldException(name);
		} catch (ReflectiveOperationException failure) {
			throw new AssertionError(failure);
		}
	}

	/** Replaces only unbootstrapped window/sound click plumbing while recording its dispatch. */
	private static final class HeadlessEditBox extends EditBox {
		private int clicks;
		private final Font metrics;

		HeadlessEditBox(Font font, int x, int y, int width) {
			super(font, x, y, width, 20, null);
			metrics = font;
		}

		@Override
		public boolean mouseClicked(double mouseX, double mouseY, int button) {
			if (button != 0 || !isMouseOver(mouseX, mouseY)) return false;
			clicks++;
			int textX = Math.max(0, (int) Math.floor(mouseX) - getX() - 4);
			int caret = metrics.plainSubstrByWidth(getValue(), textX).length();
			setCursorPosition(caret);
			setHighlightPos(caret);
			return true;
		}

		int clicks() { return clicks; }
	}

	/** Avoids container/bootstrap construction but invokes the inherited real focus owner. */
	private static final class HeadlessPanelScreen extends FactoryPanelScreen {
		private boolean requested;

		private HeadlessPanelScreen() { super(null, null, null); }

		@Override void requestActive() { requested = true; }

		@Override boolean isInventoryKey(int keyCode, int scanCode) { return keyCode == 69; }

		boolean requested() { return requested; }
	}

	/** Font only supplies deterministic metrics; EditBox itself remains the production widget. */
	private static final class HeadlessFont extends Font {
		HeadlessFont() { super(ignored -> null, false); }

		@Override public int width(String text) { return text.length(); }

		@Override public String plainSubstrByWidth(String text, int width) {
			return text.substring(0, Math.min(text.length(), Math.max(0, width)));
		}

		@Override public String plainSubstrByWidth(String text, int width, boolean reverse) {
			if (!reverse) return plainSubstrByWidth(text, width);
			return text.substring(Math.max(0, text.length() - Math.max(0, width)));
		}
	}
}
