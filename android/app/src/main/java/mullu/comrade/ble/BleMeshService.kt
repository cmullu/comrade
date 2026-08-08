package mullu.comrade.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mullu.comrade.ComradeCore

/**
 * The Bluetooth radio for Comrade's off-grid mesh — the transport that needs no
 * router, no relay, and no infrastructure of any kind.
 *
 * This is the half of the BLE mesh that cannot be tested without hardware:
 * GATT roles, advertising, scanning, connection management and MTU negotiation.
 * Everything that decides *what goes on the wire* — framing, fragmentation,
 * reassembly, dedup, TTL, relaying — lives in Rust
 * (`comrade_core::dak::ble`, `comrade_ui`'s `BleRouter`) where it is tested
 * without a radio at all. The seam between them is four calls:
 * [ComradeCore.bleSetActive], [ComradeCore.bleSetMtu], [ComradeCore.bleDeliver]
 * and [ComradeCore.bleDrainOutbound].
 *
 * ## Every device is both ends
 *
 * A BLE mesh has no clients and no servers. Each phone runs a **GATT server**
 * (so others can write packets to it) and advertises, *and* scans and connects
 * as a **GATT client** (so it can write packets to others). That is why both
 * `BLUETOOTH_ADVERTISE` and `BLUETOOTH_SCAN` are required, and why the callback
 * soup below has two of everything.
 *
 * ## What is on the air
 *
 * Only opaque packets. The service UUID is a fixed Comrade constant — it says
 * "a Comrade is here", which is unavoidable for discovery and is the same
 * information mDNS gives away on WiFi. Everything else is inside the sealed
 * envelope: no identity, no recipient, no plaintext, and a length that reveals
 * only a padding bucket. The advertisement deliberately carries **no device
 * name and no manufacturer data** — a rotating BLE address plus a service UUID
 * is the least a discoverable radio can emit.
 *
 * ## Battery
 *
 * Scanning is the expensive part, so it runs in duty cycles
 * ([SCAN_ON_MS] on, [SCAN_OFF_MS] off) rather than continuously, and
 * `SCAN_MODE_LOW_POWER` is used rather than low-latency. A mesh that flattens
 * the battery is a mesh nobody leaves on, and a mesh nobody leaves on does not
 * deliver anything.
 *
 * **Type-checked, not run.** `.claude/scripts/android-typecheck.sh` compiles
 * this file against a real `android.jar`, so the platform API calls below
 * resolve and typecheck. That is all it proves. No CI runner has a Bluetooth
 * adapter, so nothing here has ever *executed*: whether two phones actually
 * find each other, negotiate an MTU and exchange a packet is unproven until it
 * runs on hardware. Written from the platform contracts and kept structurally
 * close to the patterns already proven in `call/`.
 */
object BleMeshService {
    private const val TAG = "BleMeshService"

    /**
     * Comrade's GATT service. A fixed, published constant — every Comrade must
     * look for the same one, so this is not a secret and cannot be rotated per
     * user without making devices undiscoverable to each other.
     */
    val SERVICE_UUID: UUID = UUID.fromString("c0a7ade0-0001-4000-8000-00805f9b34fb")

    /** Write-without-response characteristic: one packet per write. */
    val PACKET_UUID: UUID = UUID.fromString("c0a7ade0-0002-4000-8000-00805f9b34fb")

    /** Duty cycle. Scanning continuously is what drains a phone. */
    private const val SCAN_ON_MS = 6_000L
    private const val SCAN_OFF_MS = 4_000L

    /** How often outbound packets are collected from the core and written. */
    private const val PUMP_INTERVAL_MS = 250L

    /** What we ask for; Android negotiates down and reports the real value. */
    private const val DESIRED_MTU = 517

    /** ATT overhead subtracted from a negotiated MTU to get usable payload. */
    private const val ATT_OVERHEAD = 3

    private var scope: CoroutineScope? = null
    private var adapter: BluetoothAdapter? = null
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: AdvertiseCallback? = null
    private var scanner: ScanCallback? = null
    private var jobs: MutableList<Job> = mutableListOf()

    /** Peers we hold an outbound GATT connection to, by device address. */
    private val links = ConcurrentHashMap<String, Link>()

    /** Peers connected to *our* GATT server, which we answer through notify. */
    private val inbound = ConcurrentHashMap<String, BluetoothDevice>()

    private class Link(
        val gatt: BluetoothGatt,
        @Volatile var characteristic: BluetoothGattCharacteristic? = null,
        @Volatile var mtu: Int = 23,
    )

    /**
     * Tell the core whether there is anybody to write to.
     *
     * The BLE equivalent of `MeshReach.deliverable`, and drawn for the same
     * reason the WiFi one was: a scanning radio with an empty room is not a
     * route. A link only counts once its packet characteristic has been
     * discovered — a half-open GATT connection cannot carry anything.
     *
     * Called on every event that can change the answer, so the core's routing
     * never plans around a peer that has walked away.
     */
    private fun publishReach() {
        val reachable = links.values.any { it.characteristic != null } || inbound.isNotEmpty()
        runCatching { ComradeCore.bleSetActive(reachable) }
    }

