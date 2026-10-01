package com.glyph.receiver

import android.app.Activity
import android.content.Intent
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.TextView
import android.widget.LinearLayout

import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest

import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : Activity() {

    // Properties Section
    private val activity_scope: CoroutineScope = CoroutineScope(Dispatchers.Main + Job())

    private var state_collector_job:  Job?          = null
    private var current_mode:         String        = "wireless"

    private lateinit var indicator_dot:        View
    private lateinit var status_text_view:     TextView
    private lateinit var tab_wireless_button:  TextView
    private lateinit var tab_cable_button:     TextView
    private lateinit var content_container:    LinearLayout

    // Lifecycle Section
    override fun onCreate(saved_instance_state: Bundle?): Unit {
        super.onCreate(saved_instance_state)

        start_receiver_service()
        request_battery_optimization_exemption()

        val primary_typeface:   Typeface = load_typeface_safely("fonts/ndot.otf", Typeface.MONOSPACE)
        val secondary_typeface: Typeface = load_typeface_safely("fonts/ntype.otf", Typeface.DEFAULT)

        val root_layout: LinearLayout = create_root_layout()

        root_layout.setOnApplyWindowInsetsListener { view: View, insets: WindowInsets ->
            val top_inset: Int = calculate_top_inset(insets)

            view.setPadding(
                density_pixels(24),
                top_inset + density_pixels(20),
                density_pixels(24),
                density_pixels(28)
            )

            insets
        }

        val badge_layout: LinearLayout = create_badge_layout(density_pixels(24))

        indicator_dot = create_indicator_dot(
            color_value         = Color.parseColor("#666666"),
            size_pixels         = density_pixels(8),
            right_margin_pixels = density_pixels(10)
        )

        status_text_view = create_text_view(
            text_content         = "SEARCHING FOR CASSETTE",
            color_value          = Color.parseColor("#888888"),
            size_sp              = 12.0f,
            font_typeface        = primary_typeface,
            letter_spacing_value = 0.08f,
            width_specification  = ViewGroup.LayoutParams.WRAP_CONTENT
        )

        badge_layout.addView(indicator_dot)
        badge_layout.addView(status_text_view)

        val title_text_view: TextView = create_text_view(
            text_content         = "Cassette\nReceiver",
            color_value          = Color.WHITE,
            size_sp              = 36.0f,
            font_typeface        = primary_typeface,
            letter_spacing_value = 0.02f,
            bottom_margin_pixels = density_pixels(24)
        )

        val tabs_layout: LinearLayout = LinearLayout(this).apply {
            orientation  = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = density_pixels(24)
            }
        }

        tab_wireless_button = create_tab_button("WIRELESS", primary_typeface) {
            switch_mode("wireless", primary_typeface, secondary_typeface)
        }

        tab_cable_button = create_tab_button("CABLE (USB)", primary_typeface) {
            switch_mode("cable", primary_typeface, secondary_typeface)
        }

        val tabs_spacer: View = View(this).apply {
            layoutParams = ViewGroup.LayoutParams(density_pixels(12), 1)
        }

        tabs_layout.addView(tab_wireless_button)
        tabs_layout.addView(tabs_spacer)
        tabs_layout.addView(tab_cable_button)

        val divider_view: View = create_divider_view(
            color_value          = Color.parseColor("#222222"),
            height_pixels        = density_pixels(1),
            bottom_margin_pixels = density_pixels(24)
        )

        content_container = LinearLayout(this).apply {
            orientation  = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        root_layout.addView(badge_layout)
        root_layout.addView(title_text_view)
        root_layout.addView(tabs_layout)
        root_layout.addView(divider_view)
        root_layout.addView(content_container)

        setContentView(root_layout)

        configure_window_appearance()

        val preferences: SharedPreferences = getSharedPreferences("receiver_preferences", Context.MODE_PRIVATE)
        current_mode                       = preferences.getString("mode", "wireless") ?: "wireless"

        switch_mode(current_mode, primary_typeface, secondary_typeface)

        observe_service_state()
    }

    override fun onDestroy(): Unit {
        state_collector_job?.cancel()
        state_collector_job = null

        super.onDestroy()
    }

    // Battery Optimization Section
    private fun request_battery_optimization_exemption(): Unit {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return
        }

        val power_manager: PowerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

        if (power_manager.isIgnoringBatteryOptimizations(packageName)) {
            return
        }

        try {
            val intent: Intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }

            startActivity(intent)
        }

        catch (exception: Exception) {
            val fallback_intent: Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

            startActivity(fallback_intent)
        }
    }

    // State Observation Section
    private fun observe_service_state(): Unit {
        state_collector_job = activity_scope.launch {
            MainService.connection_state.collectLatest { status: MainService.ConnectionStatus ->
                val endpoint: String? = MainService.client_endpoint.value

                if (status == MainService.ConnectionStatus.CONNECTED) {
                    val dot_background: GradientDrawable? = indicator_dot.background as? GradientDrawable
                    dot_background?.setColor(Color.parseColor("#00FF66"))

                    status_text_view.setTextColor(Color.parseColor("#00FF66"))

                    if (endpoint != null) {
                        status_text_view.text = "CONNECTED ($endpoint)"
                    }

                    else {
                        status_text_view.text = "CONNECTED TO CASSETTE"
                    }
                }

                else {
                    val dot_background: GradientDrawable? = indicator_dot.background as? GradientDrawable
                    dot_background?.setColor(Color.parseColor("#666666"))

                    status_text_view.setTextColor(Color.parseColor("#888888"))
                    status_text_view.text = "WAITING FOR CASSETTE"
                }
            }
        }
    }

    // Mode Switcher Section
    private fun switch_mode(
        mode:               String,
        primary_typeface:   Typeface,
        secondary_typeface: Typeface
    ): Unit {
        current_mode = mode

        val preferences: SharedPreferences = getSharedPreferences("receiver_preferences", Context.MODE_PRIVATE)
        preferences.edit().putString("mode", mode).apply()

        val active_text_color:   Int = Color.WHITE
        val inactive_text_color: Int = Color.parseColor("#444444")
        val active_background:   Int = Color.parseColor("#222222")
        val inactive_background: Int = Color.TRANSPARENT

        if (mode == "wireless") {
            tab_wireless_button.setTextColor(active_text_color)
            tab_wireless_button.setBackgroundColor(active_background)

            tab_cable_button.setTextColor(inactive_text_color)
            tab_cable_button.setBackgroundColor(inactive_background)

            render_wireless_view(primary_typeface, secondary_typeface)
        }

        else {
            tab_cable_button.setTextColor(active_text_color)
            tab_cable_button.setBackgroundColor(active_background)

            tab_wireless_button.setTextColor(inactive_text_color)
            tab_wireless_button.setBackgroundColor(inactive_background)

            render_cable_view(secondary_typeface)
        }
    }

    private fun render_wireless_view(
        primary_typeface:   Typeface,
        secondary_typeface: Typeface
    ): Unit {
        content_container.removeAllViews()

        val ip_address_value: String = resolve_wifi_ip_address() ?: "Wi-Fi not connected"

        val ip_card_layout: LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            setBackgroundColor(Color.parseColor("#111111"))

            setPadding(
                density_pixels(18),
                density_pixels(16),
                density_pixels(18),
                density_pixels(16)
            )

            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = density_pixels(20)
            }
        }

        val ip_label_view: TextView = create_text_view(
            text_content         = "LOCAL IP ADDRESS",
            color_value          = Color.parseColor("#666666"),
            size_sp              = 11.0f,
            font_typeface        = primary_typeface,
            letter_spacing_value = 0.06f,
            bottom_margin_pixels = density_pixels(4)
        )

        val ip_value_view: TextView = create_text_view(
            text_content         = ip_address_value,
            color_value          = Color.WHITE,
            size_sp              = 20.0f,
            font_typeface        = primary_typeface,
            letter_spacing_value = 0.04f
        )

        ip_card_layout.addView(ip_label_view)
        ip_card_layout.addView(ip_value_view)

        val wireless_instructions: String = """
            - Keep phone and PC on the same Wi-Fi network.
            - Cassette on your PC will automatically detect this device.
            - You can lock your phone.
            - If your Wi-Fi is slow, use cable instead.
        """.trimIndent()

        val instructions_view: TextView = create_text_view(
            text_content        = wireless_instructions,
            color_value         = Color.parseColor("#AAAAAA"),
            size_sp             = 14.0f,
            font_typeface       = secondary_typeface,
            line_spacing_pixels = density_pixels(6).toFloat()
        )

        content_container.addView(ip_card_layout)
        content_container.addView(instructions_view)
    }

    private fun render_cable_view(secondary_typeface: Typeface): Unit {
        content_container.removeAllViews()

        val cable_instructions: String = """
            1. Go to Settings -> About phone.
            2. Tap "Build number" 7 times to enable Developer options.
            3. Open System -> Developer options and enable "USB debugging".
            4. Connect your phone to your PC using a USB cable.
            5. In the pop-up on your phone, enable "Always allow from this computer" and then click "Allow".
            6. Cassette will connect via ADB automatically.
        """.trimIndent()

        val guide_view: TextView = create_text_view(
            text_content        = cable_instructions,
            color_value         = Color.parseColor("#AAAAAA"),
            size_sp             = 14.0f,
            font_typeface       = secondary_typeface,
            line_spacing_pixels = density_pixels(6).toFloat()
        )

        content_container.addView(guide_view)
    }

    // Network Information Section
    private fun resolve_wifi_ip_address(): String? {
        val connectivity_manager: ConnectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active_network                            = connectivity_manager.activeNetwork ?: return null
        val capabilities                              = connectivity_manager.getNetworkCapabilities(active_network) ?: return null

        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return null
        }

        val interfaces = NetworkInterface.getNetworkInterfaces()

        for (network_interface in interfaces) {
            val interface_name: String = network_interface.name

            if (!interface_name.contains("wlan") && !interface_name.contains("ap")) {
                continue
            }

            for (address in network_interface.inetAddresses) {
                if (!address.isLoopbackAddress && address is Inet4Address) {
                    return address.hostAddress
                }
            }
        }

        return null
    }

    // Window Section
    private fun configure_window_appearance(): Unit {
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)

        window.statusBarColor     = Color.BLACK
        window.navigationBarColor = Color.BLACK

        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    // Service Section
    private fun start_receiver_service(): Unit {
        val service_intent: Intent = Intent(this, MainService::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(service_intent)

            return
        }

        startService(service_intent)
    }

    // Window Inset Section
    private fun calculate_top_inset(insets: WindowInsets): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return insets.getInsets(WindowInsets.Type.statusBars()).top
        }

        @Suppress("DEPRECATION")
        return insets.systemWindowInsetTop
    }

    // Dimension Section
    private fun density_pixels(value: Int): Int {
        val density: Float = resources.displayMetrics.density

        return (value * density).toInt()
    }

    // Font Loader Section
    private fun load_typeface_safely(
        asset_file_path:   String,
        fallback_typeface: Typeface
    ): Typeface {
        return try {
            Typeface.createFromAsset(assets, asset_file_path)
        }

        catch (exception: Exception) {
            fallback_typeface
        }
    }

    // View Factory Section
    private fun create_root_layout(): LinearLayout {
        val root_layout: LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.START

            setBackgroundColor(Color.BLACK)

            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        return root_layout
    }

    private fun create_badge_layout(bottom_margin_pixels: Int): LinearLayout {
        val badge_layout: LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL

            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = bottom_margin_pixels
            }
        }

        return badge_layout
    }

    private fun create_tab_button(
        title_text:    String,
        font_typeface: Typeface,
        click_action:  () -> Unit
    ): TextView {
        val tab_view: TextView = TextView(this).apply {
            text        = title_text
            typeface    = font_typeface
            isClickable = true
            isFocusable = true

            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.0f)

            setPadding(
                density_pixels(16),
                density_pixels(10),
                density_pixels(16),
                density_pixels(10)
            )

            setOnClickListener {
                click_action()
            }
        }

        return tab_view
    }

    private fun create_indicator_dot(
        color_value:         Int,
        size_pixels:         Int,
        right_margin_pixels: Int
    ): View {
        val indicator_dot: View = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL

                setColor(color_value)
            }

            layoutParams = LinearLayout.LayoutParams(
                size_pixels,
                size_pixels
            ).apply {
                rightMargin = right_margin_pixels
            }
        }

        return indicator_dot
    }

    private fun create_divider_view(
        color_value:          Int,
        height_pixels:        Int,
        bottom_margin_pixels: Int
    ): View {
        val divider_view: View = View(this).apply {
            setBackgroundColor(color_value)

            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                height_pixels
            ).apply {
                bottomMargin = bottom_margin_pixels
            }
        }

        return divider_view
    }

    private fun create_text_view(
        text_content:         CharSequence,
        color_value:          Int,
        size_sp:              Float,
        font_typeface:        Typeface,
        letter_spacing_value: Float = 0.0f,
        bottom_margin_pixels: Int   = 0,
        line_spacing_pixels:  Float = 0.0f,
        width_specification:  Int   = ViewGroup.LayoutParams.MATCH_PARENT
    ): TextView {
        val text_view: TextView = TextView(this).apply {
            text          = text_content
            typeface      = font_typeface
            letterSpacing = letter_spacing_value
            gravity       = Gravity.START
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START

            setTextColor(color_value)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size_sp)

            if (line_spacing_pixels > 0.0f) {
                setLineSpacing(line_spacing_pixels, 1.0f)
            }
        }

        val layout_parameters: LinearLayout.LayoutParams = LinearLayout.LayoutParams(
            width_specification,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = bottom_margin_pixels
        }

        text_view.layoutParams = layout_parameters

        return text_view
    }
}