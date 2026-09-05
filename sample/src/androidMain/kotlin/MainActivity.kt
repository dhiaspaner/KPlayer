package com.dhiachemingui.kplayer.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import sample.SampleApp
import kotlinx.coroutines.launch
import kplayer.initializeContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initializeContext(this)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {}
        }
        setContent {
            SampleApp()
        }
    }
}
