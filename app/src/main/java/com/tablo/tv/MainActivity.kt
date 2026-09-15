package com.tablo.tv

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val channels = mutableListOf<Channel>()
    private val slots = mutableListOf<StreamSlot>()
    private var devices = emptyList<DiscoveredTablo>()
    private var selectedDevice: DiscoveredTablo? = null
    private var root: LinearLayout? = null
    private var status: TextView? = null
    private var channelGrid: GridLayout? = null
    private var streamGrid: GridLayout? = null
    private var maxStreams = 1

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.navigationBarColor = Color.rgb(16, 19, 26)
        showLogin()
    }

    private fun showLogin() {
        val page = page()
        val card = column().apply { gravity = Gravity.CENTER_HORIZONTAL }
        page.addView(card, LinearLayout.LayoutParams(-1, -2))
        card.addView(text("TABLO", 32, Color.WHITE).apply { gravity = Gravity.CENTER }, margins(0, 20, 0, 8))
        card.addView(text("Sign in with your Tablo account", 16, muted()).apply { gravity = Gravity.CENTER }, margins(0, 0, 0, 24))

        val email = field("Email Address", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val password = field("Password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        card.addView(clipboardRow(email), wide())
        card.addView(clipboardRow(password), wide())

        val deviceTitle = row().apply {
            addView(text("Connected Tablo Device", 15, Color.WHITE), LinearLayout.LayoutParams(0, -2, 1f))
        }
        val refresh = button("REFRESH")
        deviceTitle.addView(refresh)
        card.addView(deviceTitle, margins(0, 18, 0, 6))
        val deviceList = column()
        card.addView(deviceList, wide())

        status = text("Searching local network for Tablo DVR…", 14, muted()).apply { gravity = Gravity.CENTER }
        card.addView(status, margins(0, 12, 0, 10))
        val signIn = button("SIGN IN & OPEN GUIDE").apply {
            setBackgroundResource(R.drawable.button_accent)
            setOnClickListener {
                val device = selectedDevice
                if (email.text.isNullOrBlank() || password.text.isNullOrBlank()) {
                    status?.text = "Enter your Tablo email and password."
                } else if (device == null) {
                    status?.text = "Select a Tablo device first."
                } else {
                    status?.text = "Connecting to ${device.name}…"
                    loadChannels(device)
                }
            }
        }
        card.addView(signIn, margins(0, 4, 0, 0))
        email.requestFocus()

        fun renderDevices() {
            deviceList.removeAllViews()
            if (devices.isEmpty()) {
                deviceList.addView(text("No Tablo detected. Press REFRESH to search again.", 14, muted()), wide())
                return
            }
            devices.forEach { device ->
                val item = button("${device.name}\n${device.ipAddress}  •  ${device.model}  •  ${device.tunerCount} tuners")
                item.gravity = Gravity.CENTER_VERTICAL or Gravity.START
                item.setOnClickListener {
                    selectedDevice = device
                    status?.text = "${device.name} selected."
                    renderDevices()
                }
                if (device == selectedDevice) item.setBackgroundResource(R.drawable.button_accent)
                deviceList.addView(item, margins(0, 4, 0, 4))
            }
        }
        fun discover() {
            refresh.isEnabled = false
            status?.text = "Searching local network for Tablo DVR…"
            executor.execute {
                val found = TabloDiscovery.find()
                runOnUiThread {
                    devices = found
                    if (selectedDevice == null) selectedDevice = found.firstOrNull()
                    refresh.isEnabled = true
                    status?.text = if (found.isEmpty()) "No Tablo found. Confirm the TV and Tablo share a network."
                    else "${found.size} Tablo device${if (found.size == 1) "" else "s"} found."
                    renderDevices()
                }
            }
        }
        refresh.setOnClickListener { discover() }
        renderDevices()
        discover()
    }

    private fun loadChannels(device: DiscoveredTablo) {
        executor.execute {
            try {
                val paths = JSONArray(request(device.baseUrl, "/guide/channels"))
                val loaded = (0 until paths.length()).mapNotNull { index ->
                    val path = paths.optString(index)
                    if (path.isBlank()) null else Channel.fromTablo(path, JSONObject(request(device.baseUrl, path)))
                }
                channels.clear()
                channels.addAll(loaded)
                runOnUiThread { showDashboard(device) }
            } catch (error: Exception) {
                runOnUiThread { status?.text = "Unable to load channels: ${error.message ?: "Tablo request failed"}" }
            }
        }
    }

    private fun showDashboard(device: DiscoveredTablo) {
        val page = page()
        val header = row()
        header.addView(text("TABLO", 25, Color.WHITE), margins(0, 0, 22, 0))
        header.addView(button("Live TV").apply { setTextColor(accent()) }, margins(0, 0, 4, 0))
        header.addView(button("Guide").apply { setOnClickListener { status?.text = "TV GUIDE is available through the live channel list." } }, margins(0, 0, 4, 0))
        header.addView(button("Library").apply { setOnClickListener { status?.text = "Library is loading from Tablo." } }, LinearLayout.LayoutParams(0, -2, 1f))
        val logout = button("LOG OUT").apply { setOnClickListener { stopAll(); showLogin() } }
        header.addView(logout)
        page.addView(header, wide())
        status = text("Connected to ${device.name}. Select a channel to start watching.", 14, muted())
        page.addView(status, margins(24, 8, 24, 6))
        page.addView(text("ON AIR NOW", 28, Color.WHITE), margins(24, 8, 24, 0))
        page.addView(text("Browse your local guide and start watching instantly", 14, muted()), margins(24, 0, 24, 10))
        val streamControls = row()
        streamControls.addView(text("MULTISTREAM", 13, muted()), LinearLayout.LayoutParams(0, -2, 1f))
        listOf(1, 2, 3, 4).forEach { count ->
            streamControls.addView(button("$count").apply { setOnClickListener { maxStreams = count; renderStreams() } }, margins(2, 0, 2, 0))
        }
        page.addView(streamControls, margins(24, 0, 24, 4))
        streamGrid = GridLayout(this).apply { columnCount = 2; rowCount = 2 }
        page.addView(streamGrid, LinearLayout.LayoutParams(-1, 0, 1f))
        page.addView(text("LIVE GUIDE", 18, Color.WHITE), margins(24, 14, 24, 6))
        val guide = ScrollView(this)
        channelGrid = GridLayout(this).apply { columnCount = 4 }
        guide.addView(channelGrid)
        page.addView(guide, LinearLayout.LayoutParams(-1, 0, 1f))
        renderStreams()
        renderChannels()
    }

    private fun renderChannels() {
        val grid = channelGrid ?: return
        grid.removeAllViews()
        channels.forEach { channel ->
            grid.addView(button("${channel.displayName}\n${channel.callSign}").apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setOnClickListener { addStream(channel) }
            }, GridLayout.LayoutParams().apply {
                width = 0; height = dp(66); columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(6), dp(4), dp(6), dp(4))
            })
        }
    }

    private fun addStream(channel: Channel) {
        if (slots.any { it.channel.path == channel.path }) return
        if (slots.size >= maxStreams) {
            status?.text = "The $maxStreams-stream wall is full."
            return
        }
        val slot = StreamSlot(channel)
        slots += slot
        renderStreams()
        executor.execute {
            try {
                val device = selectedDevice ?: error("No Tablo selected")
                val response = JSONObject(request(device.baseUrl, "${channel.path}/watch", "POST", "{}"))
                val url = response.getString("playlist_url").let { if (it.startsWith("http")) it else device.baseUrl + it }
                slot.token = response.optString("token")
                runOnUiThread { slot.player = ExoPlayer.Builder(this).build().apply { setMediaItem(MediaItem.fromUri(url)); prepare(); play() }; renderStreams() }
            } catch (error: Exception) {
                runOnUiThread { slots.remove(slot); renderStreams(); status?.text = "Unable to start stream: ${error.message}" }
            }
        }
    }

    private fun renderStreams() {
        val grid = streamGrid ?: return
        grid.removeAllViews()
        repeat(maxStreams) { index ->
            val slot = slots.getOrNull(index)
            val tile = column().apply { setBackgroundColor(Color.BLACK) }
            if (slot?.player != null) tile.addView(PlayerView(this).apply { player = slot.player }, LinearLayout.LayoutParams(-1, 0, 1f))
            else tile.addView(text("Choose a channel\nfor stream ${index + 1}", 16, muted()).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, 0, 1f))
            if (slot != null) tile.addView(text("${slot.channel.major}.${slot.channel.minor}  ${slot.channel.displayName}", 13, Color.WHITE))
            tile.setOnClickListener { if (slot != null) { stopStream(slot); slots.remove(slot); renderStreams() } }
            grid.addView(tile, GridLayout.LayoutParams().apply {
                width = 0; height = 0; columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f); rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            })
        }
    }

    private fun stopAll() = slots.toList().forEach { stopStream(it) }.also { slots.clear() }
    private fun stopStream(slot: StreamSlot) {
        slot.player?.release()
        val token = slot.token ?: return
        val device = selectedDevice ?: return
        executor.execute { runCatching { request(device.baseUrl, "/watch/$token", "DELETE") } }
    }

    private fun clipboardRow(editor: EditText): LinearLayout = row().apply {
        addView(editor, LinearLayout.LayoutParams(0, dp(54), 1f))
        addView(button("PASTE").apply { setOnClickListener { paste(editor) } })
        addView(button("COPY").apply { setOnClickListener { copy(editor) } })
    }
    private fun paste(editor: EditText) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.let { editor.setText(it); editor.setSelection(editor.length()) }
    }
    private fun copy(editor: EditText) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Tablo", editor.text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }
    private fun request(base: String, path: String, method: String = "GET", body: String? = null): String {
        val connection = (URL(base + if (path.startsWith("/")) path else "/$path").openConnection() as HttpURLConnection)
        connection.requestMethod = method; connection.connectTimeout = 10_000; connection.readTimeout = 30_000
        if (body != null) { connection.doOutput = true; connection.outputStream.use { it.write(body.toByteArray()) } }
        if (connection.responseCode !in 200..299) error("Tablo returned HTTP ${connection.responseCode}")
        return connection.inputStream.bufferedReader().use { it.readText() }
    }
    private fun page() = column().apply { setBackgroundColor(Color.rgb(16, 19, 26)); setPadding(dp(28), dp(20), dp(28), dp(18)); setContentView(this); root = this }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun text(value: String, size: Int, color: Int) = TextView(this).apply { text = value; textSize = size.toFloat(); setTextColor(color) }
    private fun field(hint: String, type: Int) = EditText(this).apply { this.hint = hint; setHintTextColor(muted()); setTextColor(Color.WHITE); textSize = 16f; inputType = type; setSingleLine(); setBackgroundResource(R.drawable.field_surface); setPadding(dp(16), 0, dp(16), 0) }
    private fun button(label: String) = Button(this).apply { text = label; setTextColor(Color.WHITE); textSize = 13f; isAllCaps = false; isFocusable = true; setBackgroundResource(R.drawable.button_surface) }
    private fun wide() = LinearLayout.LayoutParams(dp(600), dp(54))
    private fun margins(l: Int, t: Int, r: Int, b: Int) = LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(l), dp(t), dp(r), dp(b)) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun muted() = Color.rgb(170, 177, 196)
    private fun accent() = Color.rgb(110, 140, 255)
    override fun onDestroy() { stopAll(); executor.shutdownNow(); super.onDestroy() }

    private data class Channel(val path: String, val displayName: String, val callSign: String, val major: Int, val minor: Int) {
        companion object {
            fun fromTablo(path: String, json: JSONObject): Channel {
                val meta = json.optJSONObject("channel") ?: json
                val callSign = meta.optString("call_sign", meta.optString("network", "TABLO"))
                return Channel(path, meta.optString("display_title", meta.optString("network", callSign)), callSign, meta.optInt("major"), meta.optInt("minor"))
            }
        }
    }
    private data class StreamSlot(val channel: Channel, var token: String? = null, var player: ExoPlayer? = null)
}
