package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.*;
import com.nobodiiiii.createbiotech.foundation.gui.*;
import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen;
import java.util.*;
import static org.lwjgl.glfw.GLFW.*;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Frozen installed presentation with explicit one-shot actions; typing and paging stay local. */
public final class ComputerTerminalScreen extends AbstractSimiContainerScreen<ComputerProgramMenu> {
    private static final int BODY_TOP = 56;
    private final Screen parent;
    private final Inventory inventory;
    private Consumer<Screen> navigator = screen -> minecraft.setScreen(screen);
    private Consumer<ComputerControlSession.Action> request;
    private BiConsumer<Long, String> clickRequest;
    private Consumer<TerminalSubmit> submitRequest;
    private Consumer<TerminalTaskClaim> claimRequest;
    private BiConsumer<Long,String> keyRequest;
    private BiConsumer<Long,Long> keyStateRequest;
    private long lastKeyBits;
    private int keyRefresh;
    private final TerminalInputDrafts inputDrafts = new TerminalInputDrafts();
    private final Map<String, EditBox> inputFields = new HashMap<>();
    private boolean requested;
    private long responseVersion;
    private TerminalStatusView snapshot;
    private TerminalProjection.View view;
    private final Map<String, Integer> pages = new HashMap<>();
    private Map<String, CreateTextButton> pageNext = Map.of();
    private List<TerminalLayout.Cell> visible = List.of();
    private final Map<String, TerminalItemDisplay> items = new HashMap<>();
    private int scroll;
    private Component notice = text("waiting");
    private long publishedView;
    private boolean clickLocked;
    private boolean requestPending;
    private boolean submitPending;
    private boolean questionSubmitPending;
    private boolean claimPending;
    private boolean keyPending,keyboardArmed;
    private final Set<Integer> keysDown=new HashSet<>();
    private String questionDraft = "";
    private Component keyboardReply;

