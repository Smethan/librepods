/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package me.kavishdevar.librepods.data

import android.os.Parcelable
import android.util.Log
import kotlinx.parcelize.Parcelize

// TODO: Remove everything but Battery-related stuff

enum class Enums(val value: ByteArray) {
    NOISE_CANCELLATION(byteArrayOf(0x0d)),
    PREFIX(byteArrayOf(0x04, 0x00, 0x04, 0x00)),
    SETTINGS(byteArrayOf(0x09, 0x00)),
    NOISE_CANCELLATION_PREFIX(PREFIX.value + SETTINGS.value + NOISE_CANCELLATION.value),
    CONVERSATION_AWARENESS_RECEIVE_PREFIX(PREFIX.value + byteArrayOf(0x4b, 0x00, 0x02, 0x00)),
}

object BatteryComponent {
    /** Single over-ear battery, reported by AirPods Max instead of LEFT/RIGHT/CASE. */
    const val HEADSET = 1
    const val LEFT = 4
    const val RIGHT = 2
    const val CASE = 8

    /** All components in the order they should be presented to the user. */
    val ALL = listOf(HEADSET, LEFT, RIGHT, CASE)

    /** Components that represent something worn on the head, i.e. not the case. */
    val WORN = listOf(HEADSET, LEFT, RIGHT)
}

object BatteryStatus {
    const val CHARGING = 1
    const val NOT_CHARGING = 2
    const val DISCONNECTED = 4
    const val OPTIMIZED_CHARGING = 5
}

@Parcelize
data class Battery(val component: Int, val level: Int, val status: Int) : Parcelable {
    fun getComponentName(): String? {
        return when (component) {
            BatteryComponent.HEADSET -> "HEADSET"
            BatteryComponent.LEFT -> "LEFT"
            BatteryComponent.RIGHT -> "RIGHT"
            BatteryComponent.CASE -> "CASE"
            else -> null
        }
    }

    fun getStatusName(): String? {
        return when (status) {
            BatteryStatus.CHARGING -> "CHARGING"
            BatteryStatus.NOT_CHARGING -> "NOT_CHARGING"
            BatteryStatus.DISCONNECTED -> "DISCONNECTED"
            BatteryStatus.OPTIMIZED_CHARGING -> "OPTIMIZED_CHARGING"
            else -> null
        }
    }
}

enum class NoiseControlMode {
    OFF,  NOISE_CANCELLATION, TRANSPARENCY, ADAPTIVE
}

class AirPodsNotifications {
    companion object {
        const val AIRPODS_CONNECTED = "me.kavishdevar.librepods.AIRPODS_CONNECTED"
        const val AIRPODS_L2CAP_CONNECTED = "me.kavishdevar.librepods.AIRPODS_CONNECTED"
        const val AIRPODS_DATA = "me.kavishdevar.librepods.AIRPODS_DATA"
        const val EAR_DETECTION_DATA = "me.kavishdevar.librepods.EAR_DETECTION_DATA"
        const val ANC_DATA = "me.kavishdevar.librepods.ANC_DATA"
        const val BATTERY_DATA = "me.kavishdevar.librepods.BATTERY_DATA"
        const val CA_DATA = "me.kavishdevar.librepods.CA_DATA"
        const val AIRPODS_DISCONNECTED = "me.kavishdevar.librepods.AIRPODS_DISCONNECTED"
        const val AIRPODS_CONNECTION_DETECTED = "me.kavishdevar.librepods.AIRPODS_CONNECTION_DETECTED"
        const val DISCONNECT_RECEIVERS = "me.kavishdevar.librepods.DISCONNECT_RECEIVERS"
        const val EQ_DATA = "me.kavishdevar.librepods.HEADPHONE_ACCOMMODATION"
        const val AIRPODS_INFORMATION_UPDATED = "me.kavishdevar.librepods.AIRPODS_INFORMATION_UPDATED"
    }

    class EarDetection {
        private val notificationBit = 6.toByte()
        private val notificationPrefix = Enums.PREFIX.value + notificationBit

        var status: List<Byte> = listOf(0x01, 0x01)

