package com.glyph.receiver

import android.app.Service
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.Context
import android.content.ComponentName
import android.content.ContentValues
import android.content.pm.ServiceInfo
import android.media.MediaPlayer
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Log
import android.util.Base64

import com.nothing.ketchum.Glyph
import com.nothing.ketchum.Common
import com.nothing.ketchum.GlyphFrame
import com.nothing.ketchum.GlyphManager
import com.nothing.ketchum.GlyphException

import java.io.File
import java.io.InputStream
import java.io.PrintWriter
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.net.ServerSocket
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.UUID
import java.util.SortedMap
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap

import kotlin.math.pow
import kotlin.math.roundToInt

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

import org.json.JSONArray
import org.json.JSONObject

class MainService : Service() {

    enum class ConnectionStatus {
        DISCONNECTED,
        CONNECTED
    }

    // Companion Section
    companion object {
        const val server_port:                             Int    = 7777
        const val discovery_port:                          Int    = 7778

        private const val log_tag:                         String = "GlyphReceiver"
        private const val notification_channel_identifier: String = "glyph_channel"
        private const val notification_identifier:         Int    = 1
        private const val frame_period_ms:                 Int    = 10
        private const val maximum_brightness:              Double = 4095.0
        private const val animation_step_ms:               Long   = 16L
        private const val initial_capacity:                Int    = 50000
        private const val batch_size:                      Int    = 1000
        private const val socket_timeout_ms:               Int    = 10000
        private const val device_identifier_4a:            String = "25111"
        private const val device_identifier_4b:            String = "25131"

        private val mutable_connection_state = MutableStateFlow(ConnectionStatus.DISCONNECTED)
        val connection_state: StateFlow<ConnectionStatus> = mutable_connection_state.asStateFlow()

        private val mutable_client_endpoint = MutableStateFlow<String?>(null)
        val client_endpoint: StateFlow<String?> = mutable_client_endpoint.asStateFlow()

        private fun build_device_track_map(tracks: Map<String, IntArray>): Map<String, IntArray> {
            val result_map: MutableMap<String, IntArray> = tracks.toMutableMap()

            if (result_map.containsKey("A")) {
                return result_map
            }

            val all_channels: IntArray = tracks.entries
                .sortedBy { entry: Map.Entry<String, IntArray> -> entry.key.toIntOrNull() ?: Int.MAX_VALUE }
                .flatMap { entry: Map.Entry<String, IntArray> -> entry.value.toList() }
                .toIntArray()

            result_map["A"] = all_channels

            return result_map
        }

        private fun build_segmented_bar_track_map(segment_count: Int): Map<String, IntArray> {
            val result_map: MutableMap<String, IntArray> = mutableMapOf<String, IntArray>()

            for (index in 0 until segment_count) {
                result_map[(index + 1).toString()] = intArrayOf(index)
            }

            result_map["A"] = (0 until segment_count).toList().toIntArray()

            return result_map
        }
    }

    // Properties Section
    private var active_client_socket:            Socket?                                = null
    private var is_glyph_initialized:            Boolean                                = false
    private var is_session_opened:                Boolean                                = false

    private var server_socket:                   ServerSocket?                          = null
    private var udp_socket:                      DatagramSocket?                        = null
    private var glyph_manager:                   GlyphManager?                          = null
    private var wake_lock:                       PowerManager.WakeLock?                 = null
    private var wifi_lock:                       WifiManager.WifiLock?                  = null
    private var low_latency_wifi_lock:           WifiManager.WifiLock?                  = null
    private var multicast_lock:                  WifiManager.MulticastLock?             = null
    private var connection_sound:                MediaPlayer?                           = null
    private var disconnection_sound:             MediaPlayer?                           = null
    private var animation_job:                   Job?                                   = null
    private var connect_animation_job:           Job?                                   = null
    private var current_device_identifier:       String?                                = null
    private var current_track_map:               Map<String, IntArray>?                 = null

    private var timeline_built:                  Boolean                                = false
    private var socket_server_started:           Boolean                                = false
    private var udp_server_started:              Boolean                                = false
    private var maximum_timeline_ms:             Long                                   = 0L
    private var connect_animation_maximum_ms:    Long                                   = 0L
    private var disconnect_animation_maximum_ms: Long                                   = 0L
    private var playback_speed:                  Double                                 = 1.0
    private var current_timeline_position:       Double                                 = 0.0

    private val timeline_mutex:                  Mutex                                  = Mutex()
    private val playback_executor                                                       = Executors.newSingleThreadExecutor { runnable: Runnable ->
        Thread(runnable, "GlyphPlaybackThread").apply {
            priority = Thread.MAX_PRIORITY
        }
    }
    private val playback_dispatcher                                                     = playback_executor.asCoroutineDispatcher()
    private val service_scope:                   CoroutineScope                         = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val glyph_map:                       ConcurrentHashMap<String, JSONObject> = ConcurrentHashMap<String, JSONObject>(initial_capacity)
    private val precomputed_events:              ConcurrentSkipListMap<Long, IntArray>  = ConcurrentSkipListMap<Long, IntArray>()
    private val connect_animation_events:        SortedMap<Long, IntArray>              = sortedMapOf<Long, IntArray>()
    private val disconnect_animation_events:     SortedMap<Long, IntArray>              = sortedMapOf<Long, IntArray>()

    // Track Configurations Section
    private val track_map_phone_1: Map<String, IntArray> = build_device_track_map(
        mapOf(
            "1" to intArrayOf(0),
            "2" to intArrayOf(1),
            "3" to intArrayOf(4),
            "4" to intArrayOf(5),
            "5" to intArrayOf(2),
            "6" to intArrayOf(3),
            "7" to (7 .. 14).toList().toIntArray(),
            "8" to intArrayOf(6)
        )
    )

