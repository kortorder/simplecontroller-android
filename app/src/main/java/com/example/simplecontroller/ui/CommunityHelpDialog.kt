package com.example.simplecontroller.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.example.simplecontroller.R
import kotlin.math.min

/** Offline, replayable help. No controller or connection settings are written here. */
internal object CommunityHelp {
    const val PREFERENCES = "community_help"
    const val STARTED = "tourStarted"
    const val COMPLETED = "tourCompleted"
    const val PAGE = "tourPage"
    const val STARTER_PENDING = "starterPending"
    private const val PROJECT = "https://github.com/kortorder/simplecontroller-android"
    val links = linkedMapOf(
        R.string.help_faq to "$PROJECT/blob/main/FAQ.txt",
        R.string.help_receiver to "$PROJECT/tree/main/receiver",
        R.string.help_wifi_bridge to "https://github.com/kortorder/simplecontroller-pico-wifi-bridge",
        R.string.help_chair_bridge to "https://github.com/kortorder/omni2-pico-xbox-joystick",
        R.string.help_videos to "$PROJECT/releases",
        R.string.help_project to PROJECT,
        R.string.help_contact to "$PROJECT/issues"
    )

    fun showTour(context: Context, firstRun: Boolean = false, finished: () -> Unit = {}): AlertDialog {
        val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        var page = if (firstRun) HelpOnboardingPolicy.page(prefs.getInt(PAGE, 0)) else 0
        if (firstRun) prefs.edit().putBoolean(STARTED, true).apply()
        val titles = context.resources.getStringArray(R.array.help_tour_titles)
        val bodies = context.resources.getStringArray(R.array.help_tour_bodies)
        val dp = { value: Int -> AppUiStyle.dp(context, value) }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(10))
        }
        val count = text(context, 14f).apply { id = R.id.helpPageCount }
        val title = text(context, 23f).apply { id = R.id.helpPageTitle; setPadding(0, dp(8), 0, dp(8)) }
        val picture = HelpIllustration(context)
        val body = text(context, 17f).apply { id = R.id.helpPageBody; setPadding(0, dp(10), 0, dp(12)) }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(picture, LinearLayout.LayoutParams(-1, dp(180)))
            addView(body)
        }
        val scroll = ScrollView(context).apply { id = R.id.helpScroll; isFillViewport = false; addView(content) }
        val row = LinearLayout(context).apply { gravity = Gravity.CENTER }
        val back = button(context, R.string.help_back).apply { id = R.id.helpBack }
        val next = button(context, R.string.help_next).apply { id = R.id.helpNext }
        val skip = button(context, R.string.help_skip).apply { id = R.id.helpSkip }
        row.orientation = LinearLayout.VERTICAL
        row.addView(next, LinearLayout.LayoutParams(-1, -2))
        row.addView(back, LinearLayout.LayoutParams(-1, -2))
        column.addView(count)
        column.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        column.addView(row)
        column.addView(skip)
        val dialog = AlertDialog.Builder(context).setTitle(R.string.help_quick_tour).setView(column).create()
        var completedHere = false
        fun finish() {
            if (completedHere) return
            completedHere = true
            if (firstRun) prefs.edit().putBoolean(COMPLETED, true).putInt(PAGE, 0).apply()
            dialog.dismiss()
            finished()
        }
        fun render() {
            page = HelpOnboardingPolicy.page(page)
            count.text = context.getString(R.string.help_page_count, page + 1, titles.size)
            title.text = titles[page]
            body.text = bodies[page]
            picture.page = page
            picture.contentDescription = picture.descriptionFor(page)
            picture.invalidate()
            back.isEnabled = page > 0
            next.setText(if (page == titles.lastIndex) R.string.help_finish else R.string.help_next)
            skip.visibility = if (page == titles.lastIndex) View.GONE else View.VISIBLE
            if (firstRun) prefs.edit().putInt(PAGE, page).apply()
            scroll.scrollTo(0, 0)
            title.announceForAccessibility("${count.text}. ${title.text}")
        }
        back.setOnClickListener { page = HelpOnboardingPolicy.previous(page); render() }
        next.setOnClickListener { if (page == titles.lastIndex) finish() else { page = HelpOnboardingPolicy.next(page); render() } }
        skip.setOnClickListener { finish() }
        picture.onPageSwipe = { forward ->
            val changed = if (forward) HelpOnboardingPolicy.next(page) else HelpOnboardingPolicy.previous(page)
            if (changed != page) { page = changed; render() }
        }
        dialog.setOnCancelListener { finish() }
        dialog.show()
        PropertySheetStyle(context).dialog(dialog)
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        render()
        return dialog
    }

    fun showSupport(context: Context, connectionSettings: () -> Unit): AlertDialog {
        val dp = { value: Int -> AppUiStyle.dp(context, value) }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val tabs = LinearLayout(context).apply { id = R.id.helpSupportTabs }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(8), dp(4), dp(16))
        }
        val scroll = ScrollView(context).apply { id = R.id.helpScroll; addView(content) }
        column.addView(tabs)
        column.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val dialog = AlertDialog.Builder(context).setTitle(R.string.help_title).setView(column)
            .setNegativeButton(R.string.toolbar_done, null).create()
        val tabButtons = listOf(
            button(context, R.string.help_tab_pc).apply { id = R.id.helpTabPC },
            button(context, R.string.help_tab_console).apply { id = R.id.helpTabConsole },
            button(context, R.string.help_tab_learn).apply { id = R.id.helpTabLearn }
        )
        fun link(label: Int) {
            content.addView(button(context, label).apply { setOnClickListener { open(context, links.getValue(label)) } })
        }
        fun display(tab: Int) {
            tabButtons.forEachIndexed { index, view -> view.isSelected = index == tab; view.isEnabled = index != tab }
            content.removeAllViews()
            val heading = when (tab) { 0 -> R.string.help_pc_heading; 1 -> R.string.help_console_heading; else -> R.string.help_learn_heading }
            val body = when (tab) { 0 -> R.string.help_pc_body; 1 -> R.string.help_console_body; else -> R.string.help_learn_body }
            content.addView(text(context, 22f).apply { setText(heading); setPadding(0, 0, 0, dp(10)) })
            content.addView(HelpIllustration(context).apply { page = if (tab == 0) 6 else if (tab == 1) 7 else 0; contentDescription = descriptionFor(page) }, LinearLayout.LayoutParams(-1, dp(160)))
            content.addView(text(context, 17f).apply { setText(body); setPadding(0, dp(8), 0, dp(12)) })
            if (tab == 0) {
                link(R.string.help_receiver)
                content.addView(button(context, R.string.help_open_settings).apply { setOnClickListener { dialog.dismiss(); connectionSettings() } })
            } else if (tab == 1) {
                link(R.string.help_wifi_bridge); link(R.string.help_chair_bridge)
            } else {
                content.addView(button(context, R.string.help_quick_tour).apply { setOnClickListener { dialog.dismiss(); showTour(context) } })
                link(R.string.help_videos)
            }
            link(R.string.help_faq)
            link(R.string.help_project)
            link(R.string.help_contact)
            content.addView(text(context, 14f).apply { setText(R.string.help_external); setPadding(0, dp(12), 0, 0) })
            scroll.scrollTo(0, 0)
        }
        tabButtons.forEachIndexed { index, button ->
            tabs.addView(button, LinearLayout.LayoutParams(0, -2, 1f))
            button.setOnClickListener { display(index) }
        }
        dialog.show()
        PropertySheetStyle(context).dialog(dialog)
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        display(0)
        return dialog
    }

    private fun text(context: Context, size: Float) = TextView(context).apply {
        textSize = size
        setTextColor(ContextCompat.getColor(context, R.color.ui_text))
    }
    private fun button(context: Context, title: Int) = Button(context).apply {
        AppUiStyle.button(this)
        setText(title)
        minHeight = AppUiStyle.dp(context, 48)
        isAllCaps = false
    }
    private fun open(context: Context, url: String) {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: ActivityNotFoundException) { Toast.makeText(context, R.string.help_no_browser, Toast.LENGTH_LONG).show() }
    }
}

