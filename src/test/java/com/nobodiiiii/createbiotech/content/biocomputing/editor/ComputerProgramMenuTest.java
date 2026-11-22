package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.ResourceLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import java.lang.reflect.Field;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ComputerProgramMenuTest {
    @Test void draftConflictCannotBecomePermissionToOverwriteAndRepliesStaySeparateFromInstallation() throws Exception {
        var menu = allocate(ComputerProgramMenu.class); UUID nonce = UUID.randomUUID();
        set(menu, "containerId", 6); set(menu, "nonce", nonce); set(menu, "response", Optional.of(response(6, nonce, 1, "ready")));
        var requests = new java.util.ArrayList<ProgramDraftRequest>(); menu.requestSavedDraft(requests::add);
        menu.accept(new ProgramDraftResponse(6, nonce, 1, 4, "ready", "", new byte[0]));
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(FlowDocumentBody.emptySoftwareAssembly()));
        menu.saveDraft(workspace.snapshot(), requests::add);
        assertEquals(4, requests.getLast().revision()); assertTrue(menu.draftPending());
        menu.accept(new ProgramDraftResponse(6, UUID.randomUUID(), 2, 5, "saved", "", new byte[0]));
        assertTrue(menu.draftPending(), "A different menu cannot acknowledge this save");
        menu.accept(new ProgramDraftResponse(6, nonce, 2, 5, "stale", "", new byte[0]));
        assertFalse(menu.canSaveDraft()); assertEquals(4L, get(menu, "draftBaseRevision"));
        assertThrows(IllegalStateException.class, () -> menu.saveDraft(workspace.snapshot(), requests::add));
        assertEquals(2, requests.size()); assertEquals("ready", menu.response().orElseThrow().code());
    }
	private static final ResourceLimits HARDWARE = new ResourceLimits(10, 20, 1);
	private static final ArchiveLimits ARCHIVE = new ArchiveLimits(100, 1_000, 10_000);

	@Test void acceptsOnlyExactContainerNonceAndLatestIntent() {
		UUID nonce = UUID.randomUUID();
		var exact = response(4, nonce, 2, "ready");
		assertTrue(ComputerProgramMenu.acceptsResponse(4, nonce, 2, exact));
		assertFalse(ComputerProgramMenu.acceptsResponse(3, nonce, 2, exact));
		assertFalse(ComputerProgramMenu.acceptsResponse(4, UUID.randomUUID(), 2, exact));
		assertFalse(ComputerProgramMenu.acceptsResponse(4, nonce, 3, exact));
	}

	@Test void rejectionBecomesObservableWithoutAdvancingTheOutstandingIntent() throws Exception {
		UUID nonce = UUID.randomUUID();
		ComputerProgramMenu menu = allocate(ComputerProgramMenu.class);
		set(menu, "containerId", 6); set(menu, "nonce", nonce); set(menu, "latestIntent", 8L);
		set(menu, "response", Optional.empty());
		var rejected = response(6, nonce, 8, "invalid");
		menu.accept(rejected);
		assertSame(rejected, menu.response().orElseThrow());
		assertEquals(1, menu.responseVersion());
		assertEquals(8L, get(menu, "latestIntent"));
		menu.accept(response(6, nonce, 7, "stale"));
		assertSame(rejected, menu.response().orElseThrow());
		assertEquals(1, menu.responseVersion());
	}

	private static ProgramEditorResponse response(int container, UUID nonce, long intent, String code) {
		return new ProgramEditorResponse(container, nonce, intent, 5, code, "", HARDWARE, ARCHIVE, new byte[0]);
	}
	@Test void installedSourceRefreshAfterInstallDoesNotReplaceEditorResultOrSubmitTheDraft() throws Exception {
		UUID nonce = UUID.randomUUID();
		var menu = allocate(ComputerProgramMenu.class);
		set(menu, "containerId", 6); set(menu, "nonce", nonce); set(menu, "latestIntent", 2L);
		set(menu, "response", Optional.empty());
		var applied = response(6, nonce, 2, "applied");
		menu.accept(applied); // The actual install acknowledgement contains no archive.
		var requests = new java.util.ArrayList<ProgramEditorRequest>();
		menu.requestInstalledSource(5, requests::add);
		assertEquals(1, requests.size());
		var request = requests.getFirst();
		assertFalse(request.install()); assertEquals(0, request.archive().length);
		assertEquals(5, request.revision()); assertEquals(3, request.intent());
		menu.requestInstalledSource(5, requests::add);
		assertEquals(1, requests.size(), "Do not poll an entire archive on every UI tick");
		var ready = response(6, nonce, request.intent(), "ready");
		menu.accept(ready);
		assertSame(ready, menu.installedSource(5).orElseThrow());
		assertSame(applied, menu.response().orElseThrow(), "Source reads must not replace install feedback or editor drafts");
		assertEquals(1, menu.responseVersion());
		assertTrue(menu.installedSource(4).isEmpty());
		menu.requestInstalledSource(5, requests::add);
		assertEquals(1, requests.size());
	}
	@Test void sourceReadNeverCancelsInstallationAndCanRecoverWhenItsOwnReplyWasSuperseded() throws Exception {
		UUID nonce = UUID.randomUUID(); var menu = allocate(ComputerProgramMenu.class);
		set(menu,"containerId",6); set(menu,"nonce",nonce); set(menu,"latestIntent",1L); set(menu,"response",Optional.empty());
		menu.accept(response(6,nonce,1,"applied"));
		var requests = new java.util.ArrayList<ProgramEditorRequest>();
		menu.requestInstalledSource(5, requests::add);
		assertEquals(1,requests.size());
		set(menu,"latestIntent",3L); // Player installs while the source-only read is in flight.
		menu.requestInstalledSource(6, requests::add); assertEquals(1,requests.size());
		menu.accept(response(6,nonce,2,"ready")); assertTrue(menu.installedSource(5).isEmpty());
		menu.accept(response(6,nonce,3,"invalid"));
		menu.requestInstalledSource(5, requests::add);
		assertEquals(2,requests.size(),"A discarded read must not permanently suppress this revision");
	}
	private static void set(Object target, String name, Object value) throws Exception {
		Field field = field(target.getClass(), name); field.set(target, value);
	}
	private static Object get(Object target, String name) throws Exception { return field(target.getClass(), name).get(target); }
	private static Field field(Class<?> type, String name) throws Exception {
		for (Class<?> current = type; current != null; current = current.getSuperclass()) try {
			Field field = current.getDeclaredField(name); field.setAccessible(true); return field;
		} catch (NoSuchFieldException ignored) {}
		throw new NoSuchFieldException(name);
	}
	@SuppressWarnings("removal")
	private static <T> T allocate(Class<T> type) throws Exception {
		Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
		return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
	}
	@Test void controlRepliesUseTheirOwnIntentFenceIndependentOfEditorReplies() {
		UUID nonce=UUID.randomUUID();var exact=new ProgramStatusResponse(4,nonce,9,2,"ok","",new byte[0]);
		assertTrue(ComputerProgramMenu.acceptsControlResponse(4,nonce,9,exact));
		assertFalse(ComputerProgramMenu.acceptsControlResponse(4,nonce,8,exact));
		assertFalse(ComputerProgramMenu.acceptsControlResponse(3,nonce,9,exact));
		assertFalse(ComputerProgramMenu.acceptsControlResponse(4,UUID.randomUUID(),9,exact));
	}	@Test void loadDecisionDistinguishesHealthyEmptyComputerFromCorruptHost() {
		assertEquals("ready", ComputerProgramMenu.loadCode(true, false, false));
		assertEquals("unavailable", ComputerProgramMenu.loadCode(true, false, true));
		assertEquals("ready", ComputerProgramMenu.loadCode(true, true, true));
		assertEquals("unavailable", ComputerProgramMenu.loadCode(false, true, false));
	}}


