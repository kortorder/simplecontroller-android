package com.example.simplecontroller.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.example.simplecontroller.R

/** The complete tools list uses its own window. Opening it never changes the active output route. */
internal class ControllerToolsDialog(
    private val context: Context,
    private val toggleCamera: () -> Unit,
    private val setStrength: (Int) -> Unit,
    private val releaseAll: () -> Unit,
    private val showBridge: () -> Unit,
    private val switches: List<View>,
    private val actions: List<Pair<String, () -> Unit>>
) {
    private val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(8), dp(16), dp(16))
    }
    private val camera = Button(context)
    private val strength = SeekBar(context).apply {
        id = R.id.panelStrength
        max = 200
        minimumHeight = dp(48)
        contentDescription = context.getString(R.string.toolbar_camera_strength)
    }
    private val strengthLabel = TextView(context)
    private val status = TextView(context)
    private lateinit var dialog: AlertDialog
    private fun dp(value: Int) = AppUiStyle.dp(context, value)

    fun open(onClose: () -> Unit) {
        camera.id = R.id.panelCamera
        AppUiStyle.button(camera)
        camera.setOnClickListener { toggleCamera() }
        column.addView(camera)
        column.addView(strengthLabel)
        column.addView(strength)
        strength.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) setStrength(progress)
            }
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
        action(context.getString(R.string.toolbar_bridge)) { dialog.dismiss(); showBridge() }
        action(context.getString(R.string.toolbar_release), releaseAll)
        for ((label, onClick) in actions) action(label) { dialog.dismiss(); onClick() }
        for (view in switches) {
            (view.parent as? ViewGroup)?.removeView(view)
            if (view is android.widget.Switch) view.minHeight = dp(48)
            column.addView(view, LinearLayout.LayoutParams(-1, -2))
        }
        column.addView(status)
        dialog = AlertDialog.Builder(context).setTitle(R.string.toolbar_tools)
            .setView(ScrollView(context).apply { addView(column) })
            .setNegativeButton(R.string.toolbar_done, null).create()
        dialog.setOnDismissListener {
            switches.forEach(column::removeView)
            onClose()
        }
        dialog.show()
        PropertySheetStyle(context).dialog(dialog)
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
    }

    private fun action(label: String, onClick: () -> Unit) {
        column.addView(Button(context).apply {
            AppUiStyle.button(this)
            text = label
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
    }

    fun showState(cameraState: String, available: Boolean, percent: Int, readout: String) {
        val cameraLabel = context.getString(R.string.toolbar_camera) + " · " + cameraState
        if (camera.text != cameraLabel) camera.text = cameraLabel
        camera.isEnabled = available
        strength.isEnabled = available
        if (!strength.isPressed && strength.progress != percent) strength.progress = percent
        val label = "${context.getString(R.string.toolbar_camera_strength)}: $percent%"
        if (strengthLabel.text != label) strengthLabel.text = label
        if (status.text != readout) status.text = readout
    }
}
