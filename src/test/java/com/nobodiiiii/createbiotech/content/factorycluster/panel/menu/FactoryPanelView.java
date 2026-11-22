package com.nobodiiiii.createbiotech.content.factorycluster.panel.menu;

/** Stable top-level request identities for the independently projected Panel views. */
public enum FactoryPanelView {
	COMPUTER_MONITOR(0),
	TASKS(1),
	NEW_TASK(2),
	NETWORK_COMMUNICATION(3),
	NETWORK_LOGISTICS(4);

	private final int wireId;

	FactoryPanelView(int wireId) {
		this.wireId = wireId;
	}

	public int wireId() {
		return wireId;
	}

	public static FactoryPanelView fromWireId(int wireId) {
		for (FactoryPanelView view : values())
			if (view.wireId == wireId) return view;
		throw new IllegalArgumentException("Unknown Factory Panel view id " + wireId);
	}
}
