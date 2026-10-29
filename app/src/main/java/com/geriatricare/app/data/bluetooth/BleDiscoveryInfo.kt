package com.geriatricare.app.data.bluetooth

/** Advertisement metadata, separate from Android's potentially empty cached device name. */
data class BleDiscoveryInfo(val name: String, val hasSensorService: Boolean) {
    val isGeriatriCare: Boolean
        get() = hasSensorService || name.startsWith("GeriatriCare", ignoreCase = true)

    companion object {
        fun merge(
            previous: BleDiscoveryInfo?, advertisedName: String?, cachedName: String?,
            hasSensorService: Boolean
        ): BleDiscoveryInfo {
            val name = advertisedName?.trim()?.takeIf { it.isNotEmpty() }
                ?: previous?.name?.takeUnless { it == "Unnamed BLE device" }
                ?: cachedName?.trim()?.takeIf { it.isNotEmpty() }
                ?: "Unnamed BLE device"
            return BleDiscoveryInfo(name, hasSensorService || previous?.hasSensorService == true)
        }
    }
}
