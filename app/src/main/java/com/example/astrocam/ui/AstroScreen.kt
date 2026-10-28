package com.example.astrocam.ui

import android.app.Activity
import android.graphics.SurfaceTexture
import android.view.TextureView
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.ceil
import kotlin.math.min

private val Red = Color(0xFFE53935)
private val DimRed = Color(0xFF7A1F1F)
private val FaintRed = Color(0xFF4A1414)

private val TOTAL_OPTIONS = listOf(30, 60, 120, 300, 600, 900)

@Composable
fun AstroScreen(vm: AstroViewModel) {
  val state by vm.state.collectAsStateWithLifecycle()
  val lifecycleOwner = LocalLifecycleOwner.current

  DisposableEffect(lifecycleOwner) {
    val obs = LifecycleEventObserver { _, e ->
      when (e) {
        Lifecycle.Event.ON_RESUME -> vm.setForeground(true)
        Lifecycle.Event.ON_PAUSE -> vm.setForeground(false)
        else -> Unit
      }
    }
    lifecycleOwner.lifecycle.addObserver(obs)
    onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
  }

  // Dim the screen to near-black while exposing (no light spill, saves battery/heat).
  val activity = LocalContext.current as? Activity
  LaunchedEffect(state.phase is Phase.Capturing) {
    activity?.window?.let { w ->
      val lp = w.attributes
      lp.screenBrightness =
        if (state.phase is Phase.Capturing) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
      w.attributes = lp
    }
  }

  Box(Modifier.fillMaxSize().background(Color.Black)) {
    MainLayout(state, vm)
    when (val p = state.phase) {
      is Phase.Countdown -> CountdownOverlay(p.secondsLeft, vm::cancel)
      is Phase.Capturing -> CapturingOverlay(p, state.settings.totalSeconds, vm::cancel)
      is Phase.Processing -> MessageOverlay(title = "Processing", lines = listOf(p.message), busy = true)
      is Phase.Done -> MessageOverlay("Done ✓", p.lines, onOk = vm::dismissResult)
      is Phase.Failed -> MessageOverlay("Stopped", listOf(p.message), onOk = vm::dismissResult)
      Phase.Idle -> Unit
    }
  }
}

@Composable
private fun MainLayout(state: UiState, vm: AstroViewModel) {
  val s = state.settings
  val info = state.info
  Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 12.dp)) {
    Text("AstroCam", color = Red, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
    if (info != null) {
      Text(info.description, color = DimRed, fontSize = 11.sp)
      Text(
        "Max single frame ${AstroViewModel.formatExposure(info.exposureRangeNs.upper)} · ISO ${info.isoRange.lower}–${info.isoRange.upper} (analog ≤ ${info.maxAnalogIso})",
        color = DimRed,
        fontSize = 11.sp,
      )
    }
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }

    // ---- Viewfinder ----
    Box(Modifier.weight(1.1f).fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
      if (state.hasPermission && info != null) {
        AndroidView(
          modifier =
            Modifier.aspectRatio(
                info.previewSize.height.toFloat() / info.previewSize.width,
                matchHeightConstraintsFirst = true,
              )
              .clip(RoundedCornerShape(8.dp))
              .border(1.dp, FaintRed, RoundedCornerShape(8.dp))
              .clickable { vm.updateSettings { it.copy(nightBoost = !it.nightBoost) } },
          factory = { ctx ->
            TextureView(ctx).apply {
              surfaceTextureListener =
                object : TextureView.SurfaceTextureListener {
                  override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = vm.onSurfaceAvailable(st)

                  override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}

                  override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                    vm.onSurfaceDestroyed()
                    return true
                  }

                  override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                }
            }
          },
        )
        Text(
          if (s.nightBoost) "Night viewfinder ON · tap to toggle" else "Normal viewfinder · tap for night boost",
          color = DimRed,
          fontSize = 10.sp,
          modifier = Modifier.align(Alignment.TopCenter).padding(top = 6.dp).background(Color(0xAA000000)).padding(horizontal = 6.dp),
        )
      }
    }

    // ---- Controls ----
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
      if (info != null) {
        val maxD = min(2f, info.minFocusDiopters)
        if (maxD > 0f) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Focus", color = Red, fontSize = 13.sp, modifier = Modifier.width(48.dp))
            Slider(
              value = s.focusDiopters,
              onValueChange = { v -> vm.updateSettings { it.copy(focusDiopters = v) } },
              valueRange = 0f..maxD,
              modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = { vm.updateSettings { it.copy(focusDiopters = 0f) } }, modifier = Modifier.padding(start = 6.dp)) {
              Text(if (s.focusDiopters == 0f) "∞" else "%.2f→∞".format(s.focusDiopters), color = Red)
            }
          }
          if (!info.focusCalibrated) {
            Text("Lens reports uncalibrated focus: if stars look soft, nudge the slider while zooming in on a bright star.", color = DimRed, fontSize = 10.sp)
          }
        }
      }

      ChipRow("Total exposure", TOTAL_OPTIONS, s.totalSeconds, { AstroViewModel.formatDuration(it.toLong()) }) { v ->
        vm.updateSettings { it.copy(totalSeconds = v) }
      }
      ChipRow("Frame length", state.subExposureOptionsNs, s.subExposureNs, { ns ->
        AstroViewModel.formatExposure(ns) + if (ns == state.subExposureOptionsNs.firstOrNull()) " (max)" else ""
      }) { v -> vm.updateSettings { it.copy(subExposureNs = v) } }
      ChipRow("ISO", state.isoOptions, s.iso, { it.toString() }) { v -> vm.updateSettings { it.copy(iso = v) } }
      ChipRow("White balance", WhiteBalance.entries, s.whiteBalance, { it.label }) { v ->
        vm.updateSettings { it.copy(whiteBalance = v) }
      }
      ChipRow("Output", OutputMode.entries, s.output, { it.label }) { v -> vm.updateSettings { it.copy(output = v) } }

      val frames = ceil(s.totalSeconds * 1e9 / s.subExposureNs).toInt().coerceAtLeast(1)
      Text(
        "≈ $frames RAW frames stacked → ${AstroViewModel.formatDuration(s.totalSeconds.toLong())} total exposure",
        color = DimRed,
        fontSize = 12.sp,
        modifier = Modifier.padding(top = 4.dp),
      )
      Spacer(Modifier.height(8.dp))
      Button(
        onClick = vm::start,
        enabled = state.cameraReady,
        modifier = Modifier.fillMaxWidth().height(64.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Red, contentColor = Color.Black),
      ) {
        Text(
          if (state.cameraReady) "START · 10 s countdown" else "Opening camera…",
          fontSize = 20.sp,
          fontWeight = FontWeight.Bold,
        )
      }
      Spacer(Modifier.height(12.dp))
    }
  }
}