    private val track_map_phone_2: Map<String, IntArray> = build_device_track_map(
        mapOf(
            "1"  to intArrayOf(0),
            "2"  to intArrayOf(1),
            "3"  to intArrayOf(2),
            "4"  to (3 .. 18).toList().toIntArray(),
            "5"  to intArrayOf(19),
            "6"  to intArrayOf(20),
            "7"  to intArrayOf(21),
            "8"  to intArrayOf(22),
            "9"  to intArrayOf(23),
            "10" to (25 .. 32).toList().toIntArray(),
            "11" to intArrayOf(24)
        )
    )

    private val track_map_phone_2a: Map<String, IntArray> = build_device_track_map(
        mapOf(
            "1" to (0 .. 23).toList().toIntArray(),
            "2" to intArrayOf(24),
            "3" to intArrayOf(25)
        )
    )

    private val track_map_phone_3a: Map<String, IntArray> = build_device_track_map(
        mapOf(
            "1" to (0 .. 19).toList().toIntArray(),
            "2" to (20 .. 30).toList().toIntArray(),
            "3" to intArrayOf(35, 34, 33, 32, 31)
        )
    )

    private val track_map_phone_4a: Map<String, IntArray> = build_segmented_bar_track_map(7)
    private val track_map_phone_4b: Map<String, IntArray> = build_segmented_bar_track_map(5)

    private val track_map_by_model: Map<String, Map<String, IntArray>> = mapOf(
        "20111"  to track_map_phone_1,
        "A063"   to track_map_phone_1,

        "22111"  to track_map_phone_2,
        "A065"   to track_map_phone_2,
        "AIN065" to track_map_phone_2,

        "23111"  to track_map_phone_2a,
        "23113"  to track_map_phone_2a,
        "A142"   to track_map_phone_2a,
        "A142P"  to track_map_phone_2a,

        "24111"  to track_map_phone_3a,
        "A059"   to track_map_phone_3a,
        "A059P"  to track_map_phone_3a,

        "25111"  to track_map_phone_4a,
        "A069"   to track_map_phone_4a,

        "25131"  to track_map_phone_4b,
        "A009P"  to track_map_phone_4b
    )

    // Lifecycle Section
    override fun onCreate(): Unit {
        super.onCreate()

        elevate_process_priority()
        acquire_screen_lock()
        acquire_hardware_wifi_lock()
        create_notification_channel()
        ensure_device_registered_immediately()
    }

    override fun onStartCommand(
        intent:           Intent?,
        flags:            Int,
        start_identifier: Int
    ): Int {
        elevate_process_priority()
        start_foreground_notification()
        ensure_device_registered_immediately()
        initialize_glyph_manager()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy(): Unit {
        cleanup()
        super.onDestroy()
    }

    // Process Priority Section
    private fun elevate_process_priority(): Unit {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        }

        catch (exception: Exception) {
            Log.w(log_tag, "Could not set urgent audio thread priority: ${exception.message}")
        }
    }

