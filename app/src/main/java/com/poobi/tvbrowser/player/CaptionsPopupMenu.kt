package com.poobi.tvbrowser.player

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.common.C
import com.poobi.tvbrowser.R

object CaptionsPopupMenu {

    fun show(anchorView: View, playerEngine: PlayerEngine) {
        val context = anchorView.context
        val player = playerEngine.exoPlayer ?: return
        val density = context.resources.displayMetrics.density

        val popupView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val bgDrawable = GradientDrawable().apply {
                setColor(Color.parseColor("#F01E1E24"))
                setStroke((1 * density).toInt(), Color.parseColor("#333338"))
                cornerRadius = 8 * density
            }
            background = bgDrawable
            setPadding((6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt())
        }

        var popupWindowRef: PopupWindow? = null

        val createRow = { text: String, isAdd: Boolean, isSelected: Boolean, onClick: () -> Unit ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (44 * density).toInt()
                )
                setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)

                val normalDrawable = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                }
                val focusedDrawable = GradientDrawable().apply {
                    setColor(Color.parseColor("#3300BCD4"))
                    setStroke((2 * density).toInt(), Color.parseColor("#00BCD4"))
                    cornerRadius = 6 * density
                }
                val sld = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), focusedDrawable)
                    addState(intArrayOf(), normalDrawable)
                }
                background = sld
                isFocusable = true
                isClickable = true

                setOnClickListener {
                    onClick()
                    popupWindowRef?.dismiss()
                }
            }

            if (isAdd) {
                val icon = ImageView(context).apply {
                    setImageResource(R.drawable.ic_add)
                    setColorFilter(Color.parseColor("#00BCD4"))
                    layoutParams = LinearLayout.LayoutParams((18 * density).toInt(), (18 * density).toInt()).apply {
                        rightMargin = (10 * density).toInt()
                    }
                }
                row.addView(icon)
            }

            val textView = TextView(context).apply {
                this.text = text
                setTextColor(if (isAdd) Color.parseColor("#00BCD4") else Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(null, if (isAdd || isSelected) Typeface.BOLD else Typeface.NORMAL)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.MARQUEE
                marqueeRepeatLimit = -1
                setHorizontallyScrolling(true)
            }
            row.addView(textView)

            row.setOnFocusChangeListener { _, hasFocus ->
                textView.isSelected = hasFocus
            }

            if (isSelected && !isAdd) {
                val checkmark = ImageView(context).apply {
                    setImageResource(R.drawable.ic_watched)
                    setColorFilter(Color.parseColor("#00BCD4"))
                    layoutParams = LinearLayout.LayoutParams((18 * density).toInt(), (18 * density).toInt()).apply {
                        leftMargin = (8 * density).toInt()
                    }
                }
                row.addView(checkmark)
            }

            row
        }

        val addRow = createRow("Add subtitle from disk...", true, false) {
            playerEngine.showDiskSubtitlePicker()
        }
        popupView.addView(addRow)

        val divider = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (1 * density).toInt()
            ).apply {
                setMargins((6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt())
            }
            setBackgroundColor(Color.parseColor("#333338"))
        }
        popupView.addView(divider)

        val tracks = player.currentTracks
        val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        val isTextDisabled = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        val isAnyTrackSelected = !isTextDisabled && textGroups.any { group ->
            (0 until group.length).any { group.isTrackSelected(it) }
        }

        val offRow = createRow("Off", false, !isAnyTrackSelected) {
            playerEngine.disableSubtitles()
        }
        popupView.addView(offRow)

        var firstFocusedView: View = if (!isAnyTrackSelected) offRow else addRow

        textGroups.forEach { group ->
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val isSelected = isAnyTrackSelected && group.isTrackSelected(i)
                val trackName = format.label ?: format.language ?: "Subtitle Track ${i + 1}"

                val trackRow = createRow(trackName, false, isSelected) {
                    playerEngine.selectSubtitleTrack(group.mediaTrackGroup, i, format)
                }
                popupView.addView(trackRow)

                if (isSelected) {
                    firstFocusedView = trackRow
                }
            }
        }

        val popupWidth = (320 * density).toInt()
        popupView.measure(
            View.MeasureSpec.makeMeasureSpec(popupWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val fullHeight = popupView.measuredHeight
        val screenHeight = context.resources.displayMetrics.heightPixels
        val maxHeight = (screenHeight * 0.65f).toInt()
        val popupHeight = minOf(fullHeight, maxHeight)

        val scrollView = ScrollView(context).apply {
            layoutParams = ViewGroup.LayoutParams(popupWidth, popupHeight)
            isFocusable = false
            isFocusableInTouchMode = false
            addView(popupView)
        }

        val popupWindow = PopupWindow(
            scrollView,
            popupWidth,
            popupHeight,
            true
        ).apply {
            elevation = 20 * density
            isOutsideTouchable = true
            setOnDismissListener {
                anchorView.requestFocus()
            }
        }
        popupWindowRef = popupWindow

        val anchorH = if (anchorView.height > 0) anchorView.height else (48 * density).toInt()
        val yOffset = -(popupHeight + anchorH + (10 * density).toInt())

        popupWindow.showAsDropDown(anchorView, 0, yOffset, Gravity.START)

        firstFocusedView.post {
            firstFocusedView.requestFocus()
        }
    }
}