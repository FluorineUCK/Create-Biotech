package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.network.EndpointConfiguration;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import com.nobodiiiii.createbiotech.foundation.gui.PatternGuiSkin;
import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen;
import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Explicit endpoint configuration and read-only same-cable peer directory. */
public final class ComputerCommunicationScreen extends AbstractSimiContainerScreen<ComputerProgramMenu> {
	private final ComputerExecutionScreen parent;
	private String nameText = "";
	private EditBox name;
	private boolean requested, nameEdited, nameSeeded;
	private long responseVersion;
	private int page;
	private List<String> peerLines = List.of();
	private DataValue.RecordValue directorySnapshot;
	private DataValue.RecordValue communicationSnapshot;
	private CreateTextButton previous, next;
	private Component notice = Component.empty();
	private Component endpointSummary = Component.empty();
	private ComputerControlSession.Action requestedAction;

	public ComputerCommunicationScreen(ComputerProgramMenu menu, Inventory inventory, Component title, ComputerExecutionScreen parent) {
		super(menu, inventory, title); this.parent = Objects.requireNonNull(parent); responseVersion = menu.controlResponseVersion();
	}
	@Override protected void init() {
		setWindowSize(Math.min(580, width - 12), Math.min(380, height - 12)); super.init(); setFocused(null); clearWidgets();
        button(imageWidth - 172, 5, 80, "instrument", () -> minecraft.setScreen(
            new ResidentInstrumentScreen(menu, minecraft.player.getInventory(), title, this)));
        button(imageWidth - 88, 5, 80, "radio", () -> minecraft.setScreen(
            new ComputerRadioScreen(menu, minecraft.player.getInventory(), title, this)));
		name = addRenderableWidget(new EditBox(font,leftPos+8,topPos+34,imageWidth-16,18,text("name")));
		name.setMaxLength(EndpointConfiguration.MAX_NAME_LENGTH + 1);
		name.setValue(nameText);
		name.setHint(text("name"));
		name.setResponder(value -> { nameText = value; nameEdited = true; });
		int column=(imageWidth-20)/2;
		button(8,58,column,"save_enable",this::save);
		button(12+column,58,column,"disable",()->send(ComputerControlSession.Action.DISABLE_ENDPOINT));
		button(8,82,column,"clear",()->send(ComputerControlSession.Action.CLEAR_ENDPOINT));
		button(12+column,82,column,"read",()->send(ComputerControlSession.Action.READ_ENDPOINT));
		button(8,106,column,"discover",()->send(ComputerControlSession.Action.DISCOVER_PEERS));
		button(12+column,106,column,"messages",()->send(ComputerControlSession.Action.READ_COMMUNICATION));
		previous=button(8,imageHeight-27,32,"previous",()->{page--;refreshPages();});
		next=button(44,imageHeight-27,32,"next",()->{page++;refreshPages();});
		int toolsWidth = (imageWidth - 172) / 3;
		button(84,imageHeight-27,toolsWidth,"wiring",()->minecraft.setScreen(new ComputerWiringScreen(menu, minecraft.player.getInventory(), title, this)));
		button(88+toolsWidth,imageHeight-27,toolsWidth,"serial",()->minecraft.setScreen(new ComputerSerialScreen(menu, minecraft.player.getInventory(), title, this)));
		button(92+2*toolsWidth,imageHeight-27,toolsWidth,"signal",()->minecraft.setScreen(new ComputerSignalScreen(menu, minecraft.player.getInventory(), title, this)));
		button(imageWidth-76,imageHeight-27,68,"back",this::onClose);
		// Resize reflows the same immutable snapshot; it must not issue another world read.
		if (communicationSnapshot != null) acceptCommunication(communicationSnapshot);
		else if (directorySnapshot != null) acceptDirectory(directorySnapshot); else refreshPages();
	}
	private CreateTextButton button(int x,int y,int width,String key,Runnable action){return addRenderableWidget(new CreateTextButton(font,leftPos+x,topPos+y,width,20,text(key),action,true));}
	private void save() {
		try {
			var config = new EndpointConfiguration(nameText, true);
			requestedAction = ComputerControlSession.Action.CONFIGURE_ENDPOINT;
			menu.control(requestedAction, config.value());
			notice = debug("waiting");
		} catch (IllegalArgumentException invalid) { notice = debug("invalid", invalid.getMessage()); }
	}
	private void send(ComputerControlSession.Action action) {
		requestedAction = action;
		menu.control(action, new DataValue.RecordValue(Map.of()));
		notice = debug("waiting");
	}
	@Override protected void containerTick() {
		super.containerTick();
		if (!requested) { requested = true; send(ComputerControlSession.Action.READ_ENDPOINT); }
		if (responseVersion == menu.controlResponseVersion()) return;
		responseVersion = menu.controlResponseVersion();
		menu.controlResponse().ifPresent(reply -> {
			notice = debug("result." + reply.code(), reply.detail());
			if ((requestedAction == ComputerControlSession.Action.DISCOVER_PEERS || requestedAction == ComputerControlSession.Action.READ_COMMUNICATION)
				&& !reply.code().equals("ok")) clearDirectory();
		});
		menu.endpointStatus().ifPresent(this::acceptEndpoint);
		menu.peerDirectory().ifPresent(this::acceptDirectory);
		menu.communicationStatus().ifPresent(this::acceptCommunication);
	}
	private void clearDirectory() {
		directorySnapshot = null; communicationSnapshot = null; peerLines = List.of(); page = 0; refreshPages();
	}
	private void acceptEndpoint(DataValue.RecordValue status){
		try{
			DataValue endpoint=status.field("endpoint"); DataValue diagnostic=status.field("endpoint_diagnostic");
			if(diagnostic instanceof DataValue.TextValue text)notice=text("diagnostic",BrowserLiteralDisplay.quote(text.value()));
			else if(diagnostic!=DataValue.NullValue.INSTANCE)throw new IllegalArgumentException("invalid diagnostic");
			EndpointConfiguration config=endpoint instanceof DataValue.RecordValue record?EndpointConfiguration.decode(record):null;
			endpointSummary=config==null?text("not_configured"):text(config.enabled()?"endpoint_enabled":"endpoint_disabled",BrowserLiteralDisplay.quote(config.name()));
			if(!nameSeeded){if(!nameEdited&&config!=null)name.setValue(config.name());nameSeeded=true;}
			if(endpoint!=DataValue.NullValue.INSTANCE&&!(endpoint instanceof DataValue.RecordValue))throw new IllegalArgumentException("invalid endpoint");
		}catch(IllegalArgumentException invalid){notice=debug("invalid",invalid.getMessage());}
	}
	private void acceptDirectory(DataValue.RecordValue directory){
		try{
			var lines=new ArrayList<String>();
			String dimension=((DataValue.TextValue)directory.field("dimension")).value(); long time=((DataValue.IntegerValue)directory.field("game_time")).value();
			appendWrapped(lines, text("snapshot",BrowserLiteralDisplay.quote(dimension),time));
			appendSegments(lines, directory.fields().get("cable_segments"));
			var peers=(DataValue.RecordValue)directory.field("peers");
			peers.fields().forEach((literal,value)->{
				var peer=(DataValue.RecordValue)value; boolean ambiguous=((DataValue.BooleanValue)peer.field("ambiguous")).value();
				appendWrapped(lines, text(ambiguous?"peer_ambiguous":"peer",BrowserLiteralDisplay.quote(literal)));
				for(DataValue endpoint:((DataValue.ListValue)peer.field("endpoints")).values()){
					var position=(DataValue.RecordValue)((DataValue.RecordValue)endpoint).field("position");
					appendWrapped(lines, text("position",((DataValue.IntegerValue)position.field("x")).value(),
						((DataValue.IntegerValue)position.field("y")).value(),((DataValue.IntegerValue)position.field("z")).value()));
					var pinList = ((DataValue.RecordValue)endpoint).fields().get("xbus_pins");
					if (pinList != null) {
						var pins = new com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwarePins(
							((DataValue.ListValue)pinList).values().stream().map(pin -> ((DataValue.TextValue)pin).value()).toList(), List.of()).xbus();
						if (!pins.isEmpty()) appendWrapped(lines, text("xbus_discovery_only"));
						for (String pin : pins) appendWrapped(lines, text("xbus_pin", BrowserLiteralDisplay.quote(pin)));
					}
				}
			});
			directorySnapshot = directory;
			communicationSnapshot = null;
			peerLines = List.copyOf(lines);
			refreshPages();
		} catch (RuntimeException invalid) {
			clearDirectory();
			notice = debug("invalid", String.valueOf(invalid.getMessage()));
		}
	}
	private void appendSegments(List<String> lines, DataValue value) {
		if (value == null) return; // Older read-only snapshots did not include physical component metadata.
		if (!(value instanceof DataValue.ListValue segments)) throw new IllegalArgumentException("Invalid cable segments");
		appendWrapped(lines, text("segments.header"));
		if (segments.values().isEmpty()) appendWrapped(lines, text("segments.empty"));
		for (int i = 0; i < segments.values().size(); i++) {
			var segment = (DataValue.RecordValue) segments.values().get(i);
			var faces = (DataValue.ListValue) segment.field("source_faces");
			var labels = new ArrayList<String>();
			for (DataValue face : faces.values()) {
				var direction = net.minecraft.core.Direction.byName(((DataValue.TextValue) face).value());
				if (direction == null) throw new IllegalArgumentException("Invalid cable face");
				labels.add(text("face." + direction.getName()).getString());
			}
			long cables = ((DataValue.IntegerValue) segment.field("cable_blocks")).value();
			long devices = ((DataValue.IntegerValue) segment.field("connected_devices")).value();
			if (labels.isEmpty() || cables < 0 || devices < 0) throw new IllegalArgumentException("Invalid cable segment counts");
			appendWrapped(lines, text("segments.entry", i + 1, String.join(", ", labels), cables, devices));
		}
		appendWrapped(lines, text("segments.note"));
	}
	private void acceptCommunication(DataValue.RecordValue status) {
		try {
			var values = (DataValue.RecordValue)status.field("communication");
			var lines = new ArrayList<String>();
			appendWrapped(lines, text("status.frozen"));
			values.fields().forEach((key, value) -> {
				if (key.equals("last_transport") && value instanceof DataValue.RecordValue last) {
					last.fields().forEach((part, field) -> statusLine(lines, key + "." + part, field));
				} else statusLine(lines, key, value);
			});
			if (communicationSnapshot != status) page = 0;
			communicationSnapshot = status; directorySnapshot = null; peerLines = List.copyOf(lines); refreshPages();
		} catch (RuntimeException invalid) {
			clearDirectory(); notice = debug("invalid", String.valueOf(invalid.getMessage()));
		}
	}
	private void statusLine(List<String> lines, String key, DataValue value) {
		String display = switch (value) {
			case DataValue.NullValue ignored -> text("status.none").getString();
			case DataValue.BooleanValue flag -> text(flag.value() ? "status.yes" : "status.no").getString();
			case DataValue.IntegerValue count -> Long.toString(count.value());
			case DataValue.TextValue literal -> key.equals("next_endpoint") ? BrowserLiteralDisplay.quote(literal.value())
				: text("status.code." + literal.value().toLowerCase(Locale.ROOT)).getString();
			default -> throw new IllegalArgumentException("Unexpected communication metadata");
		};
		appendWrapped(lines, Component.literal(text("status." + key).getString() + ": " + display));
	}
	private void appendWrapped(List<String> lines, Component component) {
		String remaining = component.getString();
		int width = imageWidth - 16;
		while (!remaining.isEmpty()) {
			String line = font.plainSubstrByWidth(remaining, width);
			if (line.isEmpty()) line = remaining.substring(0, Character.charCount(remaining.codePointAt(0)));
			if (Character.isHighSurrogate(line.charAt(line.length() - 1))) line = line.substring(0, line.length() - 1);
			if (line.isEmpty()) line = remaining.substring(0, Character.charCount(remaining.codePointAt(0)));
			lines.add(line); remaining = remaining.substring(line.length());
		}
	}
	private int pageSize() { return Math.max(1, (imageHeight - 160 - 38) / 12); }
	private void refreshPages() {
		var slice = ComputerValueDisplay.page(peerLines, page, pageSize());
		page = slice.index();
		if (previous != null) previous.active = page > 0;
		if (next != null) next.active = page + 1 < slice.count();
	}
	@Override protected void renderBg(GuiGraphics graphics, float partial, int mouseX, int mouseY) {
		PatternGuiSkin.blit(graphics, PatternGuiSkin.SURFACE, leftPos, topPos, imageWidth, imageHeight);
		PatternGuiSkin.blit(graphics, PatternGuiSkin.HEADER, leftPos+4, topPos+4, imageWidth-8, 22);
		graphics.drawString(font, font.plainSubstrByWidth(text("title").getString(), imageWidth-104), leftPos+10, topPos+10, 0xffeeeeee, false);
		line(graphics, endpointSummary, 130, 0xffaaccee);
		line(graphics, notice, 144, 0xffffddaa);
		var slice = ComputerValueDisplay.page(peerLines, page, pageSize());
		for (int row = 0; row < slice.lines().size(); row++)
			graphics.drawString(font, slice.lines().get(row), leftPos+8, topPos+160+row*12, 0xffdddddd, false);
	}
	private void line(GuiGraphics graphics, Component value, int y, int color) {
		graphics.drawString(font, font.plainSubstrByWidth(value.getString(), imageWidth-16), leftPos+8, topPos+y, color, false);
	}
	@Override protected void renderLabels(GuiGraphics graphics,int mouseX,int mouseY){}
	@Override public void onClose(){minecraft.setScreen(parent);}
	@Override public void removed(){}
	private static Component text(String key,Object...args){return Component.translatable("create_biotech.biocomputing.communication."+key,args);}
	private static Component debug(String key,Object...args){return Component.translatable("create_biotech.biocomputing.debug."+key,args);}
}