        fun setStatus(data: ByteArray) {
            status = listOf(data[6], data[7])
        }

        fun isEarDetectionData(data: ByteArray): Boolean {
            if (data.size != 8) {
                return false
            }
            val prefixHex = notificationPrefix.joinToString("") { "%02x".format(it) }
            val dataHex = data.joinToString("") { "%02x".format(it) }
            return dataHex.startsWith(prefixHex)
        }
    }

    class ANC {
        private val notificationPrefix = Enums.NOISE_CANCELLATION_PREFIX.value

        var status: Int = 1
            private set

        fun isANCData(data: ByteArray): Boolean {
            if (data.size != 11) {
                return false
            }
            val prefixHex = notificationPrefix.joinToString("") { "%02x".format(it) }
            val dataHex = data.joinToString("") { "%02x".format(it) }
            return dataHex.startsWith(prefixHex)
        }

        fun setStatus(data: ByteArray) {
            when (data.size) {
                // if the whole packet is given
                11 -> {
                    status = data[7].toInt()
                }
                // if only the data is given
                1 -> {
                    status = data[0].toInt()
                }
                // if the value of control command is given
                4 -> {
                    status = data[0].toInt()
                }
                else -> {
                    Log.d("ANC", "Invalid ANC data size: ${data.size}")
                }
            }
        }

        val name: String =
            when (status) {
                1 -> "OFF"
                2 -> "ON"
                3 -> "TRANSPARENCY"
                4 -> "ADAPTIVE"
                else -> "UNKNOWN"
            }

    }

    /**
     * Parses AACP battery packets (opcode 0x04).
     *
     * Layout: `04 00 04 00 04 00 <count>` followed by `count` five byte entries of
     * `<component> 01 <level> <status> 01`. Devices report a different number of
     * components: earbuds with a case report three (left, right, case), while
     * AirPods Max report a single [BatteryComponent.HEADSET] entry. Anything that
     * assumes exactly three components silently drops AirPods Max battery data.
     */
    class BatteryNotification {
        private val states = LinkedHashMap<Int, Battery>()

        /** Components the device actually reported, in presentation order. */
        val reportedComponents: List<Int>
            get() = BatteryComponent.ALL.filter { states.containsKey(it) }

        /** True once the device has told us it has a single over-ear battery. */
        val isHeadset: Boolean
            get() = states.containsKey(BatteryComponent.HEADSET)

        fun isBatteryData(data: ByteArray): Boolean {
            if (data.size < HEADER_SIZE) return false
            if (!data.joinToString("") { "%02x".format(it) }.startsWith(PREFIX_HEX)) {
                return false
            }
            val count = data[COUNT_INDEX].toInt() and 0xFF
            if (count !in 1..MAX_COMPONENTS) {
                Log.d("BatteryNotification", "Battery data reports an implausible component count: $count")
                return false
            }
            if (data.size != HEADER_SIZE + ENTRY_SIZE * count) {
                Log.d("BatteryNotification", "Battery data size ${data.size} does not match a count of $count")
                return false
            }
            return true
        }

        fun setBatteryDirect(
            leftLevel: Int,
            leftCharging: Boolean,
            rightLevel: Int,
            rightCharging: Boolean,
            caseLevel: Int,
            caseCharging: Boolean,
            headset: Boolean = false
        ) {
            fun status(charging: Boolean) =
                if (charging) BatteryStatus.CHARGING else BatteryStatus.NOT_CHARGING

            states.clear()
            if (headset) {
                // BLE advertisements always carry pod-shaped battery bytes; for an
                // over-ear headset only one of them is meaningful.
                val (level, charging) = listOf(
                    leftLevel to leftCharging,
                    rightLevel to rightCharging,
                    caseLevel to caseCharging
                ).firstOrNull { it.first in 1..100 } ?: (leftLevel to leftCharging)
                states[BatteryComponent.HEADSET] =
                    Battery(BatteryComponent.HEADSET, level, status(charging))
                return
            }
            states[BatteryComponent.LEFT] =
                Battery(BatteryComponent.LEFT, leftLevel, status(leftCharging))
            states[BatteryComponent.RIGHT] =
                Battery(BatteryComponent.RIGHT, rightLevel, status(rightCharging))
            states[BatteryComponent.CASE] =
                Battery(BatteryComponent.CASE, caseLevel, status(caseCharging))
        }

