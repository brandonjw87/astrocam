package com.example.astrocam

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.astrocam.theme.AstroCamTheme
import com.example.astrocam.ui.AstroScreen
import com.example.astrocam.ui.AstroViewModel

class MainActivity : ComponentActivity() {
  private val vm: AstroViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    // The exposure stops if the screen turns off, so keep it on while the app is open.
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    vm.setPermission(hasCameraPermission())

    setContent {
      AstroCamTheme {
        val state by vm.state.collectAsStateWithLifecycle()
        val launcher =
          rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            vm.setPermission(granted)
          }
        LaunchedEffect(Unit) { if (!hasCameraPermission()) launcher.launch(Manifest.permission.CAMERA) }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
          if (state.hasPermission) {
            AstroScreen(vm)
          } else {
            Column(
              Modifier.fillMaxSize().padding(32.dp),
              verticalArrangement = Arrangement.Center,
              horizontalAlignment = Alignment.CenterHorizontally,
            ) {
              Text("AstroCam needs camera access to take photos.", color = Color(0xFFE53935))
              Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }, modifier = Modifier.padding(top = 16.dp)) {
                Text("Grant camera permission")
              }
            }
          }
        }
      }
    }
  }

  private fun hasCameraPermission() =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
}
