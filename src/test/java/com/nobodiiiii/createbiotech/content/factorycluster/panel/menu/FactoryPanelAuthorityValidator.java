package com.nobodiiiii.createbiotech.content.factorycluster.panel.menu;

import java.util.Objects;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.network.CommunicationPage;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.FactoryPanelCommandScope;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.network.CommunicationNetworkProjectionService;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.monitor.ComputerMonitorViewRequest;

/** Authority fences for legacy read projections. */
public final class FactoryPanelAuthorityValidator {
	private FactoryPanelAuthorityValidator() {}

	public static Optional<Validation> validate(Request request,
		Facts facts) {
		request = Objects.requireNonNull(request, "request");
		facts = Objects.requireNonNull(facts, "facts");
		if (!request.panelAddress().equals(facts.menuAddress())
			|| !request.panelId().equals(facts.menuPanelId())
			|| !request.menuSessionId().equals(facts.menuSessionId())
			|| !facts.openingClusterId().equals(facts.currentClusterId())
			|| !request.epochId().equals(facts.epochId())
			|| request.snapshotGeneration() != facts.snapshotGeneration()
			|| request.viewRevision() != facts.viewRevision()
			|| !facts.serverMenuOwnsBlockEntity() || !facts.menuOwned()
			|| !facts.sameLevel() || !facts.exactLoadedBlockEntity()
			|| !facts.exactRegisteredBlock() || !facts.playerInRange()
			|| !facts.bindingReady())
			return Optional.empty();
		return Optional.of(new Validation(new FactoryPanelCommandScope(
			facts.currentClusterId(), facts.epochId(), facts.snapshotGeneration(),
			facts.viewRevision(), facts.menuPanelId(), facts.menuAddress(), facts.menuSessionId(),
			facts.actorAddress())));
	}

	/** Domain-branded command validation bound to one exact current Communication publication. */
	public static Optional<CommunicationValidation> validateCommunication(
		CommunicationRequest request, Facts facts,
		CommunicationNetworkProjectionService.PublishedSnapshot publication) {
		request = Objects.requireNonNull(request, "request");
		publication = Objects.requireNonNull(publication, "publication");
		CommunicationNetworkProjectionService.PublishedSnapshot exactPublication = publication;
		if (request.view() != FactoryPanelView.NETWORK_COMMUNICATION
			|| !request.opening().clusterId().equals(publication.localClusterId())
			|| !request.opening().epochId().equals(publication.localEpochId())
			|| request.snapshotGeneration() != publication.generation()
			|| request.viewRevision() != publication.revision()
			|| !publication.isCurrentPublication()) return Optional.empty();
		Request generic = new Request(request.opening().panelAddress(), request.opening().panelId(),
			request.opening().menuSessionId(), request.opening().epochId(),
			request.snapshotGeneration(), request.viewRevision());
		return validate(generic, facts).map(value -> new CommunicationValidation(
			value.commandScope(), exactPublication));
	}

	/** Validates a read request without requiring the client to guess a global snapshot fence. */
	public static Optional<ViewValidation> validateView(ViewRequest request, Facts facts,
		int panelRowLimit) {
		facts = Objects.requireNonNull(facts, "facts");
		return validateView(request, new ViewFacts(facts.menuAddress(), facts.menuPanelId(),
			facts.menuSessionId(), facts.openingClusterId(), facts.currentClusterId(),
			facts.epochId(), facts.serverMenuOwnsBlockEntity(), facts.menuOwned(),
			facts.sameLevel(), facts.exactLoadedBlockEntity(), facts.exactRegisteredBlock(),
			facts.playerInRange(), facts.bindingReady(), facts.actorAddress(), panelRowLimit));
	}

	/** Cheap menu/envelope gate run before any world authority capture. */
	public static boolean validateViewEnvelope(ViewRequest request,
		FactoryPanelOpening menuOpening, int menuRowLimit) {
		request = Objects.requireNonNull(request, "request");
		menuOpening = Objects.requireNonNull(menuOpening, "menuOpening");
		FactoryPanelOpening requested = request.opening();
		return request.view() != null && request.selection() != null
			&& request.knownRevision() != null
			&& request.monitorRequest() != null && request.intentId() >= 0
			&& (request.knownRevision().isEmpty()
				|| request.knownRevision().orElseThrow() >= 0)
			&& request.offset() >= 0 && request.limit() >= 0
			&& (long) request.offset() + request.limit() <= Integer.MAX_VALUE
			&& menuRowLimit > 0 && request.limit() <= menuRowLimit
			&& requested.equals(menuOpening);
	}

