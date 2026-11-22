package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.BrowserFence;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserFilter;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserPage;

import java.util.Optional;
import java.util.UUID;

/** Client intent fences keep delayed pages/details from a previous filter or opening out of the UI. */
public final class PatternBrowserClientSession {
    private final int containerId;
    private BrowserFence fence;
    private long intent;
    private PatternBrowserRequestPacket pending;
    private PatternBrowserResponsePacket response;
    private boolean refresh;
    public PatternBrowserClientSession(int containerId, BrowserFence fence) { this.containerId = containerId; this.fence = fence; }
    public BrowserFence fence() { return fence; }
    public Optional<PatternBrowserPage> page() { return response == null ? Optional.empty() : Optional.of(response.page()); }
    public PatternBrowserDiagnostics diagnostics() { return response == null ? PatternBrowserDiagnostics.empty() : response.diagnostics(); }
    public boolean needsRefresh() { return refresh; }
    /** Transport pacing, not gameplay budget: do not invalidate an in-flight read every second. */
    public int refreshAfterTicks() { return pending != null && (response == null || response.intentId() != pending.intentId()) ? 100 : 20; }
    public PatternBrowserRequestPacket request(PatternBrowserFilter filter, int page, Optional<UUID> selection) {
        if (pending == null || !pending.filter().equals(filter) || pending.pageIndex() != page || !pending.selection().equals(selection)) response = null;
        pending = new PatternBrowserRequestPacket(containerId, fence, ++intent, filter, page, selection);
        refresh = false; return pending;
    }
    public boolean accept(PatternBrowserResponsePacket next) {
        if (pending == null || next.containerId() != containerId || next.intentId() != pending.intentId()
            || !fence.sameSession(next.page().fence()) || next.page().fence().generation() < fence.generation()
            || next.page().pageIndex() != Math.max(0, pending.pageIndex())) return false;
        if (next.page().status() == PatternBrowserPage.Status.STALE) {
            fence = next.page().fence(); refresh = true;
        } else if (!next.page().fence().equals(fence)) return false;
        if (next.page().detail().isPresent() && pending.selection().filter(next.page().detail().get().patternId()::equals).isEmpty()) return false;
        response = next;
        return true;
    }
}
