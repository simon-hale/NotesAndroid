package com.notes.notes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.notes.notes.ui.NotesApp
import com.notes.notes.ui.components.WebViewWarmUp

class MainActivity : ComponentActivity() {
    override fun onCreate(
        savedInstanceState: Bundle?,
    ) {
        super.onCreate(
            savedInstanceState
        )

        enableEdgeToEdge()

        WebViewWarmUp.start(
            applicationContext
        )

        setContent {
            NotesApp(
                viewModel = viewModel()
            )
        }
    }
}