        fun setBattery(data: ByteArray): Boolean {
            if (!isBatteryData(data)) return false

            val count = data[COUNT_INDEX].toInt() and 0xFF
            val parsed = LinkedHashMap<Int, Battery>()
            for (i in 0 until count) {
                val offset = HEADER_SIZE + ENTRY_SIZE * i
                val component = data[offset].toInt() and 0xFF
                if (component !in BatteryComponent.ALL) {
                    Log.d("BatteryNotification", "Ignoring unknown battery component 0x%02x".format(component))
                    continue
                }
                val rawLevel = data[offset + 2].toInt() and 0xFF
                val status = data[offset + 3].toInt() and 0xFF
                // The firmware reports 0xFF (and occasionally other out of range
                // values) when a level is unknown; keep the last one we saw
                // instead of rendering it as -1% or 255%.
                val level = if (rawLevel in 0..100) rawLevel else states[component]?.level ?: 0
                parsed[component] = Battery(component, level, status)
            }

            if (parsed.isEmpty()) return false

            states.clear()
            states.putAll(parsed)
            return true
        }

        /**
         * The components the device reported. Before anything has been received
         * this falls back to the classic left/right/case triple so existing
         * consumers keep seeing a stable, disconnected list.
         */
        fun getBattery(): List<Battery> {
            if (states.isEmpty()) return DISCONNECTED_DEFAULTS
            return BatteryComponent.ALL.mapNotNull { states[it] }
        }

        /**
         * Lowest level across the components worn on the head, which is what the
         * island, notification and system battery indicator should show. Returns
         * null when nothing usable has been reported yet.
         */
        fun getWornLevel(): Int? {
            return BatteryComponent.WORN
                .mapNotNull { states[it] }
                .filter { it.status != BatteryStatus.DISCONNECTED }
                .minOfOrNull { it.level }
        }

        /** True when every component worn on the head reports charging. */
        fun isWornCharging(): Boolean {
            val worn = BatteryComponent.WORN.mapNotNull { states[it] }
            return worn.isNotEmpty() && worn.all { it.status == BatteryStatus.CHARGING }
        }

        companion object {
            private const val PREFIX_HEX = "040004000400"
            private const val COUNT_INDEX = 6
            private const val HEADER_SIZE = 7
            private const val ENTRY_SIZE = 5
            private const val MAX_COMPONENTS = 3

            private val DISCONNECTED_DEFAULTS = listOf(
                Battery(BatteryComponent.LEFT, 0, BatteryStatus.DISCONNECTED),
                Battery(BatteryComponent.RIGHT, 0, BatteryStatus.DISCONNECTED),
                Battery(BatteryComponent.CASE, 0, BatteryStatus.DISCONNECTED)
            )
        }
    }

    class ConversationalAwarenessNotification {
        @Suppress("PrivatePropertyName")
        private val NOTIFICATION_PREFIX = Enums.CONVERSATION_AWARENESS_RECEIVE_PREFIX.value

        var status: Byte = 0
            private set

        fun isConversationalAwarenessData(data: ByteArray): Boolean {
            if (data.size != 10) {
                return false
            }
            val prefixHex = NOTIFICATION_PREFIX.joinToString("") { "%02x".format(it) }
            val dataHex = data.joinToString("") { "%02x".format(it) }
            return dataHex.startsWith(prefixHex)
        }

        fun setData(data: ByteArray) {
            status = data[9]
        }
    }
}

fun isHeadTrackingData(data: ByteArray): Boolean {
    if (data.size <= 60) return false

    val prefixPattern = byteArrayOf(
        0x04, 0x00, 0x04, 0x00, 0x17, 0x00, 0x00, 0x00,
        0x10, 0x00
    )

    for (i in prefixPattern.indices) {
        if (data[i] != prefixPattern[i]) return false
    }

    if (data[10] != 0x44.toByte() && data[10] != 0x45.toByte()) return false

    if (data[11] != 0x00.toByte()) return false

    return true
}
