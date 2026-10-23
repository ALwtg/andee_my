package net.kuafuai.andee.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import net.kuafuai.andee.R

/**
 * The input pill, drawn one way in two places: resting at the foot of the card (where it is not a field — an
 * overlay cannot raise the keyboard — and every part opens [TextInputActivity]) and inside that activity, where
 * [middle] is the real field. One builder, so the two cannot drift: the user should see the keyboard arrive and
 * nothing else change.
 */
object InputPill {

    fun build(
        context: Context,
        middle: View,
        onCamera: () -> Unit,
        onAlbum: () -> Unit,
        onSend: () -> Unit,
    ): LinearLayout {
        fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()
        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Glass.well(context, dp(24))
            setPadding(dp(6), dp(4), dp(5), dp(4))
            minimumHeight = dp(48)
        }
        fun icon(res: Int, desc: String, onClick: () -> Unit) = ImageView(context).apply {
            setImageDrawable(ContextCompat.getDrawable(context, res)?.mutate())
            setColorFilter(Color.parseColor(Glass.SECONDARY))
            contentDescription = desc
            setPadding(dp(7), dp(7), dp(7), dp(7))
            isClickable = true
            Glass.pressable(this)
            setOnClickListener { onClick() }
        }
        pill.addView(icon(R.drawable.ic_camera, "拍照", onCamera), LinearLayout.LayoutParams(dp(36), dp(36)))
        pill.addView(icon(R.drawable.ic_image, "相册", onAlbum), LinearLayout.LayoutParams(dp(36), dp(36)))
        pill.addView(middle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4) })
        val send = ImageView(context).apply {
            setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_send)?.mutate())
            setColorFilter(Color.WHITE)
            contentDescription = "发送"
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(Glass.ACCENT))
            }
            isClickable = true
            Glass.pressable(this)
            setOnClickListener { onSend() }
        }
        pill.addView(send, LinearLayout.LayoutParams(dp(36), dp(36)))
        return pill
    }
}