	/** Final read authority check over a projection-free current world capture. */
	public static Optional<ViewValidation> validateView(ViewRequest request, ViewFacts facts) {
		request = Objects.requireNonNull(request, "request");
		facts = Objects.requireNonNull(facts, "facts");
		FactoryPanelOpening opening = request.opening();
		if (facts.panelRowLimit() <= 0 || request.limit() > facts.panelRowLimit()
			|| !opening.panelAddress().equals(facts.menuAddress())
			|| !opening.panelId().equals(facts.menuPanelId())
			|| !opening.menuSessionId().equals(facts.menuSessionId())
			|| !opening.clusterId().equals(facts.openingClusterId())
			|| !facts.openingClusterId().equals(facts.currentClusterId())
			|| !opening.epochId().equals(facts.epochId())
			|| !facts.serverMenuOwnsBlockEntity() || !facts.menuOwned()
			|| !facts.sameLevel() || !facts.exactLoadedBlockEntity()
			|| !facts.exactRegisteredBlock() || !facts.playerInRange()
			|| !facts.bindingReady())
			return Optional.empty();
		return Optional.of(new ViewValidation(request));
	}

	/** Opaque proof; only {@link #validate} can create one. */
	public static final class Validation {
		private final FactoryPanelCommandScope commandScope;
		private Validation(FactoryPanelCommandScope commandScope) {
			this.commandScope = Objects.requireNonNull(commandScope, "commandScope");
		}
		/** Independent Task-domain proof. */
		public FactoryPanelCommandScope commandScope() { return commandScope; }
	}

	/** Opaque read proof; only {@link #validateView} can create one. */
	public static final class ViewValidation {
		private final ViewRequest request;
		private ViewValidation(ViewRequest request) { this.request = request; }
		public ViewRequest request() { return request; }
	}

	/** Opaque proof usable only by NETWORK_COMMUNICATION commands. */
	public static final class CommunicationValidation {
		private final FactoryPanelCommandScope commandScope;
		private final CommunicationNetworkProjectionService.PublishedSnapshot publication;
		private CommunicationValidation(FactoryPanelCommandScope commandScope,
			CommunicationNetworkProjectionService.PublishedSnapshot publication) {
			this.commandScope = Objects.requireNonNull(commandScope, "command scope");
			this.publication = Objects.requireNonNull(publication, "publication");
		}
		public FactoryPanelCommandScope commandScope() { return commandScope; }
		public CommunicationNetworkProjectionService.PublishedSnapshot publication() {
			return publication;
		}
	}

	public record ViewRequest(FactoryPanelOpening opening, FactoryPanelView view,
		OptionalLong knownRevision, int offset, int limit, Optional<UUID> selection,
		long intentId, Optional<ComputerMonitorViewRequest> monitorRequest, CommunicationPage communicationPage) {
		public ViewRequest(FactoryPanelOpening opening, FactoryPanelView view,
			OptionalLong knownRevision, int offset, int limit, Optional<UUID> selection,
			long intentId, Optional<ComputerMonitorViewRequest> monitorRequest) {
			this(opening, view, knownRevision, offset, limit, selection, intentId, monitorRequest,
				CommunicationPage.OVERVIEW);
		}

		public ViewRequest {
			Objects.requireNonNull(opening, "opening");
			Objects.requireNonNull(view, "view");
			knownRevision = Objects.requireNonNull(knownRevision, "knownRevision");
			selection = Objects.requireNonNull(selection, "selection");
			monitorRequest = Objects.requireNonNull(monitorRequest, "monitorRequest");
			communicationPage = Objects.requireNonNull(communicationPage, "communicationPage");
			if (view != FactoryPanelView.NETWORK_COMMUNICATION && communicationPage != CommunicationPage.OVERVIEW)
				throw new IllegalArgumentException("Communication lane on another view");
			if ((knownRevision.isPresent() && knownRevision.orElseThrow() < 0)
				|| offset < 0 || limit < 0 || intentId < 0
				|| (long) offset + limit > Integer.MAX_VALUE)
				throw new IllegalArgumentException("invalid Factory Panel view request bounds");
			if ((view == FactoryPanelView.COMPUTER_MONITOR) != monitorRequest.isPresent())
				throw new IllegalArgumentException("Computer monitor payload must match the requested view");
			if (monitorRequest.isPresent()) {
				ComputerMonitorViewRequest request = monitorRequest.orElseThrow();
				if (view != FactoryPanelView.COMPUTER_MONITOR || request.activeOffset() != offset
					|| !request.activeSelection().equals(selection) || request.requestedRows() > limit)
					throw new IllegalArgumentException("inconsistent Computer monitor request");
			}
		}

		public ViewRequest(FactoryPanelOpening opening, FactoryPanelView view,
			OptionalLong knownRevision, int offset, int limit, Optional<UUID> selection) {
			this(opening, view, knownRevision, offset, limit, selection, 0, Optional.empty());
		}

		/** A numeric value, including zero, is always an exact revision fence. */
		public ViewRequest(FactoryPanelOpening opening, FactoryPanelView view,
			long knownRevision, int offset, int limit, Optional<UUID> selection) {
			this(opening, view, OptionalLong.of(knownRevision), offset, limit, selection);
		}
	}

