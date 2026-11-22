package com.nobodiiiii.createbiotech.content.factorycluster.panel.view.network;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.FactoryStateRepresentationBounds;

/** Strictly local edit state; never attached to a remote Cluster or subnet card. */
public record LocalCommunicationEditProjection(long declarationRevision,
	List<ListenerEditorProjection> listenerEditors, int totalListeners, boolean listenerEditing) {
	public LocalCommunicationEditProjection(long declarationRevision, List<ListenerEditorProjection> listenerEditors) {
		this(declarationRevision, listenerEditors, listenerEditors.size(), !listenerEditors.isEmpty());
	}
	public LocalCommunicationEditProjection {
		listenerEditors = List.copyOf(Objects.requireNonNull(listenerEditors, "listener editors"));
		if (totalListeners < listenerEditors.size() || totalListeners < 0 || declarationRevision < 0
			|| listenerEditors.size() > FactoryStateRepresentationBounds.MAX_PANEL_ROWS)
			throw new IllegalArgumentException("invalid local communication edit projection");
		Set<String> targets = new HashSet<>();
		for (ListenerEditorProjection editor : listenerEditors)
			if (!targets.add(editor.target()))
				throw new IllegalArgumentException("duplicate local listener edit target");
	}
}
