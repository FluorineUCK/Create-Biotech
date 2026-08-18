package com.nobodiiiii.createbiotech.content.allay.logistics.courier.hud;

public enum AllayCourierHudStatus {
	IN_TRANSIT,
	DELIVERED,
	FAILED;

	public static AllayCourierHudStatus byId(int id) {
		AllayCourierHudStatus[] values = values();
		return id >= 0 && id < values.length ? values[id] : IN_TRANSIT;
	}
}