    /** Whether the radio is currently running, for diagnostics and tests. */
    @Volatile
    var isRunning: Boolean = false
        private set

    /**
     * Whether this build can run the mesh at all: the hardware exists, it is
     * turned on, and the user has granted scan + advertise.
     *
     * Checked before every start rather than cached — Bluetooth can be turned
     * off at any moment, and a permission can be revoked from Settings while
     * the app is running.
     */
    fun isAvailable(context: Context): Boolean {
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return false
        val adapter = manager.adapter ?: return false
        if (!adapter.isEnabled) return false
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            return false
        }
        return requiredPermissions().all {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * The runtime permissions the mesh needs, for the caller's permission
     * launcher. Empty below API 31, where they are install-time.
     */
    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            emptyArray()
        }

    /**
     * Bring the radio up: GATT server, advertising, duty-cycled scanning, and
     * the pump that moves packets between the core and the air.
     *
     * Idempotent, and a no-op when [isAvailable] is false — a device with
     * Bluetooth off or a permission denied simply has no BLE route, which the
     * core already treats as an ordinary state rather than an error.
     */
    @SuppressLint("MissingPermission") // isAvailable() is the gate; see its doc.
    @Synchronized
    fun start(context: Context) {
        if (isRunning) return
        if (!isAvailable(context)) {
            Log.i(TAG, "Bluetooth mesh unavailable — no radio, disabled, or not permitted")
            return
        }
        val app = context.applicationContext
        val manager = app.getSystemService(BluetoothManager::class.java) ?: return
        adapter = manager.adapter

        runCatching {
            openGattServer(app, manager)
            startAdvertising()
            val scope = CoroutineScope(Dispatchers.IO + Job()).also { this.scope = it }
            jobs += scope.launch { scanDutyCycle(app) }
            jobs += scope.launch { pumpOutbound() }
            isRunning = true
            // Deliberately *not* `bleSetActive(true)` here. The radio being up
            // is not the same as having somewhere to write, and the core treats
            // "BLE is a route" as a reason to spend an outbox attempt — eight of
            // which fail the message. `publishReach` reports the truth as links
            // come and go.
            publishReach()
            Log.i(TAG, "Bluetooth mesh up — looking for peers")
        }.onFailure {
            Log.e(TAG, "could not start the Bluetooth mesh", it)
            stop()
        }
    }

    /** Tear everything down and tell the core Bluetooth is no longer a route. */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun stop() {
        // Told first, so nothing new is queued for a radio that is going away.
        runCatching { ComradeCore.bleSetActive(false) }
        isRunning = false

        jobs.forEach { it.cancel() }
        jobs.clear()
        scope = null

        runCatching { advertiser?.let { adapter?.bluetoothLeAdvertiser?.stopAdvertising(it) } }
        advertiser = null
        runCatching { scanner?.let { adapter?.bluetoothLeScanner?.stopScan(it) } }
        scanner = null
        runCatching { gattServer?.close() }
        gattServer = null

        links.values.forEach { runCatching { it.gatt.close() } }
        links.clear()
        inbound.clear()
        Log.i(TAG, "Bluetooth mesh down")
    }

    // ── Peripheral role: others write packets to us ──────────────────────────

    @SuppressLint("MissingPermission")
    private fun openGattServer(context: Context, manager: BluetoothManager) {
        val server = manager.openGattServer(context, serverCallback) ?: return
        val characteristic = BluetoothGattCharacteristic(
            PACKET_UUID,
            // Write-without-response: a mesh packet is fire-and-forget, and
            // waiting for an ACK per fragment would halve throughput on a link
            // that is already the slowest part of the system.
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(characteristic)
        server.addService(service)
        gattServer = server
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                inbound[device.address] = device
            } else {
                inbound.remove(device.address)
            }
            publishReach()
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (characteristic.uuid == PACKET_UUID) {
                // Straight to the core. It decides everything: whether this is
                // a duplicate, whether to relay it, and whether it completes an
                // envelope. Nothing about the packet is interpreted here.
                runCatching { ComradeCore.bleDeliver(value) }
                    .onFailure { Log.w(TAG, "core refused a packet: ${it.message}") }
            }
            if (responseNeeded) {
                runCatching {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            }
        }
    }

