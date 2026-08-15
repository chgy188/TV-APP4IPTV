package com.example.composedtv

import android.app.Application
import com.example.composedtv.data.remote.ApiClient

class ComposedTVApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ApiClient.init(this)
    }
}
