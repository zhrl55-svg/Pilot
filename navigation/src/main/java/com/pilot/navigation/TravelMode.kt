package com.pilot.navigation

/** 导航出行方式。数值会通过 AIDL 传给 `:navigation` 进程。 */
enum class TravelMode(val value: Int, val label: String) {
    DRIVE(0, "驾车"),
    WALK(1, "步行");

    companion object {
        fun fromValue(value: Int): TravelMode = entries.firstOrNull { it.value == value } ?: DRIVE
    }
}
