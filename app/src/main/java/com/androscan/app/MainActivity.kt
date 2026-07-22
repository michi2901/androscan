package com.androscan.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.androscan.app.ui.MainScreen
import com.androscan.app.ui.ScanViewModel
import com.androscan.app.ui.theme.AndroscanTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AndroscanTheme {
                val viewModel: ScanViewModel = viewModel(
                    factory = ScanViewModel.factory(application)
                )
                MainScreen(viewModel = viewModel)
            }
        }
    }
}
