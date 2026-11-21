package com.haseebullahjan.roshd.ui.components

import android.app.AlertDialog
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/** Apply the same shape, type and focus system to app-owned dialogs and pickers. */
fun AlertDialog.Builder.showThemed(theme:ClayTheme):AlertDialog {
    val dialog=create()
    dialog.setOnShowListener {
        val window=dialog.window ?: return@setOnShowListener
        window.setBackgroundDrawable(ClayDrawable(theme,radius=theme.radius,scale=context.resources.displayMetrics.density))
        window.decorView.setLayerType(View.LAYER_TYPE_SOFTWARE,null)
        window.decorView.layoutDirection=View.LAYOUT_DIRECTION_RTL
        fun style(view:View) {
            if(view is TextView) {
                if(view !is android.widget.Button) { view.typeface=ClayTheme.regular(context);view.setTextColor(theme.ink) }
                if(view.isClickable)view.minimumHeight=context.dp(48)
            }
            if(view is ViewGroup)for(i in 0 until view.childCount)style(view.getChildAt(i))
        }
        window.decorView.post { style(window.decorView) }
        for(which in listOf(AlertDialog.BUTTON_POSITIVE,AlertDialog.BUTTON_NEGATIVE,AlertDialog.BUTTON_NEUTRAL)) {
            dialog.getButton(which)?.apply {
                setTextColor(theme.onAccent);typeface=ClayTheme.bold(context);minHeight=context.dp(48)
                background=ClayDrawable(theme,accent=true,radius=24f,scale=context.resources.displayMetrics.density)
                textSize=14f
                setPadding(context.dp(16),context.dp(8),context.dp(16),context.dp(8))
            }
        }
        // The native content remains focusable/selectable; only its outer surface changes.
        dialog.findViewById<View>(android.R.id.content)?.setBackgroundColor(Color.TRANSPARENT)
    }
    dialog.show();return dialog
}
