package com.tajmalook.fbrsurvey

import java.util.concurrent.Executors

/** App-wide background worker: keeps running after a screen is closed. */
object Bg {
    val io = Executors.newSingleThreadExecutor()
}
