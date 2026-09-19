package com.glyph.receiver

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.WindowInsetsController
import android.widget.TextView
import android.widget.LinearLayout

class MainActivity : Activity() {

    // Lifecycle Section
    override fun onCreate(saved_instance_state: Bundle?): Unit {
        super.onCreate(saved_instance_state)

        start_receiver_service()

        val primary_typeface:   Typeface = load_typeface_safely("fonts/ndot.otf", Typeface.MONOSPACE)
        val secondary_typeface: Typeface = load_typeface_safely("fonts/ntype.otf", Typeface.DEFAULT)

        val root_layout: LinearLayout = create_root_layout()

        root_layout.setOnApplyWindowInsetsListener { view: View, insets: WindowInsets ->
            val top_inset: Int = calculate_top_inset(insets)

            view.setPadding(
                density_pixels(28),
                top_inset + density_pixels(24),
                density_pixels(28),
                density_pixels(32)
            )

            insets
        }

        val badge_layout: LinearLayout = create_badge_layout(density_pixels(28))

        val indicator_dot: View = create_indicator_dot(
            color_value         = Color.parseColor("#D71921"),
            size_pixels         = density_pixels(8),
            right_margin_pixels = density_pixels(10)
        )

        val status_text_view: TextView = create_text_view(
            text_content         = "RECEIVER ACTIVE",
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

        val divider_view: View = create_divider_view(
            color_value          = Color.parseColor("#222222"),
            height_pixels        = density_pixels(1),
            bottom_margin_pixels = density_pixels(28)
        )

        val instructions_content: String = """
            1. Plug your phone into your PC with a USB cable.
            2. Make sure "USB debugging" is turned on.
            3. Fire up Cassette on your computer - it'll connect automatically.

            You can minimize the app: Receiver will keep running in the background.
        """.trimIndent()

        val instruction_text_view: TextView = create_text_view(
            text_content        = instructions_content,
            color_value         = Color.parseColor("#AAAAAA"),
            size_sp             = 14.0f,
            font_typeface       = secondary_typeface,
            line_spacing_pixels = density_pixels(6).toFloat()
        )

        root_layout.addView(badge_layout)
        root_layout.addView(title_text_view)
        root_layout.addView(divider_view)
        root_layout.addView(instruction_text_view)

        setContentView(root_layout)

        configure_window_appearance(root_layout)
    }

    // Window Section
    private fun configure_window_appearance(target_view: View): Unit {
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)

        window.statusBarColor     = Color.BLACK
        window.navigationBarColor = Color.BLACK

        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)

        target_view.post {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val insets_controller: WindowInsetsController = target_view.windowInsetsController ?: window.insetsController ?: return@post

                insets_controller.hide(WindowInsets.Type.statusBars())
                insets_controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
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

            setPadding(
                density_pixels(28),
                density_pixels(48),
                density_pixels(28),
                density_pixels(32)
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