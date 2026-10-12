package com.tajmalook.fbrsurvey

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Keeps the screen content above the phone's navigation bar and keyboard (needed on Android 15+). */
fun Activity.keepAboveSystemBars() {
    val root = (findViewById<ViewGroup>(android.R.id.content)).getChildAt(0) ?: return
    val baseBottom = root.paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(root) { v: View, insets: WindowInsetsCompat ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        v.setPadding(bars.left, v.paddingTop, bars.right, baseBottom + maxOf(bars.bottom, ime.bottom))
        insets
    }
    ViewCompat.requestApplyInsets(root)
}