/** Code-native diagrams: bundled, offline, scalable and described to screen readers. */
private class HelpIllustration(context: Context) : View(context) {
    var page = 0
    var onPageSwipe: ((Boolean) -> Unit)? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var downX = 0f
    private var downY = 0f
    private val ink = Color.rgb(213, 235, 255)
    private val accent = Color.rgb(76, 208, 187)
    private val blue = Color.rgb(87, 162, 255)
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }
    fun descriptionFor(index: Int): String = when (index) {
        0 -> "Picture: a phone with buttons and two sticks."
        1 -> "Picture: Practice controls and a Stop button."
        2 -> "Picture: a stick with normal movement, run and jump zones."
        3 -> "Picture: an aiming pad and a pad that combines acceleration with steering."
        4 -> "Picture: hold, repeat and a sequence of button actions."
        5 -> "Picture: a small button moving to a comfortable position on the phone."
        6 -> "Picture: a phone connects over the home network to a Windows PC or Mac running the receiver."
        else -> "Picture: phone, Wi-Fi Pico bridge and compatible adaptive controller connect to a console."
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (onPageSwipe == null) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; return true }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                if (kotlin.math.abs(dx) > AppUiStyle.dp(context, 45) && kotlin.math.abs(dx) > kotlin.math.abs(event.y - downY)) onPageSwipe?.invoke(dx < 0)
                else performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(20, 32, 48))
        val scale = min(width / 340f, height / 180f)
        canvas.save(); canvas.translate((width - 340f * scale) / 2, (height - 180f * scale) / 2); canvas.scale(scale, scale)
        fun stroke(color: Int = ink, width: Float = 3f) { p.color = color; p.style = Paint.Style.STROKE; p.strokeWidth = width }
        fun fill(color: Int = ink) { p.color = color; p.style = Paint.Style.FILL }
        fun label(text: String, x: Float, y: Float, size: Float = 14f) { fill(); p.textSize = size; p.textAlign = Paint.Align.CENTER; canvas.drawText(text, x, y, p) }
        fun round(x: Float, y: Float, w: Float, h: Float, color: Int = blue) { stroke(color); canvas.drawRoundRect(RectF(x,y,x+w,y+h),12f,12f,p) }
        fun circle(x: Float, y: Float, r: Float, color: Int = accent) { stroke(color); canvas.drawCircle(x,y,r,p) }
        fun arrow(x1: Float, y: Float, x2: Float) { stroke(accent); canvas.drawLine(x1,y,x2,y,p); canvas.drawLine(x2-7,y-6,x2,y,p); canvas.drawLine(x2-7,y+6,x2,y,p) }
        fun phone(x: Float, y: Float, w: Float, h: Float) { round(x,y,w,h); stroke(ink,2f); canvas.drawLine(x+w*.35f,y+8,x+w*.65f,y+8,p) }
        when(page) {
            0, 5 -> {
                phone(115f,8f,110f,164f)
                circle(140f,75f,14f); circle(140f,125f,17f); circle(195f,130f,14f)
                circle(194f,64f,10f); label("A",194f,69f,12f); circle(194f,91f,10f); label("B",194f,96f,12f)
                if(page==5) { arrow(45f,70f,112f); label("Move",60f,48f); circle(45f,70f,10f) }
                else { label("Your",65f,76f,20f); label("way",65f,102f,20f); label("One",275f,76f,20f); label("finger",275f,102f,20f) }
            }
            1 -> { circle(85f,80f,37f); circle(85f,80f,15f); round(175f,42f,100f,74f,accent); label("STOP",225f,86f,21f); label("Practice",85f,145f,18f); label("Release all",225f,145f,18f) }
            2 -> { circle(105f,95f,62f,blue); circle(105f,95f,43f); circle(105f,95f,21f); stroke(accent,5f); canvas.drawLine(105f,95f,105f,35f,p); fill(accent);canvas.drawCircle(105f,35f,10f,p); label("Jump",230f,46f,20f);label("Run",230f,96f,20f);label("Move",230f,143f,20f) }
            3 -> { circle(80f,82f,45f); stroke();canvas.drawLine(45f,82f,115f,82f,p);canvas.drawLine(80f,47f,80f,117f,p);label("Aim + fire",80f,150f,18f);round(185f,40f,120f,84f);arrow(202f,82f,288f);label("Drive + steer",245f,150f,18f) }
            4 -> { circle(55f,64f,27f);label("HOLD",55f,69f,13f);circle(170f,64f,27f);label("REPEAT",170f,69f,12f);circle(285f,64f,27f);label("MACRO",285f,69f,12f);label("A",90f,145f,24f);arrow(115f,137f,150f);label("A",175f,145f,24f);arrow(200f,137f,235f);label("B",260f,145f,24f) }
            6 -> { phone(30f,35f,62f,110f);arrow(115f,85f,175f);round(195f,30f,120f,88f);stroke();canvas.drawLine(185f,130f,325f,130f,p);label("IP address",145f,63f,12f);label("Phone",61f,168f,17f);label("PC / Mac",255f,158f,17f) }
            else -> { phone(15f,40f,52f,90f);arrow(78f,85f,110f);round(121f,57f,57f,55f,accent);label("Pico W",150f,145f,16f);arrow(190f,85f,220f);round(231f,42f,95f,85f);circle(251f,84f,10f);circle(286f,84f,10f);label("Adaptive",279f,151f,15f);label("controller",279f,171f,15f);label("Phone",41f,153f,15f) }
        }
        canvas.restore()
    }
}