    public ComputerTerminalScreen(ComputerProgramMenu menu, Inventory inventory, Component title, Screen parent) {
        super(menu, inventory, title); this.parent = Objects.requireNonNull(parent); this.inventory = inventory;
        responseVersion = menu.controlResponseVersion();
        request = action -> menu.control(action, new DataValue.RecordValue(Map.of()));
        clickRequest = menu::terminalClick;
        submitRequest = submit -> menu.terminalSubmit(submit.view(), submit.instance(), submit.text());
        claimRequest = claim -> menu.terminalClaim(claim.view(), claim.index());
        keyRequest=menu::terminalKey;
        keyStateRequest=menu::terminalKeyState;
    }
    @Override protected void init() {
        setWindowSize(Math.min(580, width - 12), Math.min(420, height - 12)); super.init(); clearWidgets(); setFocused(null);
        button(8, 30, 72, text("refresh"), this::refresh).active = !requestPending;
        if(snapshot!=null && snapshot.keyboard()) {
            var keyboard=button(84,30,84,text(keyboardArmed?"keyboard_on":"keyboard_off"),()->{
                keyboardArmed=!keyboardArmed;keysDown.clear();publishKeys();keyboardReply=null;init();
            });
            keyboard.active=!snapshot.paused();keyboard.setTooltip(net.minecraft.client.gui.components.Tooltip.create(text("keyboard_hint")));
        }
        if (snapshot != null && snapshot.roots().fields().get("tasks") instanceof DataValue.ListValue tasks)
            button(imageWidth - 100, 30, 92, text("tasks", tasks.values().size()), () -> navigator.accept(
                new ComputerTerminalTasksScreen(menu, inventory, title, tasks, index -> {
                    navigator.accept(this); claim(index);
                }, () -> navigator.accept(this)))).active = !requestPending && !clickLocked && publishedView > 0;
        pageNext = new LinkedHashMap<>(); visible = List.of(); items.clear(); inputFields.clear();
        if (snapshot != null && snapshot.question().isPresent()) addQuestion();
        int contentHeight = 0;
        if (snapshot != null && snapshot.terminal().isPresent()) {
            try {
                int maxViews = menu.response().orElseThrow(() -> new IllegalArgumentException("Missing authoritative archive limits")).archiveLimits().maxNodes();
                int rowsPerPage = Math.max(1, bodyHeight() / TerminalLayout.ROW_HEIGHT - 1);
                view = TerminalProjection.project(snapshot, pages, rowsPerPage, maxViews);
                var layout = TerminalLayout.layout(view, imageWidth - 16); contentHeight = layout.height();
                scroll = Math.clamp(scroll, 0, Math.max(0, contentHeight - bodyHeight()));
                final int offset = scroll;
                visible = layout.cells().stream().filter(cell -> cell.y() >= offset && cell.y() + 20 <= offset + bodyHeight()).toList();
                for (var cell : visible) addCell(cell);
            } catch (IllegalArgumentException invalid) { view = null; notice = text("invalid", String.valueOf(invalid.getMessage())); }
        } else view = null;
        button(8, imageHeight - 27, 40, text("previous"), () -> { scroll = Math.max(0, scroll - bodyHeight()); init(); }).active = scroll > 0;
        final int totalHeight = contentHeight;
        button(52, imageHeight - 27, 40, text("next"), () -> { scroll = Math.min(Math.max(0, totalHeight - bodyHeight()), scroll + bodyHeight()); init(); }).active = scroll + bodyHeight() < contentHeight;
        button(imageWidth - 76, imageHeight - 27, 68, text("back"), this::onClose);
    }
    private int bodyTop() { return snapshot != null && snapshot.question().isPresent() ? BODY_TOP + 54 : BODY_TOP; }
    private int bodyHeight() { return Math.max(TerminalLayout.ROW_HEIGHT, (imageHeight - bodyTop() - 32) / TerminalLayout.ROW_HEIGHT * TerminalLayout.ROW_HEIGHT); }
    private void addQuestion() {
        int width = imageWidth - 16, submitWidth = Math.min(72, Math.max(44, width / 3));
        int y = BODY_TOP + 23;
        var field = addRenderableWidget(new EditBox(font, leftPos + 8, topPos + y, Math.max(1, width - submitWidth - 4), 20, text("answer")));
        field.setMaxLength(com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowPrompts.MAX_CHARS);
        field.setValue(questionDraft); field.setResponder(value -> questionDraft = value);
        field.active = !snapshot.paused();
        inputFields.put(TerminalSubmit.QUESTION, field);
        button(imageWidth - 8 - submitWidth, y, submitWidth, text("answer"), () -> submit(TerminalSubmit.QUESTION)).active =
            field.active && !requestPending && !clickLocked && publishedView > 0;
    }
    private void addCell(TerminalLayout.Cell cell) {
        var value = cell.view(); int x = 8 + cell.x(), y = bodyTop() + cell.y() - scroll, width = cell.width();
        if (value.page().isPresent()) {
            var page = value.page().orElseThrow();
            button(x, y, 20, Component.literal("<"), () -> page(value.instance(), page.index() - 1)).active = page.index() > 0;
            pageNext.put(value.instance(), button(x + 24, y, 20, Component.literal(">"), () -> page(value.instance(), page.index() + 1)));
            pageNext.get(value.instance()).active = page.index() + 1 < page.count();
            button(x + 48, y, Math.max(1, width - 48), text("page", page.index() + 1, page.count()), () -> inspect(value));
            return;
        }
        if (value.definition() instanceof TerminalDefinition.Button) {
            var resolved = value.button();
            Component label = resolved.<Component>map(button -> Component.literal(button.title()))
                .orElseGet(() -> problemLabel(value.problem().orElseGet(() -> new TerminalProjection.Problem("invalid_button", ""))));
            var control = button(x, y, Math.max(1, width), label, () -> click(value.instance()));
            control.active = resolved.map(TerminalProjection.ResolvedButton::enabled).orElse(false) && !clickLocked && !requestPending
                && snapshot != null && !snapshot.paused() && !snapshot.phase().equals("RUNNING");
            return;
        }
        if (value.definition() instanceof TerminalDefinition.Input) {
            var resolved = value.input();
            if (resolved.isEmpty()) { button(x, y, Math.max(1, width), problemLabel(value.problem().orElseGet(() -> new TerminalProjection.Problem("invalid_input", ""))), () -> inspect(value)); return; }
            var input = resolved.orElseThrow(); int submitWidth = Math.min(68, Math.max(44, width / 3));
            var field = addRenderableWidget(new EditBox(font, leftPos + x, topPos + y, Math.max(1, width - submitWidth - 4), 20, Component.empty()));
            field.setMaxLength(ProgramEditorWire.MAX_BYTES); field.setValue(inputDrafts.value(value.node(), value.instance(), input.inputs(), input.text()));
            field.setResponder(next -> inputDrafts.update(value.node(), value.instance(), input.inputs(), next));
            field.active = input.enabled(); inputFields.put(value.instance(), field);
            var control = button(x + width - submitWidth, y, submitWidth, text("submit"), () -> submit(value.instance()));
            control.active = input.enabled() && !clickLocked && !requestPending && snapshot != null && !snapshot.paused() && !snapshot.phase().equals("RUNNING");
            return;
        }
        Component label;
        if (value.problem().isPresent()) {
            label = problemLabel(value.problem().orElseThrow());
        } else if (value.definition() instanceof TerminalDefinition.Item && value.value().isPresent()) {
            var item = TerminalItemDisplay.resolve(value.value().orElseThrow()); items.put(value.instance(), item);
            label = item.problem().isPresent() ? text("unknown_item", item.id()) : Component.literal(item.id() + " × " + item.count());
            x += 20; width -= 20;
        } else label = Component.literal(value.value().map(ComputerTerminalScreen::summary).orElse(""));
        button(x, y, Math.max(1, width), label, () -> inspect(value));
    }
    private static Component problemLabel(TerminalProjection.Problem problem) {
        return switch (problem.code()) {
            case "output_pending", "output_failed", "output_no_program", "output_unavailable" -> text(problem.code());
            default -> text("problem", problem.code(), problem.detail());
        };
    }
    private static String summary(DataValue value) {
        if (value instanceof DataValue.RecordValue || value instanceof DataValue.MapValue || value instanceof DataValue.ListValue)
            return Component.translatable("create_biotech.biocomputing.tree.type." + ValueTree.kind(value)).getString()
                + " · " + Component.translatable("create_biotech.biocomputing.tree.entries", ValueTree.childCount(value)).getString();
        return BrowserLiteralDisplay.leaf(value);
    }
    private void page(String instance, int index) { pages.put(instance, index); init(); }
    private void inspect(TerminalProjection.View value) {
        var roots = new LinkedHashMap<>(snapshot.roots().fields());
        value.value().ifPresent(raw -> roots.put("value", raw));
        value.problem().ifPresent(problem -> roots.put("problem", new DataValue.TextValue(problem.code() + ": " + problem.detail())));
        navigator.accept(new ComputerValueTreeScreen(menu, inventory, title, this, new DataValue.RecordValue(roots), null,
            text("inspect"), text("frozen"), () -> navigator.accept(this)));
    }
    private void refresh() {
        if (requestPending) return;
        requestPending = true; request.accept(ComputerControlSession.Action.READ_TERMINAL); notice = text("waiting"); init();
    }
    private void click(String instance) {
        if (requestPending || clickLocked || publishedView <= 0) return;
        requestPending = true; clickLocked = true;
        try { clickRequest.accept(publishedView, instance); notice = text("waiting"); }
        catch (IllegalArgumentException rejected) { requestPending = false; notice = text("click_rejected", String.valueOf(rejected.getMessage())); }
        init();
    }
    private void submit(String instance) {
        if (requestPending || clickLocked || publishedView <= 0 || snapshot == null
            || snapshot.paused() || snapshot.phase().equals("RUNNING")) return;
        var field = inputFields.get(instance); if (field == null || !field.active) return;
        requestPending = true; clickLocked = true; submitPending = true;
        questionSubmitPending = TerminalSubmit.QUESTION.equals(instance);
        try { submitRequest.accept(new TerminalSubmit(publishedView, instance, field.getValue())); notice = text("waiting"); }
        catch (IllegalArgumentException rejected) { requestPending = false; submitPending = false; questionSubmitPending = false; notice = text("click_rejected", String.valueOf(rejected.getMessage())); }
        init();
    }
    private void claim(int index) {
        if (requestPending || clickLocked || publishedView <= 0) return;
        requestPending = true; clickLocked = true; claimPending = true;
        try { claimRequest.accept(new TerminalTaskClaim(publishedView, index)); notice = text("waiting"); }
        catch (IllegalArgumentException rejected) { requestPending = false; claimPending = false; notice = text("click_rejected", String.valueOf(rejected.getMessage())); }
        init();
    }
    @Override protected void containerTick() {
        super.containerTick();
        if(keyboardArmed && (!minecraft.isWindowActive() || getFocused() instanceof EditBox))keysDown.clear();
        if (lastKeyBits != 0 || !keysDown.isEmpty()) {
            if (keysDown.isEmpty() || ++keyRefresh >= TerminalKeyboard.REFRESH_TICKS) publishKeys();
        }
        if (!requested) { requested = true; refresh(); }
        if (responseVersion == menu.controlResponseVersion()) return;
        responseVersion = menu.controlResponseVersion();
        requestPending = false;
        if(keyPending){
            keyPending=false;var reply=menu.controlResponse().orElseThrow();
            keyboardReply=switch(reply.code()){
                case "ok","no_listener","already_running" -> text("keyboard_"+reply.code());
                default -> text("click_rejected",reply.code()+": "+reply.detail());
            };
            refresh();return;
        }
        if (claimPending || menu.terminalClaimStatus().isPresent()) {
            claimPending = false;
            var reply = menu.controlResponse().orElseThrow(); clickLocked = true;
            notice = text(reply.code().equals("ok") ? "task_claimed" : "click_rejected", reply.code() + ": " + reply.detail()); init(); return;
        }
        if (menu.terminalClickStatus().isPresent()) {
            var reply = menu.controlResponse().orElseThrow(); clickLocked = true;
            notice = text(reply.code().equals("ok") ? "click_accepted" : "click_rejected", reply.detail()); init(); return;
        }
        // Local encoding failures have no dedicated payload, but still finish this submit rather than a READ.
        if (submitPending || menu.terminalSubmitStatus().isPresent()) {
            submitPending = false;
            boolean answering = questionSubmitPending; questionSubmitPending = false;
            var reply = menu.controlResponse().orElseThrow(); clickLocked = true;
            if (answering && reply.code().equals("ok")) { questionDraft = ""; refresh(); return; }
            notice = text(reply.code().equals("ok") ? "click_accepted" : "click_rejected", reply.detail()); init(); return;
        }
        var status = menu.terminalStatus();
        if (status.isEmpty()) {
            snapshot = null; view = null; pages.clear(); scroll = 0; publishedView = 0; clickLocked = true;
            notice = text("invalid", menu.controlResponse().map(reply -> reply.code() + ": " + reply.detail()).orElse("")); init(); return;
        }
        try {
            var next = TerminalStatusView.decode(status.orElseThrow());
            if (snapshot == null || !snapshot.terminal().equals(next.terminal())) { pages.clear(); scroll = 0; }
            if (snapshot == null || !snapshot.question().equals(next.question())) questionDraft = "";
            if (next.terminal().isPresent()) inputDrafts.observe(next.terminal().orElseThrow(), next.roots());
            else inputDrafts.clear();
            snapshot = next; publishedView = menu.controlResponse().orElseThrow().intent(); clickLocked = false;
            if(!next.keyboard() || next.paused()){keyboardArmed=false;keysDown.clear();publishKeys();}
            notice=keyboardReply==null?text("frozen"):keyboardReply;keyboardReply=null;init();
        } catch (IllegalArgumentException invalid) { snapshot = null; view = null; pages.clear(); publishedView = 0; clickLocked = true; notice = text("invalid", String.valueOf(invalid.getMessage())); init(); }
    }
    private CreateTextButton button(int x, int y, int width, Component label, Runnable action) {
        return addRenderableWidget(new CreateTextButton(font, leftPos + x, topPos + y, width, 20, label, action, true));
    }
    private net.minecraft.client.renderer.Rect2i noticeBounds() {
        int start = snapshot != null && snapshot.keyboard() ? 172 : 88;
        int end = imageWidth - (snapshot != null && snapshot.roots().fields().containsKey("tasks") ? 104 : 10);
        return new net.minecraft.client.renderer.Rect2i(leftPos + start, topPos + 30, Math.max(0, end - start), 20);
    }
    @Override protected void renderBg(GuiGraphics graphics, float partial, int mouseX, int mouseY) {
        PatternGuiSkin.blit(graphics, PatternGuiSkin.SURFACE, leftPos, topPos, imageWidth, imageHeight);
        PatternGuiSkin.blit(graphics, PatternGuiSkin.HEADER, leftPos + 4, topPos + 4, imageWidth - 8, 22);
        graphics.drawString(font, text("title"), leftPos + 10, topPos + 10, 0xffeeeeee, false);
        var noticeArea = noticeBounds();
        graphics.drawString(font, font.plainSubstrByWidth(notice.getString(), noticeArea.getWidth()), noticeArea.getX(), noticeArea.getY() + 6, 0xffaaccee, false);
        if (noticeArea.contains(mouseX, mouseY))
            graphics.renderTooltip(font, font.split(notice, imageWidth - 20), mouseX, mouseY);
        if (snapshot != null && snapshot.question().isPresent()) {
            var question = text("question", snapshot.question().orElseThrow().text());
            var lines = font.split(question, Math.max(1, imageWidth - 16));
            for (int i = 0; i < Math.min(2, lines.size()); i++)
                graphics.drawString(font, lines.get(i), leftPos + 8, topPos + BODY_TOP + i * 10, 0xffe7e7e7, false);
            if (lines.size() > 2 && mouseX >= leftPos + 8 && mouseX < leftPos + imageWidth - 8
                && mouseY >= topPos + BODY_TOP && mouseY < topPos + BODY_TOP + 20)
                graphics.renderTooltip(font, lines, mouseX, mouseY);
        }
        if (snapshot != null && snapshot.terminal().isEmpty()) graphics.drawString(font, text("empty"), leftPos + 8, topPos + bodyTop(), 0xffdddddd, false);
        if (snapshot != null) graphics.drawString(font, font.plainSubstrByWidth(text("state", snapshot.phase().equals("WAITING_TASK") ? text("waiting_task").getString() : snapshot.phase(), snapshot.paused()).getString(), Math.max(1, imageWidth - 180)),
            leftPos + 100, topPos + imageHeight - 21, 0xffaaccee, false);
        for (var cell : visible) {
            var item = items.get(cell.view().instance());
            if (item != null && !item.stack().isEmpty()) graphics.renderItem(item.stack(), leftPos + 8 + cell.x(), topPos + bodyTop() + cell.y() - scroll + 2);
        }
    }
    @Override public void onClose() { navigator.accept(parent); }
    @Override protected void renderForeground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        super.renderForeground(graphics, mouseX, mouseY, partialTicks);
        if (getFocused() instanceof CreateTextButton control && children().contains(control) && control.active && control.visible)
            graphics.renderOutline(control.getX(), control.getY(), control.getWidth(), control.getHeight(), 0xffaaccee);
    }
    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if(keyboardArmed && !(getFocused() instanceof EditBox) && (modifiers & (GLFW_MOD_CONTROL|GLFW_MOD_ALT|GLFW_MOD_SUPER))==0){
            String key=terminalKey(keyCode);
            if(key!=null){
                if(keysDown.add(keyCode) && !requestPending && !clickLocked && publishedView>0 && snapshot!=null && !snapshot.paused()){
                    publishKeys();
                    requestPending=true;keyPending=true;clickLocked=true;keyRequest.accept(publishedView,key);
                }
                return true;
            }
        }
        // Create returns directly from focused EditBox dispatch, bypassing Screen's Tab navigation.
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_TAB) {
            var event = new FocusNavigationEvent.TabNavigation((modifiers & org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT) == 0);
            var path = nextFocusPath(event);
            if (path == null) { clearFocus(); path = nextFocusPath(event); }
            if (path != null) changeFocus(path);
            return true;
        }
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER && getFocused() instanceof EditBox field) for (var entry : inputFields.entrySet()) if (entry.getValue() == field) { submit(entry.getKey()); return true; }
        // Catnip's IconButton has a pointer callback, but no keyboard activation or focused skin.
        if ((keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE)
            && getFocused() instanceof CreateTextButton control) {
            if (children().contains(control) && control.active && control.visible) control.runCallback(control.getX(), control.getY());
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
    @Override public boolean keyReleased(int keyCode,int scanCode,int modifiers) {
        if (!keysDown.remove(keyCode)) return super.keyReleased(keyCode,scanCode,modifiers);
        publishKeys(); return true;
    }
    private void publishKeys() {
        keyRefresh = 0;
        long bits = 0;
        if (keyboardArmed && publishedView > 0 && minecraft.isWindowActive() && !(getFocused() instanceof EditBox))
            for (int key : keysDown) bits |= TerminalKey.bit(terminalKey(key));
        if (bits != 0 && (requestPending || clickLocked)) return;
        if (bits == 0 && lastKeyBits == 0) return;
        keyStateRequest.accept(publishedView, bits); lastKeyBits = bits;
    }
    private static String terminalKey(int key) {
        if (key >= GLFW_KEY_A && key <= GLFW_KEY_Z) return Character.toString('a' + key - GLFW_KEY_A);
        if (key >= GLFW_KEY_0 && key <= GLFW_KEY_9) return Character.toString('0' + key - GLFW_KEY_0);
        return switch (key) {
            case GLFW_KEY_SPACE -> "space";
            case GLFW_KEY_UP -> "up";
            case GLFW_KEY_DOWN -> "down";
            case GLFW_KEY_LEFT -> "left";
            case GLFW_KEY_RIGHT -> "right";
            default -> null;
        };
    }
    @Override public void removed() { keyboardArmed=false;keysDown.clear();publishKeys(); }
    @Override protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) { }
    private static Component text(String key, Object... args) { return Component.translatable("create_biotech.biocomputing.terminal." + key, args); }
}
