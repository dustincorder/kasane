package cord.kasane.music.connect

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class TrustedDevice(
    val deviceId: String,
    val name: String,
    val platform: String,
    val fingerprint: String,
    val pairedAt: Long,
    val endpoints: List<String> = emptyList(),
)

/** Pinned-fingerprint trust store, persisted as JSON with atomic writes. */
class TrustStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Disk(val devices: List<TrustedDevice> = emptyList())

    @Volatile
    private var devices: Map<String, TrustedDevice> = load()

    private fun load(): Map<String, TrustedDevice> {
        if (!file.isFile) return emptyMap()
        return try {
            json.decodeFromString<Disk>(file.readText()).devices.associateBy { it.deviceId }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    @Synchronized
    private fun persist() {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(Disk.serializer(), Disk(devices.values.toList())))
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    fun isTrusted(deviceId: String, fingerprint: String): Boolean =
        devices[deviceId]?.fingerprint == fingerprint

    fun get(deviceId: String): TrustedDevice? = devices[deviceId]

    fun all(): List<TrustedDevice> = devices.values.sortedBy { it.name }

    @Synchronized
    fun put(device: TrustedDevice) {
        devices = devices + (device.deviceId to device)
        persist()
    }

    @Synchronized
    fun remove(deviceId: String) {
        devices = devices - deviceId
        persist()
    }
}
