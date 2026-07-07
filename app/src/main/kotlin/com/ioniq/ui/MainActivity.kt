package com.ioniq.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.ioniq.data.repository.VehicleRepository
import com.ioniq.ui.screens.HomeScreen
import com.ioniq.ui.theme.IoniqTheme

class MainActivity : ComponentActivity() {

    private val viewModel: VehicleViewModel by viewModels {
        VehicleViewModelFactory(VehicleRepository(this))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            IoniqTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    HomeScreen(viewModel)
                }
            }
        }
    }
}