@Composable
private fun <T> ChipRow(title: String, options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
  if (options.isEmpty()) return
  Text(title, color = Red, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
  Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    options.forEach { opt ->
      FilterChip(
        selected = opt == selected,
        onClick = { onSelect(opt) },
        label = { Text(label(opt)) },
        colors =
          FilterChipDefaults.filterChipColors(
            selectedContainerColor = Red,
            selectedLabelColor = Color.Black,
            labelColor = Red,
          ),
      )
    }
  }
}

@Composable
private fun CountdownOverlay(secondsLeft: Int, onCancel: () -> Unit) {
  Column(
    Modifier.fillMaxSize().background(Color.Black).systemBarsPadding().padding(24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Text("Set the phone down & walk away", color = Red, fontSize = 18.sp, textAlign = TextAlign.Center)
    Text(
      if (secondsLeft > 0) "$secondsLeft" else "●",
      color = Red,
      fontSize = 220.sp,
      fontWeight = FontWeight.Black,
    )
    Text(if (secondsLeft > 0) "Shutter opens at zero" else "Opening shutter…", color = DimRed, fontSize = 16.sp)
    Spacer(Modifier.height(48.dp))
    OutlinedButton(onClick = onCancel, modifier = Modifier.size(width = 200.dp, height = 56.dp)) {
      Text("Cancel", color = Red, fontSize = 18.sp)
    }
  }
}

@Composable
private fun CapturingOverlay(p: Phase.Capturing, totalSeconds: Int, onCancel: () -> Unit) {
  val progress = if (p.targetFrames > 0) p.frames.toFloat() / p.targetFrames else 0f
  Column(
    Modifier.fillMaxSize().background(Color.Black).systemBarsPadding().padding(24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Text("EXPOSING", color = DimRed, fontSize = 28.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(16.dp))
    Text(mmss(p.elapsedMs / 1000) + " / " + mmss(totalSeconds.toLong()), color = DimRed, fontSize = 40.sp)
    Spacer(Modifier.height(16.dp))
    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = DimRed, trackColor = FaintRed)
    Spacer(Modifier.height(8.dp))
    Text(
      "Frame ${p.frames} / ${p.targetFrames} · ${AstroViewModel.formatExposure(p.actualExposureNs)} @ ISO ${p.actualIso}",
      color = FaintRed,
      fontSize = 14.sp,
    )
    Spacer(Modifier.height(24.dp))
    Text("Don't touch the phone. You'll hear a chime when it's done.", color = FaintRed, fontSize = 13.sp, textAlign = TextAlign.Center)
    Spacer(Modifier.height(48.dp))
    OutlinedButton(onClick = onCancel) { Text("Cancel", color = DimRed) }
  }
}

@Composable
private fun MessageOverlay(title: String, lines: List<String>, busy: Boolean = false, onOk: (() -> Unit)? = null) {
  Column(
    Modifier.fillMaxSize().background(Color(0xF0000000)).systemBarsPadding().padding(24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Text(title, color = Red, fontSize = 28.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(16.dp))
    if (busy) {
      CircularProgressIndicator(color = Red)
      Spacer(Modifier.height(16.dp))
    }
    lines.forEach { Text(it, color = Red, fontSize = 14.sp, modifier = Modifier.fillMaxWidth()) }
    if (onOk != null) {
      Spacer(Modifier.height(24.dp))
      Button(onClick = onOk, colors = ButtonDefaults.buttonColors(containerColor = Red, contentColor = Color.Black)) {
        Text("OK", fontSize = 18.sp)
      }
    }
  }
}

private fun mmss(sec: Long) = "%d:%02d".format(sec / 60, sec % 60)
