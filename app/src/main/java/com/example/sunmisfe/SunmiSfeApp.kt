package com.example.sunmisfe

import android.app.Application
import android.util.Log
import com.example.sunmisfe.printing.ReceiptPrinter

class SunmiSfeApp : Application() {
    companion object {
        private const val TAG = "SunmiSfeApp"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Application onCreate - binding printer")
        ReceiptPrinter.bind(this)
    }

    override fun onTerminate() {
        Log.i(TAG, "Application onTerminate - unbinding printer")
        ReceiptPrinter.unbind()
        super.onTerminate()
    }
}