    // ── Central role: we write packets to others ─────────────────────────────

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        val advertiser = adapter?.bluetoothLeAdvertiser ?: return
        val settings = AdvertiseSettings.Builder()
            // Balanced, not low-latency: this advertisement runs for as long as
            // the app is unlocked, so its power cost is paid all day.
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()
        // Service UUID only. No device name, no manufacturer data — the less a
        // passive listener gets, the better, and the UUID is the irreducible
        // minimum for two Comrades to find each other.
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        val callback = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) {
                // Common and survivable: some chipsets cannot advertise while
                // scanning, or run out of advertisement slots. Such a device
                // still *finds* others and can still send to them — it just
                // cannot be found first.
                Log.w(TAG, "advertising failed ($errorCode) — this device can connect but not be found")
            }
        }
        advertiser.startAdvertising(settings, data, callback)
        this.advertiser = callback
    }

    @SuppressLint("MissingPermission")
    private suspend fun scanDutyCycle(context: Context) {
        val scanner = adapter?.bluetoothLeScanner ?: return
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build())
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                connectTo(context, result.device)
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "scan failed ($errorCode)")
            }
        }
        this.scanner = callback

        val currentScope = scope ?: return
        while (currentScope.isActive) {
            runCatching { scanner.startScan(filters, settings, callback) }
            delay(SCAN_ON_MS)
            runCatching { scanner.stopScan(callback) }
            delay(SCAN_OFF_MS)
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectTo(context: Context, device: BluetoothDevice) {
        // Already linked, or a link is being set up: a scan reports the same
        // device on every advertisement interval, so this is the common case.
        if (links.containsKey(device.address)) return
        val gatt = device.connectGatt(context, false, clientCallback, BluetoothDevice.TRANSPORT_LE)
            ?: return
        links[device.address] = Link(gatt)
    }

    private val clientCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    // Ask for a bigger MTU before discovering services: a
                    // 23-byte default would fragment a short message into
                    // dozens of packets.
                    if (!gatt.requestMtu(DESIRED_MTU)) gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    links.remove(gatt.device.address)
                    runCatching { gatt.close() }
                    publishReach()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            val usable = (mtu - ATT_OVERHEAD).coerceAtLeast(0)
            links[gatt.device.address]?.mtu = usable
            // The core sizes fragments from this. Reported as *usable payload*,
            // with the ATT overhead already subtracted, so the Rust side never
            // has to know a BLE-specific constant.
            runCatching { ComradeCore.bleSetMtu(usable) }
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val characteristic = gatt.getService(SERVICE_UUID)?.getCharacteristic(PACKET_UUID)
            if (characteristic == null) {
                Log.w(TAG, "peer advertised Comrade but has no packet characteristic")
                return
            }
            links[gatt.device.address]?.characteristic = characteristic
            // *Now* there is somewhere to write — not when the TCP-equivalent
            // connection opened.
            publishReach()
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid == PACKET_UUID) {
                runCatching { ComradeCore.bleDeliver(value) }
            }
        }
    }

    // ── The pump: core → air ─────────────────────────────────────────────────

    /**
     * Collect packets the core wants sent and write one copy to every linked
     * peer.
     *
     * Flooding to *all* links rather than picking one is the point: a mesh
     * exists because every device passes on what it hears, and the core has
     * already bounded how far that goes (TTL) and how often (dedup), so this
     * layer does not need a routing decision of its own.
     */
    @SuppressLint("MissingPermission")
    private suspend fun pumpOutbound() {
        val currentScope = scope ?: return
        while (currentScope.isActive) {
            val packets = runCatching { ComradeCore.bleDrainOutbound() }.getOrDefault(emptyList())
            if (packets.isEmpty()) {
                delay(PUMP_INTERVAL_MS)
                continue
            }
            for (packet in packets) {
                var delivered = false
                for (link in links.values) {
                    val characteristic = link.characteristic ?: continue
                    val ok = runCatching {
                        writePacket(link, characteristic, packet)
                    }.getOrDefault(false)
                    delivered = delivered || ok
                }
                // Peers connected to *our* server are reached by notifying them
                // instead — a device that could not advertise still has an
                // inbound link, and forgetting it would strand exactly the
                // chipsets that already had the worst time.
                notifyInbound(packet)
                if (!delivered && inbound.isEmpty()) {
                    Log.d(TAG, "a packet had nowhere to go — nobody in range yet")
                }
            }
            delay(PUMP_INTERVAL_MS)
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION") // The pre-33 write API is the only one below Tiramisu.
    private fun writePacket(
        link: Link,
        characteristic: BluetoothGattCharacteristic,
        packet: ByteArray,
    ): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            link.gatt.writeCharacteristic(
                characteristic,
                packet,
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
            ) == BluetoothGatt.GATT_SUCCESS
        } else {
            characteristic.value = packet
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            link.gatt.writeCharacteristic(characteristic)
        }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun notifyInbound(packet: ByteArray) {
        val server = gattServer ?: return
        val characteristic =
            server.getService(SERVICE_UUID)?.getCharacteristic(PACKET_UUID) ?: return
        for (device in inbound.values) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    server.notifyCharacteristicChanged(device, characteristic, false, packet)
                } else {
                    characteristic.value = packet
                    server.notifyCharacteristicChanged(device, characteristic, false)
                }
            }
        }
    }
}
