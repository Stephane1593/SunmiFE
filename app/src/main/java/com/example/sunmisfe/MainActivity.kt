package com.example.sunmisfe

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import com.example.sunmisfe.network.SfeApiClient
import com.example.sunmisfe.ui.theme.PosApp

class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "MainActivity onCreate")
        SfeApiClient.init(applicationContext)
        setContent {
            MaterialTheme { PosApp() }
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "MainActivity onDestroy")
        super.onDestroy()
    }
}