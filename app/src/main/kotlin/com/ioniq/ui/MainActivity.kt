package com.ioniq.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import com.ioniq.ui.screens.HomeScreen
import com.ioniq.ui.theme.IoniqTheme

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: VehicleViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        viewModel = ViewModelProvider(this, VehicleViewModel.factory(application as android.app.Application))[VehicleViewModel::class.java]
        setContent { IoniqTheme { HomeScreen(viewModel = viewModel) } }
    }
}