	public record CommunicationRequest(FactoryPanelOpening opening, FactoryPanelView view,
		long snapshotGeneration, long viewRevision) {
		public CommunicationRequest {
			opening = Objects.requireNonNull(opening, "opening");
			view = Objects.requireNonNull(view, "view");
			if (view != FactoryPanelView.NETWORK_COMMUNICATION
				|| snapshotGeneration < 0 || viewRevision < 0)
				throw new IllegalArgumentException("invalid Communication command request");
		}
	}

	public record Request(SpaceAddress panelAddress, UUID panelId, UUID menuSessionId,
		UUID epochId, long snapshotGeneration, long viewRevision) {
		public Request {
			Objects.requireNonNull(panelAddress); Objects.requireNonNull(panelId);
			Objects.requireNonNull(menuSessionId); Objects.requireNonNull(epochId);
			if (snapshotGeneration < 0 || viewRevision < 0)
				throw new IllegalArgumentException("negative Panel command fence");
		}
	}

	public record Facts(SpaceAddress menuAddress, UUID menuPanelId, UUID menuSessionId,
		UUID openingClusterId, UUID currentClusterId, UUID epochId, long snapshotGeneration,
		long viewRevision, boolean serverMenuOwnsBlockEntity, boolean menuOwned,
		boolean sameLevel, boolean exactLoadedBlockEntity, boolean exactRegisteredBlock,
		boolean playerInRange, boolean bindingReady, Optional<SpaceAddress> actorAddress) {
		public Facts {
			Objects.requireNonNull(menuAddress); Objects.requireNonNull(menuPanelId);
			Objects.requireNonNull(menuSessionId); Objects.requireNonNull(openingClusterId);
			Objects.requireNonNull(currentClusterId); Objects.requireNonNull(epochId);
			actorAddress = Objects.requireNonNull(actorAddress, "actorAddress");
			if (snapshotGeneration < 0 || viewRevision < 0)
				throw new IllegalArgumentException("negative current Panel fence");
		}
		public Facts(SpaceAddress menuAddress, UUID menuPanelId, UUID menuSessionId,
			UUID openingClusterId, UUID currentClusterId, UUID epochId, long snapshotGeneration,
			long viewRevision, boolean serverMenuOwnsBlockEntity, boolean menuOwned,
			boolean sameLevel, boolean exactLoadedBlockEntity, boolean exactRegisteredBlock,
			boolean playerInRange, boolean bindingReady) {
			this(menuAddress, menuPanelId, menuSessionId, openingClusterId, currentClusterId,
				epochId, snapshotGeneration, viewRevision, serverMenuOwnsBlockEntity, menuOwned,
				sameLevel, exactLoadedBlockEntity, exactRegisteredBlock, playerInRange,
				bindingReady, Optional.empty());
		}
	}

	/** Projection-free facts captured for one view request on the server thread. */
	public record ViewFacts(SpaceAddress menuAddress, UUID menuPanelId, UUID menuSessionId,
		UUID openingClusterId, UUID currentClusterId, UUID epochId,
		boolean serverMenuOwnsBlockEntity, boolean menuOwned, boolean sameLevel,
		boolean exactLoadedBlockEntity, boolean exactRegisteredBlock, boolean playerInRange,
		boolean bindingReady, Optional<SpaceAddress> actorAddress, int panelRowLimit) {
		public ViewFacts {
			Objects.requireNonNull(menuAddress); Objects.requireNonNull(menuPanelId);
			Objects.requireNonNull(menuSessionId); Objects.requireNonNull(openingClusterId);
			Objects.requireNonNull(currentClusterId); Objects.requireNonNull(epochId);
			actorAddress = Objects.requireNonNull(actorAddress, "actorAddress");
		}
	}
}
