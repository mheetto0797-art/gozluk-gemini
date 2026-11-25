package com.metin.gozlukgemini

import android.app.Application
import android.util.Log
import com.meta.wearable.dat.core.Wearables

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Wearables.initialize(this)
            .onFailure { error, _ -> Log.e("GozlukGemini", "DAT init: ${error.description}") }
    }
}