    // Power & Hardware Wi-Fi Lock Section
    private fun acquire_screen_lock(): Unit {
        if (wake_lock != null) {
            return
        }

        val power_manager: PowerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

        wake_lock = power_manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Cassette::WakeLock").apply {
            setReferenceCounted(false)
            acquire(86400000L)
        }
    }

    @Suppress("DEPRECATION")
    private fun acquire_hardware_wifi_lock(): Unit {
        try {
            val wifi_manager: WifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                low_latency_wifi_lock = wifi_manager.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY,
                    "Cassette::LowLatencyWifi"
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }

            wifi_lock = wifi_manager.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "Cassette::HighPerfWifi"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }

            multicast_lock = wifi_manager.createMulticastLock("Cassette::MulticastLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        }

        catch (exception: Exception) {
            Log.w(log_tag, "Could not acquire Wi-Fi Hardware Locks: ${exception.message}")
        }
    }

    // Notification Section
    private fun create_notification_channel(): Unit {
        val channel: NotificationChannel = NotificationChannel(
            notification_channel_identifier,
            "Glyph Receiver",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Maintains connection with Cassette desktop app"
            setShowBadge(false)
        }

        val manager: NotificationManager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun start_foreground_notification(): Unit {
        val notification: Notification = Notification.Builder(this, notification_channel_identifier)
            .setContentTitle("Cassette Receiver")
            .setContentText("Background service active. Ready to connect.")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                notification_identifier,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )

            return
        }

        startForeground(notification_identifier, notification)
    }

    // Audio & Preloaded Animation Section
    private fun play_connection_success_sound(): Unit {
        try {
            val media_player: MediaPlayer = connection_sound ?: return

            if (media_player.isPlaying) {
                return
            }

            media_player.start()

            if (connect_animation_events.isEmpty()) {
                return
            }

            play_isolated_animation(
                connect_animation_events,
                connect_animation_maximum_ms
            )
        }

        catch (exception: Exception) {
            Log.w(log_tag, "Connection sound error: ${exception.message}")
        }
    }

    private suspend fun play_goodbye_sound(): Unit {
        try {
            val media_player: MediaPlayer = disconnection_sound ?: return

            if (media_player.isPlaying) {
                return
            }

            val completion: CompletableDeferred<Unit> = CompletableDeferred<Unit>()

            media_player.setOnCompletionListener {
                completion.complete(Unit)
            }

            media_player.start()

            if (disconnect_animation_events.isNotEmpty()) {
                play_isolated_animation(
                    disconnect_animation_events,
                    disconnect_animation_maximum_ms
                )
            }

            completion.await()
        }

        catch (exception: Exception) {
            Log.w(log_tag, "Disconnect sound error: ${exception.message}")
        }
    }

    private fun play_isolated_animation(
        events:     SortedMap<Long, IntArray>,
        maximum_ms: Long
    ): Unit {
        connect_animation_job?.cancel()

        connect_animation_job = service_scope.launch(playback_dispatcher) {
            elevate_process_priority()

            val state: MutableMap<Int, Int> = mutableMapOf<Int, Int>()
            var position: Double            = 0.0
            var last_real_time: Long        = System.currentTimeMillis()

            while (isActive && position <= maximum_ms) {
                val current_time: Long = System.currentTimeMillis()
                val delta_time: Long   = current_time - last_real_time

                last_real_time         = current_time
                position              += delta_time

                val previous_position: Long = (position - delta_time).toLong().coerceAtLeast(0L)
                val window: SortedMap<Long, IntArray> = events.subMap(
                    previous_position,
                    position.toLong() + 1L
                )

                var has_changed: Boolean = false

                for (event_array in window.values) {
                    for (index in event_array.indices step 2) {
                        state[event_array[index]] = event_array[index + 1]
                    }

                    has_changed = true
                }

                if (has_changed) {
                    val frame_channels: Map<Int, Int> = state.filterValues { value: Int -> value > 0 }

                    update_glyph_frame(frame_channels)
                }

                delay(animation_step_ms)
            }
        }
    }

    private fun preload_animations(): Unit {
        val device_identifier: String = current_device_identifier ?: return

        connection_sound    = MediaPlayer.create(this, R.raw.connect)
        disconnection_sound = MediaPlayer.create(this, R.raw.disconnect)

        val connect_json: JSONObject?    = load_model_json("connect", device_identifier)
        val disconnect_json: JSONObject? = load_model_json("disconnect", device_identifier)

        if (connect_json != null) {
            connect_animation_maximum_ms = precompile_animation_events(connect_json, connect_animation_events)
        }

        if (disconnect_json != null) {
            disconnect_animation_maximum_ms = precompile_animation_events(disconnect_json, disconnect_animation_events)
        }
    }

    private fun extract_glyphs_array(json_object: JSONObject): JSONArray? {
        if (!json_object.has("glyphs")) {
            return null
        }

        val glyphs_raw: Any = json_object.get("glyphs")

        if (glyphs_raw is JSONArray) {
            return glyphs_raw
        }

        if (glyphs_raw is JSONObject) {
            val array: JSONArray       = JSONArray()
            val keys: Iterator<String> = glyphs_raw.keys()

            while (keys.hasNext()) {
                val identifier: String     = keys.next()
                val glyph_item: JSONObject = glyphs_raw.getJSONObject(identifier)

                glyph_item.put("id", identifier)
                array.put(glyph_item)
            }

            return array
        }

        return null
    }

    private fun precompile_animation_events(
        json_object: JSONObject,
        target_map:  SortedMap<Long, IntArray>
    ): Long {
        target_map.clear()

        val glyphs_array: JSONArray = extract_glyphs_array(json_object) ?: return 0L

        val temporary_glyph_map: MutableMap<String, JSONObject>                        = mutableMapOf<String, JSONObject>()
        val temporary_event_changes: SortedMap<Long, MutableList<Pair<IntArray, Int>>> = sortedMapOf<Long, MutableList<Pair<IntArray, Int>>>()

        for (index in 0 until glyphs_array.length()) {
            val glyph: JSONObject  = glyphs_array.getJSONObject(index)
            val identifier: String = glyph.optString("id", UUID.randomUUID().toString())

            glyph.put("id", identifier)
            temporary_glyph_map[identifier] = glyph
        }

        for ((_, glyph) in temporary_glyph_map) {
            process_glyph_events(glyph, temporary_event_changes)
        }

        compile_events_into(temporary_event_changes, target_map)

        return target_map.keys.lastOrNull() ?: 0L
    }

    private fun load_model_json(
        name:              String,
        device_identifier: String
    ): JSONObject? {
        val candidate_names: List<String> = listOf(
            "${name}_${device_identifier}".lowercase(),
            "${name}_24111",
            "${name}_a059"
        ).distinct()

        for (candidate in candidate_names) {
            val resource_identifier: Int = resources.getIdentifier(
                candidate,
                "raw",
                packageName
            )

            if (resource_identifier == 0) {
                continue
            }

            return try {
                val input_stream: InputStream = resources.openRawResource(resource_identifier)
                val json_text: String         = input_stream.bufferedReader().use { reader: BufferedReader -> reader.readText() }

                JSONObject(json_text)
            }

            catch (exception: Exception) {
                null
            }
        }

        return null
    }

    // Device Detection Section
    private fun detect_device_identifier(): String? {
        val model: String  = Build.MODEL.uppercase()
        val device: String = Build.DEVICE.uppercase()

        if (Common.is20111() || model.contains("A063") || device.contains("20111")) {
            return Glyph.DEVICE_20111
        }

        if (Common.is22111() || model.contains("A065") || model.contains("AIN065") || device.contains("22111")) {
            return Glyph.DEVICE_22111
        }

        if (Common.is23111() || model.contains("A142") || device.contains("23111")) {
            return Glyph.DEVICE_23111
        }

        if (Common.is23113() || model.contains("A142P") || device.contains("23113")) {
            return Glyph.DEVICE_23113
        }

        if (Common.is24111() || model.contains("A059") || model.contains("A059P") || device.contains("24111")) {
            return Glyph.DEVICE_24111
        }

        if (model.contains("A069") || device.contains("25111")) {
            return device_identifier_4a
        }

        if (model.contains("A009P") || device.contains("25131")) {
            return device_identifier_4b
        }

        return null
    }

    private fun ensure_device_registered_immediately(): Unit {
        if (current_track_map != null && current_device_identifier != null) {
            return
        }

        val detected_identifier: String = detect_device_identifier() ?: return
        current_device_identifier       = detected_identifier
        current_track_map               = track_map_by_model[detected_identifier]
    }

    private fun initialize_glyph_manager(): Unit {
        ensure_device_registered_immediately()

        if (!is_glyph_initialized || glyph_manager == null) {
            glyph_manager = GlyphManager.getInstance(applicationContext)

            glyph_manager?.init(object : GlyphManager.Callback {
                override fun onServiceConnected(name: ComponentName?): Unit {
                    is_glyph_initialized = true
                    register_device_and_open_session()
                }

                override fun onServiceDisconnected(name: ComponentName?): Unit {
                    is_glyph_initialized = false
                    is_session_opened     = false

                    glyph_manager?.closeSession()

                    service_scope.launch {
                        delay(1000L)

                        if (!is_glyph_initialized) {
                            initialize_glyph_manager()
                        }
                    }
                }
            })
        }

        else if (!is_session_opened) {
            register_device_and_open_session()
        }

        if (!socket_server_started) {
            service_scope.launch(Dispatchers.IO) {
                start_socket_server()
            }
        }

        if (!udp_server_started) {
            service_scope.launch(Dispatchers.IO) {
                start_udp_discovery_responder()
            }
        }
    }

    private fun register_device_and_open_session(): Unit {
        val detected_identifier: String = detect_device_identifier() ?: return

        current_device_identifier = detected_identifier
        current_track_map         = track_map_by_model[detected_identifier]

        try {
            glyph_manager?.register(detected_identifier)
            glyph_manager?.openSession()

            is_session_opened = true

            preload_animations()
        }

        catch (exception: GlyphException) {
            Log.e(log_tag, "Failed to open session: ${exception.message}")

            is_session_opened = false

            service_scope.launch {
                delay(800L)

                if (!is_session_opened) {
                    try {
                        glyph_manager?.openSession()

                        is_session_opened = true

                        preload_animations()
                    }

                    catch (retry_exception: Exception) {
                        Log.e(log_tag, "Retry open session failed: ${retry_exception.message}")
                    }
                }
            }
        }
    }

    // UDP Discovery Section
    private fun start_udp_discovery_responder(): Unit {
        if (udp_server_started && udp_socket != null && !udp_socket!!.isClosed) {
            return
        }

        elevate_process_priority()

        try {
            udp_socket = DatagramSocket(discovery_port, InetAddress.getByName("0.0.0.0")).apply {
                broadcast    = true
                reuseAddress = true
            }

            udp_server_started = true

            val buffer: ByteArray = ByteArray(1024)

            while (service_scope.isActive) {
                val packet: DatagramPacket = DatagramPacket(buffer, buffer.size)

                udp_socket?.receive(packet)

                val request: String = String(packet.data, 0, packet.length).trim()

                if (!request.contains("CASSETTE_DISCOVERY_PROBE")) {
                    continue
                }

                val reply_json: JSONObject = JSONObject().apply {
                    put("service", "cassette_receiver")
                    put("status", "ready")
                    put("tcp_port", server_port)
                    put("device", current_device_identifier ?: "unknown")
                }

                val reply_bytes: ByteArray = (reply_json.toString() + "\n").toByteArray()

                val response_packet: DatagramPacket = DatagramPacket(
                    reply_bytes,
                    reply_bytes.size,
                    packet.address,
                    packet.port
                )

                udp_socket?.send(response_packet)
            }
        }

        catch (exception: Exception) {
            if (service_scope.isActive) {
                Log.w(log_tag, "UDP discovery error: ${exception.message}")
            }
        }

        finally {
            udp_server_started = false
        }
    }

    // TCP Socket Server Section
    private suspend fun start_socket_server(): Unit {
        if (socket_server_started && server_socket != null && !server_socket!!.isClosed) {
            return
        }

        try {
            val server: ServerSocket = ServerSocket().apply {
                reuseAddress = true

                setPerformancePreferences(0, 2, 1)
                bind(InetSocketAddress(server_port))
            }

            server_socket         = server
            socket_server_started = true

            while (true) {
                val client_socket: Socket = server.accept()

                active_client_socket?.let { previous_socket: Socket ->
                    close_socket(previous_socket)
                }

                active_client_socket = client_socket

                client_socket.setPerformancePreferences(0, 2, 1)
                client_socket.tcpNoDelay        = true
                client_socket.trafficClass      = 0x10
                client_socket.keepAlive         = true
                client_socket.soTimeout         = socket_timeout_ms
                client_socket.sendBufferSize    = 65536
                client_socket.receiveBufferSize = 65536

                val remote_ip: String = client_socket.inetAddress.hostAddress ?: "unknown"

                mutable_client_endpoint.value  = remote_ip
                mutable_connection_state.value = ConnectionStatus.CONNECTED

                play_connection_success_sound()

                service_scope.launch(Dispatchers.IO) {
                    elevate_process_priority()
                    handle_client(client_socket)
                }
            }
        }

        catch (exception: Exception) {
            Log.e(log_tag, "Socket server error: ${exception.message}")
        }
    }

    private suspend fun handle_client(client_socket: Socket): Unit {
        var heartbeat_job: Job? = null

        try {
            val reader: BufferedReader = BufferedReader(InputStreamReader(client_socket.getInputStream()))
            val writer: PrintWriter    = PrintWriter(OutputStreamWriter(client_socket.getOutputStream()), true)

            heartbeat_job = service_scope.launch(Dispatchers.IO) {
                elevate_process_priority()

                while (isActive) {
                    delay(1000L)

                    writer.println("{}")

                    if (writer.checkError()) {
                        close_socket(client_socket)

                        break
                    }
                }
            }

            while (true) {
                val line: String? = reader.readLine()

                if (line == null) {
                    break
                }

                if (line.isBlank()) {
                    continue
                }

                process_command(line, writer)
            }
        }

        catch (exception: Exception) {
            Log.w(log_tag, "Client connection exception: ${exception.message}")
        }

        finally {
            heartbeat_job?.cancel()
            heartbeat_job = null

            close_socket(client_socket)

            if (active_client_socket == client_socket) {
                active_client_socket = null

                mutable_connection_state.value = ConnectionStatus.DISCONNECTED
                mutable_client_endpoint.value  = null

                stop_all()
            }
        }
    }

    private fun close_socket(target_socket: Socket): Unit {
        try {
            target_socket.close()
        }

        catch (exception: Exception) {
            Log.w(log_tag, "Error closing client socket: ${exception.message}")
        }
    }

    // Command Dispatcher Section
    private suspend fun process_command(
        command_line: String,
        writer:       PrintWriter
    ): Unit {
        try {
            val json_command: JSONObject = JSONObject(command_line)
            val action: String            = json_command.optString("action")

            when (action) {
                "load" -> {
                    handle_load_action(json_command)
                }

                "update" -> {
                    handle_update_action(json_command)
                }

                "delete" -> {
                    handle_delete_action(json_command)
                }

                "play" -> {
                    handle_play_action(json_command)
                }

                "stop" -> {
                    handle_stop_action()
                }

                "ping" -> {
                    handle_ping_action(json_command, writer)
                }

                "stop_app" -> {
                    handle_stop_app()
                }

                "pulse" -> {
                    handle_pulse_action(json_command)
                }

                "set_speed" -> {
                    handle_set_speed_action(json_command)
                }

                "save_ringtone" -> {
                    handle_save_ringtone_action(json_command)
                }
            }
        }

        catch (exception: Exception) {
            Log.e(log_tag, "Command error: ${exception.message}")
        }
    }

    private fun handle_set_speed_action(json_command: JSONObject): Unit {
        val speed: Double = json_command.optDouble("value", 1.0)

        if (speed >= 0.0) {
            playback_speed = speed
        }
    }

    private fun handle_ping_action(
        json_command: JSONObject,
        writer:       PrintWriter
    ): Unit {
        val timestamp: Double      = json_command.optDouble("timestamp", 0.0)
        val reply_json: JSONObject = JSONObject().apply {
            put("action", "pong")
            put("timestamp", timestamp)
        }

        writer.println(reply_json.toString())
        writer.flush()
    }

    private fun handle_save_ringtone_action(json_command: JSONObject): Unit {
        val file_name: String       = json_command.optString("name", "ringtone.ogg")
        val encoded_content: String = json_command.optString("content", "")

        if (encoded_content.isEmpty()) {
            return
        }

        service_scope.launch(Dispatchers.IO) {
            try {
                val audio_bytes: ByteArray = Base64.decode(encoded_content, Base64.DEFAULT)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val relative_directory_path: String = "${Environment.DIRECTORY_RINGTONES}/Compositions/"

                    val content_values: ContentValues = ContentValues().apply {
                        put(MediaStore.Audio.Media.DISPLAY_NAME, file_name)
                        put(MediaStore.Audio.Media.MIME_TYPE, "audio/ogg")
                        put(MediaStore.Audio.Media.RELATIVE_PATH, relative_directory_path)
                        put(MediaStore.Audio.Media.IS_RINGTONE, 1)
                    }

                    val content_resolver = applicationContext.contentResolver
                    val audio_uri        = content_resolver.insert(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        content_values
                    )

                    if (audio_uri != null) {
                        content_resolver.openOutputStream(audio_uri)?.use { output_stream ->
                            output_stream.write(audio_bytes)
                        }
                    }
                }

                else {
                    val base_directory      = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_RINGTONES)
                    val compositions_folder = File(base_directory, "Compositions")

                    if (!compositions_folder.exists()) {
                        compositions_folder.mkdirs()
                    }

                    val destination_file = File(compositions_folder, file_name)
                    destination_file.writeBytes(audio_bytes)
                }
            }

            catch (exception: Exception) {
                Log.e(log_tag, "Failed to save ringtone: ${exception.message}")
            }
        }
    }

    private fun handle_stop_app(): Unit {
        service_scope.launch {
            play_goodbye_sound()
            cleanup()
            stopSelf()
        }
    }

    private fun handle_stop_action(): Unit {
        stop_all()
    }

    private fun handle_play_action(json_object: JSONObject): Unit {
        val from_ms: Long = json_object.optLong("from_ms", 0L)

        play_from_timeline(from_ms)
    }

    private suspend fun handle_delete_action(json_object: JSONObject): Unit {
        val identifiers_to_delete: JSONArray = json_object.getJSONArray("ids")

        timeline_mutex.withLock {
            for (index in 0 until identifiers_to_delete.length()) {
                val identifier: String = identifiers_to_delete.getString(index)

                glyph_map.remove(identifier)
                remove_effect_glyphs(identifier)
            }

            build_timeline_internal()
        }
    }

    private suspend fun handle_load_action(json_object: JSONObject): Unit {
        timeline_mutex.withLock {
            glyph_map.clear()
            precomputed_events.clear()

            timeline_built = false

            val glyphs: JSONArray = json_object.getJSONArray("glyphs")
            val total_glyphs: Int = glyphs.length()

            for (batch_start in 0 until total_glyphs step batch_size) {
                val batch_end: Int = minOf(batch_start + batch_size, total_glyphs)

                for (index in batch_start until batch_end) {
                    val glyph: JSONObject  = glyphs.getJSONObject(index)
                    val identifier: String = glyph.optString("id", UUID.randomUUID().toString())

                    if (glyph.has("effect") && glyph.has("effect_to_glyphs")) {
                        unpack_effect_glyphs(identifier, glyph)
                    }

                    else {
                        glyph.put("id", identifier)
                        glyph_map[identifier] = glyph
                    }
                }

                if (batch_start % (batch_size * 5) == 0) {
                    yield()
                }
            }

            build_timeline_internal()
        }
    }

    private suspend fun handle_update_action(json_object: JSONObject): Unit {
        timeline_mutex.withLock {
            val glyphs: JSONObject     = json_object.getJSONObject("glyphs")
            val keys: Iterator<String> = glyphs.keys()

            while (keys.hasNext()) {
                val identifier: String = keys.next()
                val glyph: JSONObject  = glyphs.getJSONObject(identifier)

                remove_effect_glyphs(identifier)
                glyph_map.remove(identifier)

                if (glyph.has("effect") && glyph.has("effect_to_glyphs")) {
                    unpack_effect_glyphs(identifier, glyph)
                }

                else {
                    glyph.put("id", identifier)
                    glyph_map[identifier] = glyph
                }
            }

            build_timeline_internal()
        }
    }

    private fun handle_pulse_action(json_object: JSONObject): Unit {
        val track_name: String = json_object.optString("track", null) ?: return
        val channels: IntArray = resolve_track_channels(track_name) ?: return

        service_scope.launch(playback_dispatcher) {
            elevate_process_priority()

            val duration_ms: Long = 200L
            val steps: Int        = (duration_ms / animation_step_ms).toInt()

            for (step in 0 .. steps) {
                val progress: Double   = step.toDouble() / steps
                val eased: Double      = 1.0 - (1.0 - progress).pow(2)
                val brightness: Int    = convert_brightness(100.0 * (1.0 - eased))

                update_glyph_frame(channels.associateWith { brightness })

                delay(animation_step_ms)
            }

            update_glyph_frame(channels.associateWith { 0 })
        }
    }

    // Timeline Processing Section
    private fun unpack_effect_glyphs(
        identifier: String,
        glyph:      JSONObject
    ): Int {
        val effect_glyphs: JSONArray = glyph.getJSONArray("effect_to_glyphs")
        val count: Int               = effect_glyphs.length()
        val base_identifier: String  = "$identifier@"

        for (index in 0 until count) {
            val effect_glyph: JSONObject = effect_glyphs.getJSONObject(index)
            val glyph_identifier: String = "$base_identifier${UUID.randomUUID()}"

            effect_glyph.put("id", glyph_identifier)
            effect_glyph.put("parent_id", identifier)

            glyph_map[glyph_identifier] = effect_glyph
        }

        return count
    }

    private fun remove_effect_glyphs(identifier: String): Int {
        val prefix: String               = "$identifier@"
        val keys_to_remove: List<String> = glyph_map.keys.filter { key: String -> key.startsWith(prefix) }

        keys_to_remove.forEach { key: String ->
            glyph_map.remove(key)
        }

        return keys_to_remove.size
    }

    private fun build_timeline_internal(): Unit {
        precomputed_events.clear()

        val event_changes: SortedMap<Long, MutableList<Pair<IntArray, Int>>> = sortedMapOf<Long, MutableList<Pair<IntArray, Int>>>()
        maximum_timeline_ms = 0L

        for ((_, glyph) in glyph_map.entries) {
            process_glyph_events(glyph, event_changes)
        }

        compile_events_into(event_changes, precomputed_events)

        maximum_timeline_ms = precomputed_events.keys.lastOrNull() ?: 0L
        timeline_built      = true
    }

    private fun process_glyph_events(
        glyph:         JSONObject,
        event_changes: MutableMap<Long, MutableList<Pair<IntArray, Int>>>
    ): Unit {
        val glyph_start: Long    = glyph.getDouble("start").toLong()
        val glyph_duration: Long = glyph.getLong("duration")
        val track_name: String   = glyph.getString("track")

        val channel_list: IntArray = get_channel_list(glyph, track_name) ?: return

        if (glyph.has("keyframes")) {
            process_keyframes_glyph(
                glyph,
                channel_list,
                glyph_start,
                glyph_duration,
                event_changes
            )

            return
        }

        if (glyph.has("brightness")) {
            val start_brightness: Int = convert_brightness(glyph.getDouble("brightness"))
            val end_time: Long        = glyph_start + glyph_duration

            event_changes.getOrPut(glyph_start) { mutableListOf() }.add(channel_list to start_brightness)
            event_changes.getOrPut(end_time) { mutableListOf() }.add(channel_list to -start_brightness)
        }
    }

    private fun process_keyframes_glyph(
        glyph:          JSONObject,
        channel_list:   IntArray,
        glyph_start:    Long,
        glyph_duration: Long,
        event_changes:  MutableMap<Long, MutableList<Pair<IntArray, Int>>>
    ): Unit {
        val keyframes_array: JSONArray = glyph.getJSONArray("keyframes")
        val easing: String             = glyph.optString("easing", "linear")

        val keyframes: List<Pair<Double, Double>> = (0 until keyframes_array.length()).map { index: Int ->
            val keyframe_pair: JSONArray = keyframes_array.getJSONArray(index)
            keyframe_pair.getDouble(0) to keyframe_pair.getDouble(1)
        }.sortedBy { pair: Pair<Double, Double> -> pair.first }

        val steps: Int           = (glyph_duration / animation_step_ms).toInt().coerceAtLeast(2)
        var last_brightness: Int = -1

        for (step in 0 .. steps) {
            val time: Long              = glyph_start + (step * glyph_duration / steps)
            val progress: Double        = step.toDouble() / steps
            val current_brightness: Int = convert_brightness(
                interpolate_keyframes(
                    keyframes,
                    easing,
                    progress
                )
            )

            if (current_brightness == last_brightness) {
                continue
            }

            if (last_brightness != -1) {
                event_changes.getOrPut(time) { mutableListOf() }.add(channel_list to -last_brightness)
            }

            event_changes.getOrPut(time) { mutableListOf() }.add(channel_list to current_brightness)
            last_brightness = current_brightness
        }

        val end_time: Long = glyph_start + glyph_duration

        if (last_brightness > 0) {
            event_changes.getOrPut(end_time) { mutableListOf() }.add(channel_list to -last_brightness)
        }
    }

    private fun compile_events_into(
        event_changes: SortedMap<Long, MutableList<Pair<IntArray, Int>>>,
        target:        MutableMap<Long, IntArray>
    ): Unit {
        val active_brightness: MutableMap<Int, MutableList<Int>> = mutableMapOf<Int, MutableList<Int>>()
        val current_channel_state: MutableMap<Int, Int>          = mutableMapOf<Int, Int>()

        for ((time, changes) in event_changes) {
            val channels_to_update: MutableSet<Int> = mutableSetOf<Int>()

            for ((channels, brightness) in changes) {
                for (channel in channels) {
                    channels_to_update.add(channel)

                    val brightness_list: MutableList<Int> = active_brightness.getOrPut(channel) { mutableListOf() }

                    if (brightness > 0) {
                        brightness_list.add(brightness)
                    }

                    else {
                        brightness_list.remove(-brightness)
                    }
                }
            }

            val frame_events: MutableList<Int> = mutableListOf<Int>()

            for (channel in channels_to_update) {
                val new_brightness: Int = active_brightness[channel]?.maxOrNull() ?: 0

                if (current_channel_state.getOrDefault(channel, 0) == new_brightness) {
                    continue
                }

                current_channel_state[channel] = new_brightness
                frame_events.add(channel)
                frame_events.add(new_brightness)
            }

            if (frame_events.isNotEmpty()) {
                target[time] = frame_events.toIntArray()
            }
        }
    }

    // Playback Engine Section
    private fun play_from_timeline(start_ms: Long): Unit {
        if (!timeline_built) {
            return
        }

        cancel_animation_job()
        current_timeline_position = start_ms.toDouble()

        val initial_state: MutableMap<Int, Int> = mutableMapOf<Int, Int>()

        for ((time, event_array) in precomputed_events.headMap(start_ms + 1L)) {
            for (index in event_array.indices step 2) {
                initial_state[event_array[index]] = event_array[index + 1]
            }
        }

        val initial_frame: Map<Int, Int> = initial_state.filterValues { value: Int -> value > 0 }
        update_glyph_frame(initial_frame)

        animation_job = service_scope.launch(playback_dispatcher) {
            elevate_process_priority()

            var last_real_time: Long = System.currentTimeMillis()

            while (isActive && current_timeline_position <= maximum_timeline_ms) {
                val current_time: Long = System.currentTimeMillis()
                val delta_time: Long   = current_time - last_real_time

                last_real_time              = current_time
                current_timeline_position += delta_time * playback_speed

                val previous_position: Long = (current_timeline_position - (delta_time * playback_speed)).toLong()
                val events_in_window        = precomputed_events.subMap(
                    previous_position,
                    current_timeline_position.toLong() + 1L
                )

                var frame_changed: Boolean = false

                for (event_array in events_in_window.values) {
                    for (index in event_array.indices step 2) {
                        initial_state[event_array[index]] = event_array[index + 1]
                    }

                    frame_changed = true
                }

                if (frame_changed) {
                    val current_frame: Map<Int, Int> = initial_state.filterValues { value: Int -> value > 0 }

                    update_glyph_frame(current_frame)
                }

                delay(animation_step_ms)
            }
        }
    }

    private fun cancel_animation_job(): Unit {
        animation_job?.cancel()
        animation_job = null
    }

    private fun stop_all(): Unit {
        cancel_animation_job()

        service_scope.launch(playback_dispatcher) {
            turn_off_all_channels()
        }
    }

    // Frame Hardware Section
    private fun resolve_track_channels(track_name: String): IntArray? {
        ensure_device_registered_immediately()

        val track_map: Map<String, IntArray> = current_track_map ?: return null

        if (track_name.equals("A", ignoreCase = true)) {
            track_map["A"]?.let { channels: IntArray ->
                return channels
            }

            return track_map.entries
                .filter { entry: Map.Entry<String, IntArray> -> entry.key != "A" }
                .sortedBy { entry: Map.Entry<String, IntArray> -> entry.key.toIntOrNull() ?: Int.MAX_VALUE }
                .flatMap { entry: Map.Entry<String, IntArray> -> entry.value.toList() }
                .toIntArray()
        }

        return track_map[track_name] ?: track_map[track_name.uppercase()]
    }

    private fun get_channel_list(
        glyph:      JSONObject,
        track_name: String
    ): IntArray? {
        if (glyph.has("channels")) {
            val channels_array: JSONArray? = glyph.optJSONArray("channels")

            return IntArray(channels_array?.length() ?: 0) { index: Int ->
                channels_array?.getInt(index) ?: 0
            }
        }

        val full_track_channels: IntArray = resolve_track_channels(track_name) ?: return null

        if (!glyph.has("segments")) {
            return full_track_channels
        }

        val segments_array: JSONArray         = glyph.getJSONArray("segments")
        val segments: IntArray                = IntArray(segments_array.length()) { index: Int -> segments_array.getInt(index) }
        val result_channels: MutableList<Int> = mutableListOf<Int>()

        segments.forEach { segment_index: Int ->
            if (segment_index in full_track_channels.indices) {
                result_channels.add(full_track_channels[segment_index])
            }
        }

        return result_channels.toIntArray()
    }

    private fun is_segmented_device(device_identifier: String): Boolean {
        return device_identifier == device_identifier_4a ||
                device_identifier == device_identifier_4b ||
                device_identifier == "A069" ||
                device_identifier == "A009P"
    }

    private fun resolve_segment_count(device_identifier: String): Int {
        if (device_identifier == device_identifier_4a || device_identifier == "A069") {
            return 7
        }

        return 5
    }

    private fun toggle_glyph_frame_fallback(
        device_identifier: String,
        active_channels:   Map<Int, Int>
    ): Unit {
        try {
            val builder: GlyphFrame.Builder = GlyphFrame.Builder(device_identifier)

            for ((channel, brightness) in active_channels) {
                builder.buildChannel(channel, brightness)
            }

            builder.buildPeriod(frame_period_ms)

            glyph_manager?.toggle(builder.build())
        }

        catch (exception: Exception) {
            Log.e(log_tag, "Fallback toggle failed: ${exception.message}")
        }
    }

    private fun update_glyph_frame(active_channels: Map<Int, Int>): Unit {
        val device_identifier: String = current_device_identifier ?: return

        if (is_segmented_device(device_identifier)) {
            val segment_count: Int = resolve_segment_count(device_identifier)
            val colors: IntArray   = IntArray(segment_count) { index: Int -> active_channels[index] ?: 0 }

            try {
                glyph_manager?.setFrameColors(colors)
            }

            catch (exception: Exception) {
                toggle_glyph_frame_fallback(device_identifier, active_channels)
            }

            return
        }

        toggle_glyph_frame_fallback(device_identifier, active_channels)
    }

    private fun turn_off_all_channels(): Unit {
        val device_identifier: String = current_device_identifier ?: return

        if (is_segmented_device(device_identifier)) {
            val segment_count: Int = resolve_segment_count(device_identifier)

            try {
                glyph_manager?.setFrameColors(IntArray(segment_count) { 0 })
            }

            catch (exception: Exception) {
                toggle_glyph_frame_fallback(device_identifier, emptyMap())
            }

            return
        }

        toggle_glyph_frame_fallback(device_identifier, emptyMap())
    }

    // Interpolation Section
    private fun interpolate_keyframes(
        keyframes: List<Pair<Double, Double>>,
        easing:    String,
        progress:  Double
    ): Double {
        if (keyframes.isEmpty()) {
            return 0.0
        }

        if (progress <= keyframes.first().first) {
            return keyframes.first().second
        }

        if (progress >= keyframes.last().first) {
            return keyframes.last().second
        }

        val next_index: Int = keyframes.indexOfFirst { keyframe: Pair<Double, Double> -> keyframe.first > progress }

        val (previous_progress, previous_brightness) = keyframes[next_index - 1]
        val (next_progress, next_brightness)         = keyframes[next_index]

        val segment_progress: Double = (progress - previous_progress) / (next_progress - previous_progress)

        val eased_progress: Double = when (easing) {
            "ease_in" -> segment_progress * segment_progress

            "ease_out" -> 1.0 - (1.0 - segment_progress).pow(2)

            "ease_in_out" -> if (segment_progress < 0.5) 2.0 * segment_progress.pow(2) else 1.0 - (-2.0 * segment_progress + 2.0).pow(2) / 2.0

            else -> segment_progress
        }

        return previous_brightness + (next_brightness - previous_brightness) * eased_progress
    }

    private fun convert_brightness(brightness: Double): Int {
        return ((brightness.coerceIn(0.0, 100.0) / 100.0) * maximum_brightness).roundToInt()
    }

    // Cleanup Section
    private fun cleanup(): Unit {
        stop_all()

        is_glyph_initialized = false
        is_session_opened     = false
        active_client_socket  = null

        connect_animation_job?.cancel()
        connect_animation_job = null

        glyph_manager?.closeSession()
        glyph_manager?.unInit()

        try {
            server_socket?.close()
            server_socket = null
        }

        catch (exception: Exception) {
            Log.w(log_tag, "Error closing server socket: ${exception.message}")
        }

        try {
            udp_socket?.close()
            udp_socket = null
        }

        catch (exception: Exception) {
            Log.w(log_tag, "Error closing udp socket: ${exception.message}")
        }

        if (wake_lock?.isHeld == true) {
            wake_lock?.release()
        }

        wake_lock = null

        if (low_latency_wifi_lock?.isHeld == true) {
            low_latency_wifi_lock?.release()
        }

        low_latency_wifi_lock = null

        if (wifi_lock?.isHeld == true) {
            wifi_lock?.release()
        }

        wifi_lock = null

        if (multicast_lock?.isHeld == true) {
            multicast_lock?.release()
        }

        multicast_lock = null

        service_scope.cancel()
        playback_executor.shutdown()

        connection_sound?.release()
        connection_sound = null

        disconnection_sound?.release()
        disconnection_sound = null
    }
}