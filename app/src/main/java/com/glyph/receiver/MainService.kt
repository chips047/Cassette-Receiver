package com.glyph.receiver

import android.app.Service
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.ComponentName
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.util.Log

import com.nothing.ketchum.Glyph
import com.nothing.ketchum.Common
import com.nothing.ketchum.GlyphFrame
import com.nothing.ketchum.GlyphManager
import com.nothing.ketchum.GlyphException

import java.io.InputStream
import java.io.PrintWriter
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.net.ServerSocket
import java.net.InetSocketAddress
import java.util.UUID
import java.util.SortedMap
import java.util.concurrent.ConcurrentHashMap

import kotlin.math.pow
import kotlin.math.roundToInt

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

import org.json.JSONArray
import org.json.JSONObject

class MainService : Service() {

    // Companion Section
    companion object {
        private const val log_tag:                         String = "GlyphReceiver"
        private const val notification_channel_identifier: String = "glyph_channel"
        private const val notification_identifier:         Int    = 1
        private const val server_port:                     Int    = 7777
        private const val frame_period_ms:                 Int    = 10
        private const val maximum_brightness:              Double = 4095.0
        private const val animation_step_ms:               Long   = 16L
        private const val initial_capacity:                Int    = 50000
        private const val batch_size:                      Int    = 1000
        private const val device_identifier_4a:            String = "25111"
        private const val device_identifier_4b:            String = "25131"

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
    private var server_socket:                   ServerSocket?                          = null
    private var glyph_manager:                   GlyphManager?                          = null
    private var connection_sound:                MediaPlayer?                           = null
    private var disconnection_sound:             MediaPlayer?                           = null
    private var animation_job:                   Job?                                   = null
    private var connect_animation_job:           Job?                                   = null
    private var current_device_identifier:       String?                                = null
    private var current_track_map:               Map<String, IntArray>?                 = null

    private var timeline_built:                  Boolean                                = false
    private var socket_server_started:           Boolean                                = false
    private var maximum_timeline_ms:             Long                                   = 0L
    private var connect_animation_maximum_ms:    Long                                   = 0L
    private var disconnect_animation_maximum_ms: Long                                   = 0L
    private var playback_speed:                  Double                                 = 1.0
    private var current_timeline_position:       Double                                 = 0.0

    private val data_lock:                       Mutex                                  = Mutex()
    private val service_scope:                   CoroutineScope                         = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val glyph_map:                       ConcurrentHashMap<String, JSONObject> = ConcurrentHashMap<String, JSONObject>(initial_capacity)
    private val precomputed_events:              ConcurrentHashMap<Long, IntArray>      = ConcurrentHashMap<Long, IntArray>(10000)
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

    val track_map_by_model: Map<String, Map<String, IntArray>> = mapOf(
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

    // Service Lifecycle Section
    override fun onCreate(): Unit {
        super.onCreate()
        Log.i(log_tag, "Service created")
        create_notification_channel()
        ensure_device_registered_immediately()
    }

    override fun onStartCommand(
        intent:           Intent?,
        flags:            Int,
        start_identifier: Int
    ): Int {
        Log.i(log_tag, "Service started")
        start_foreground_notification()
        ensure_device_registered_immediately()
        initialize_glyph_manager()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy(): Unit {
        Log.i(log_tag, "Service destroyed")
        cleanup()
        super.onDestroy()
    }

    // Notification Section
    private fun create_notification_channel(): Unit {
        val channel: NotificationChannel = NotificationChannel(
            notification_channel_identifier,
            "Glyph Receiver",
            NotificationManager.IMPORTANCE_LOW
        )

        val manager: NotificationManager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
        Log.i(log_tag, "Notification channel created")
    }

    private fun start_foreground_notification(): Unit {
        val notification: Notification = Notification.Builder(this, notification_channel_identifier)
            .setContentTitle("Glyph Receiver Running")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()

        startForeground(notification_identifier, notification)
        Log.i(log_tag, "Foreground notification started")
    }

    // Feedback Media Section
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
            Log.w(log_tag, "Failed to play connection sound: ${exception.message}")
        }
    }

    private suspend fun play_goodbye_sound(): Unit = withContext(Dispatchers.Main) {
        try {
            val media_player: MediaPlayer = disconnection_sound ?: return@withContext

            if (media_player.isPlaying) {
                return@withContext
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
            Log.w(log_tag, "Failed to play goodbye sound and animation: ${exception.message}")
        }
    }

    private fun play_isolated_animation(
        events: SortedMap<Long, IntArray>,
        max_ms: Long
    ): Unit {
        connect_animation_job?.cancel()

        connect_animation_job = service_scope.launch {
            val state: MutableMap<Int, Int> = mutableMapOf<Int, Int>()
            var position: Double            = 0.0
            var last_real_time: Long        = System.currentTimeMillis()

            while (isActive && position <= max_ms) {
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

                    withContext(Dispatchers.Main) {
                        update_glyph_frame(frame_channels)
                    }
                }

                delay(animation_step_ms)
            }
        }
    }

    // Animation Preload Section
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
                Log.w(log_tag, "Failed to parse $candidate: ${exception.message}")
                null
            }
        }

        return null
    }

    // Device Session Section
    private fun check_device_match(
        check_function:   () -> Boolean,
        model_substring:  String,
        device_substring: String
    ): Boolean {
        return try {
            check_function()
        }

        catch (throwable: Throwable) {
            Build.MODEL.contains(model_substring, ignoreCase = true) || Build.DEVICE.contains(device_substring)
        }
    }

    private fun is_device_25111(): Boolean {
        return check_device_match(
            { Common.is25111() },
            "A069",
            "25111"
        )
    }

    private fun is_device_25131(): Boolean {
        return check_device_match(
            { Common.is25131() },
            "A009P",
            "25131"
        )
    }

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

        if (is_device_25111() || model.contains("A069") || device.contains("25111")) {
            return try {
                Glyph.DEVICE_25111
            }

            catch (throwable: Throwable) {
                device_identifier_4a
            }
        }

        if (is_device_25131() || model.contains("A009P") || device.contains("25131")) {
            return try {
                Glyph.DEVICE_25131
            }

            catch (throwable: Throwable) {
                device_identifier_4b
            }
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

        Log.i(log_tag, "Device track map resolved synchronously: $detected_identifier")
    }

    private fun initialize_glyph_manager(): Unit {
        ensure_device_registered_immediately()

        service_scope.launch {
            Log.i(log_tag, "Initializing GlyphManager")
            glyph_manager = GlyphManager.getInstance(applicationContext)

            glyph_manager?.init(object : GlyphManager.Callback {
                override fun onServiceConnected(name: ComponentName?): Unit {
                    register_device_and_open_session()
                }

                override fun onServiceDisconnected(name: ComponentName?): Unit {
                    Log.w(log_tag, "onServiceDisconnected: $name")
                    glyph_manager?.closeSession()
                }
            })

            start_socket_server()
        }
    }

    private fun register_device_and_open_session(): Unit {
        val detected_identifier: String = detect_device_identifier() ?: run {
            Log.w(log_tag, "Unknown device variant")
            return
        }

        current_device_identifier = detected_identifier
        current_track_map         = track_map_by_model[detected_identifier]

        glyph_manager?.register(detected_identifier)
        Log.i(log_tag, "Registered $detected_identifier")

        try {
            glyph_manager?.openSession()
            Log.i(log_tag, "Glyph session opened")

            preload_animations()
            play_connection_success_sound()
        }

        catch (exception: GlyphException) {
            Log.e(log_tag, "Failed to open session: ${exception.message}")
        }
    }

    // Socket Server Section
    private suspend fun start_socket_server(): Unit {
        if (socket_server_started && server_socket != null && !server_socket!!.isClosed) {
            Log.i(log_tag, "Socket server is already running on port $server_port")
            return
        }

        try {
            val server: ServerSocket = ServerSocket()
            server.reuseAddress      = true

            server.bind(InetSocketAddress(server_port))

            server_socket         = server
            socket_server_started = true
            Log.i(log_tag, "Listening on localhost:$server_port")

            while (true) {
                val socket: Socket = server.accept()
                Log.i(log_tag, "Accepted connection from ${socket.inetAddress}")

                service_scope.launch {
                    handle_client(socket)
                }
            }
        }

        catch (exception: Exception) {
            Log.e(log_tag, "Socket server error: ${exception.message}", exception)
        }
    }

    private suspend fun handle_client(socket: Socket): Unit {
        try {
            val reader: BufferedReader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer: PrintWriter    = PrintWriter(OutputStreamWriter(socket.getOutputStream()), true)

            while (true) {
                val line: String? = reader.readLine()

                if (line.isNullOrBlank()) {
                    Log.d(log_tag, "Received null or blank line, closing connection")
                    break
                }

                Log.i(log_tag, "Received line: $line")
                process_command(line, writer)
            }
        }

        catch (exception: Exception) {
            Log.e(log_tag, "Client socket error: ${exception.message}", exception)
        }

        finally {
            close_socket(socket)
        }
    }

    private fun close_socket(socket: Socket): Unit {
        try {
            socket.close()
            Log.i(log_tag, "Socket closed")
        }

        catch (exception: Exception) {
            Log.w(log_tag, "Error closing socket: ${exception.message}")
        }
    }

    // Command Processing Section
    private fun process_command(
        line:   String,
        writer: PrintWriter
    ): Unit {
        try {
            val json_command: JSONObject = JSONObject(line)
            val action: String            = json_command.optString("action")

            Log.i(log_tag, "Parsed action: $action")

            when (action) {
                "load" -> {
                    service_scope.launch {
                        handle_load_action(json_command)
                    }
                }

                "update" -> {
                    service_scope.launch {
                        handle_update_action(json_command)
                    }
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
                    handle_ping_action(writer)
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

                else -> {
                    Log.w(log_tag, "Unknown action: $action")
                }
            }
        }

        catch (exception: Exception) {
            Log.e(log_tag, "Error parsing command: ${exception.message}", exception)
        }
    }

    private fun handle_set_speed_action(json_command: JSONObject): Unit {
        val speed: Double = json_command.optDouble("value", 1.0)

        if (speed < 0.0) {
            return
        }

        playback_speed = speed
        Log.d(log_tag, "Speed updated to $speed")
    }

    private fun handle_ping_action(writer: PrintWriter): Unit {
        writer.println("pong")
        Log.i(log_tag, "Sent ping response")
    }

    private fun handle_stop_app(): Unit {
        service_scope.launch {
            play_goodbye_sound()
            cleanup()
            stopSelf()
        }
    }

    private fun handle_stop_action(): Unit {
        Log.i(log_tag, "Stop command received")
        stop_all()
    }

    private fun handle_play_action(json_object: JSONObject): Unit {
        val from_ms: Long = json_object.optLong("from_ms", 0L)
        Log.i(log_tag, "Playing from $from_ms ms")
        play_from_timeline(from_ms)
    }

    private fun handle_delete_action(json_object: JSONObject): Unit {
        val identifiers_to_delete: JSONArray = json_object.getJSONArray("ids")

        for (index in 0 until identifiers_to_delete.length()) {
            val identifier: String = identifiers_to_delete.getString(index)

            glyph_map.remove(identifier)
            remove_effect_glyphs(identifier)
        }

        service_scope.launch {
            build_timeline_internal()
        }

        Log.i(log_tag, "Glyphs deleted: ${identifiers_to_delete.length()}")
    }

    private suspend fun handle_load_action(json_object: JSONObject): Unit = withContext(Dispatchers.Default) {
        val start_time_ms:    Long      = System.currentTimeMillis()
        Log.i(log_tag, "Starting optimized load...")

        glyph_map.clear()
        precomputed_events.clear()
        timeline_built = false

        val glyphs:           JSONArray = json_object.getJSONArray("glyphs")
        val total_glyphs:     Int       = glyphs.length()
        var processed_glyphs: Int       = 0
        var unpacked_count:   Int       = 0

        for (batch_start in 0 until total_glyphs step batch_size) {
            val batch_end: Int = minOf(batch_start + batch_size, total_glyphs)

            for (index in batch_start until batch_end) {
                val glyph: JSONObject  = glyphs.getJSONObject(index)
                val identifier: String = glyph.optString("id", UUID.randomUUID().toString())

                if (glyph.has("effect") && glyph.has("effect_to_glyphs")) {
                    unpacked_count += unpack_effect_glyphs(identifier, glyph)
                }

                else {
                    glyph.put("id", identifier)
                    glyph_map[identifier] = glyph
                }

                processed_glyphs++
            }

            if (batch_start % (batch_size * 5) == 0) {
                yield()
                Log.d(log_tag, "Processed $processed_glyphs/$total_glyphs glyphs")
            }
        }

        build_timeline_internal()

        val load_time_ms: Long = System.currentTimeMillis() - start_time_ms
        Log.i(log_tag, "Optimized load completed in ${load_time_ms}ms. Total: ${glyph_map.size}, Unpacked: $unpacked_count, Events: ${precomputed_events.size}")
    }

    private suspend fun handle_update_action(json_object: JSONObject): Unit = withContext(Dispatchers.Default) {
        val glyphs: JSONObject         = json_object.getJSONObject("glyphs")
        val keys: Iterator<String>     = glyphs.keys()
        var updated_count: Int         = 0
        var unpacked_count: Int        = 0

        while (keys.hasNext()) {
            val identifier: String     = keys.next()
            val glyph: JSONObject      = glyphs.getJSONObject(identifier)

            remove_effect_glyphs(identifier)
            glyph_map.remove(identifier)

            if (glyph.has("effect") && glyph.has("effect_to_glyphs")) {
                unpacked_count += unpack_effect_glyphs(identifier, glyph)
            }

            else {
                glyph.put("id", identifier)
                glyph_map[identifier] = glyph
                updated_count++
            }
        }

        build_timeline_internal()
        Log.i(log_tag, "Update completed. Updated: $updated_count, Unpacked: $unpacked_count")
    }

    private fun handle_pulse_action(json_object: JSONObject): Unit {
        val track_name: String = json_object.optString("track", null) ?: run {
            Log.w(log_tag, "pulse: missing track")
            return
        }

        val channels: IntArray = resolve_track_channels(track_name) ?: run {
            Log.w(log_tag, "pulse: unknown track '$track_name'")
            return
        }

        service_scope.launch {
            val duration_ms: Long = 200L
            val steps: Int        = (duration_ms / animation_step_ms).toInt()

            for (step in 0 .. steps) {
                val progress: Double   = step.toDouble() / steps
                val eased: Double      = 1.0 - (1.0 - progress).pow(2)
                val brightness: Int    = convert_brightness(100.0 * (1.0 - eased))

                withContext(Dispatchers.Main) {
                    update_glyph_frame(channels.associate { channel: Int -> channel to brightness })
                }

                delay(animation_step_ms)
            }

            withContext(Dispatchers.Main) {
                update_glyph_frame(channels.associate { channel: Int -> channel to 0 })
            }
        }
    }

    // Timeline Engine Section
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
        val prefix: String                      = "$identifier@"
        val keys_to_remove: MutableList<String> = mutableListOf<String>()

        for (key in glyph_map.keys) {
            if (key.startsWith(prefix)) {
                keys_to_remove.add(key)
            }
        }

        for (key in keys_to_remove) {
            glyph_map.remove(key)
        }

        return keys_to_remove.size
    }

    private suspend fun build_timeline_internal(): Unit {
        precomputed_events.clear()

        val event_changes: SortedMap<Long, MutableList<Pair<IntArray, Int>>> = sortedMapOf<Long, MutableList<Pair<IntArray, Int>>>()
        maximum_timeline_ms = 0L

        for ((_, glyph) in glyph_map.entries) {
            process_glyph_events(glyph, event_changes)
        }

        compile_events_into(event_changes, precomputed_events)

        maximum_timeline_ms = precomputed_events.keys.maxOrNull() ?: 0L
        timeline_built      = true
    }

    private fun process_glyph_events(
        glyph:         JSONObject,
        event_changes: MutableMap<Long, MutableList<Pair<IntArray, Int>>>
    ): Unit {
        val glyph_start: Long      = glyph.getDouble("start").toLong()
        val glyph_duration: Long   = glyph.getLong("duration")
        val track_name: String     = glyph.getString("track")

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

        val start_brightness: Int = convert_brightness(glyph.getDouble("brightness"))
        val end_time: Long        = glyph_start + glyph_duration

        event_changes.getOrPut(glyph_start) { mutableListOf() }.add(channel_list to start_brightness)
        event_changes.getOrPut(end_time) { mutableListOf() }.add(channel_list to -start_brightness)
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

    // Playback Section
    private fun play_from_timeline(
        start_ms:     Long,
        ignore_speed: Boolean = false
    ): Unit {
        if (!timeline_built) {
            return
        }

        cancel_animation_job()
        current_timeline_position = start_ms.toDouble()

        val initial_state: MutableMap<Int, Int> = mutableMapOf<Int, Int>()

        for ((time, event_array) in precomputed_events) {
            if (time > start_ms) {
                continue
            }

            for (index in event_array.indices step 2) {
                initial_state[event_array[index]] = event_array[index + 1]
            }
        }

        animation_job = service_scope.launch {
            var last_real_time: Long                       = System.currentTimeMillis()
            val sorted_timeline: SortedMap<Long, IntArray> = precomputed_events.toSortedMap()

            val effective_speed: () -> Double = {
                if (ignore_speed) 1.0 else playback_speed
            }

            while (isActive && current_timeline_position <= maximum_timeline_ms) {
                val current_time: Long = System.currentTimeMillis()
                val delta_time: Long   = current_time - last_real_time

                last_real_time         = current_time
                current_timeline_position += delta_time * effective_speed()

                val previous_position: Long = (current_timeline_position - (delta_time * effective_speed())).toLong()
                val events_in_window: SortedMap<Long, IntArray> = sorted_timeline.subMap(
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

                    withContext(Dispatchers.Main) {
                        update_glyph_frame(current_frame)
                    }
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

        service_scope.launch(Dispatchers.Main) {
            turn_off_all_channels()
        }
    }

    fun play_json_glyph_map(
        json_object:  JSONObject,
        ignore_speed: Boolean = false
    ): Unit {
        service_scope.launch {
            data_lock.withLock {
                glyph_map.clear()
                precomputed_events.clear()
                timeline_built = false

                val glyphs_array: JSONArray = extract_glyphs_array(json_object) ?: return@withLock

                for (index in 0 until glyphs_array.length()) {
                    val glyph: JSONObject  = glyphs_array.getJSONObject(index)
                    val identifier: String = glyph.optString("id", UUID.randomUUID().toString())

                    glyph.put("id", identifier)
                    glyph_map[identifier] = glyph
                }

                build_timeline_internal()
            }

            play_from_timeline(0L, ignore_speed = ignore_speed)
        }
    }

    // Frame Rendering Section
    private fun resolve_track_channels(track_name: String): IntArray? {
        ensure_device_registered_immediately()

        val track_map: Map<String, IntArray> = current_track_map ?: return null

        if (track_name.equals("A", ignoreCase = true)) {
            track_map["A"]?.let { channels: IntArray -> return channels }

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
            return IntArray(channels_array?.length() ?: 0) { index: Int -> channels_array?.getInt(index) ?: 0 }
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

            else {
                Log.w(log_tag, "Segment index $segment_index out of bounds for track '$track_name'")
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

            val frame: GlyphFrame = builder.build()
            glyph_manager?.toggle(frame)
        }

        catch (exception: Exception) {
            Log.e(log_tag, "Fallback frame toggle failed for $device_identifier: ${exception.message}")
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
                Log.e(log_tag, "Failed to call setFrameColors for $device_identifier: ${exception.message}")
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
                Log.i(log_tag, "All $device_identifier glyphs turned off via setFrameColors")
            }

            catch (exception: Exception) {
                Log.w(log_tag, "setFrameColors turnOff failed, attempting fallback: ${exception.message}")
                toggle_glyph_frame_fallback(device_identifier, emptyMap())
            }

            return
        }

        toggle_glyph_frame_fallback(device_identifier, emptyMap())
        Log.i(log_tag, "All glyphs turned off")
    }

    // Mathematics Section
    private fun apply_easing(
        easing:   String,
        progress: Double
    ): Double = when (easing) {
        "ease_in" -> progress * progress

        "ease_out" -> 1.0 - (1.0 - progress).pow(2)

        "ease_in_out" -> if (progress < 0.5) 2.0 * progress * progress else 1.0 - (-2.0 * progress + 2.0).pow(2) / 2.0

        "ease_out_cubic" -> 1.0 - (1.0 - progress).pow(3)

        else -> progress
    }

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

        val segment_progress: Double                 = (progress - previous_progress) / (next_progress - previous_progress)
        val eased_progress: Double                   = apply_easing(easing, segment_progress)

        return previous_brightness + (next_brightness - previous_brightness) * eased_progress
    }

    private fun convert_brightness(brightness: Double): Int {
        return ((brightness.coerceIn(0.0, 100.0) / 100.0) * maximum_brightness).roundToInt()
    }

    // Resource Cleanup Section
    private fun cleanup(): Unit {
        stop_all()

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

        service_scope.cancel()

        connection_sound?.release()
        connection_sound = null

        disconnection_sound?.release()
        disconnection_sound = null

        Log.i(log_tag, "Resources cleaned up")
    }
}