package com.example.simplecontroller.ui

import android.content.Context
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.example.simplecontroller.R
import com.example.simplecontroller.io.GameStarter

internal fun showGameStarterDialog(
    context: Context, firstLaunch: Boolean, pick: (GameStarter) -> Boolean
) {
    val dp = { n: Int -> AppUiStyle.dp(context, n) }
    val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(8), dp(18), dp(4))
    }
    val dialog = AlertDialog.Builder(context)
        .setTitle(R.string.game_starters)
        .setView(ScrollView(context).apply { addView(column) })
        .setNegativeButton(if (firstLaunch) R.string.starter_standard else android.R.string.cancel, null)
        .create()
    column.addView(TextView(context).apply {
        setText(R.string.starter_intro)
        setTextColor(ContextCompat.getColor(context, R.color.ui_text))
        textSize = 16f
        setPadding(0, 0, 0, dp(8))
    })
    for (starter in GameStarter.entries) {
        column.addView(Button(context).apply {
            AppUiStyle.button(this)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            text = context.getString(starter.title) + "\n" + context.getString(starter.description)
            setOnClickListener { if (pick(starter)) dialog.dismiss() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
    }
    dialog.show()
    PropertySheetStyle(context).dialog(dialog)
}
