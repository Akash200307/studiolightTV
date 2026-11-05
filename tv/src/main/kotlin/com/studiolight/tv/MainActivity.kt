package com.studiolight.tv

import android.content.Intent
import android.annotation.SuppressLint
import android.os.*
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.*
import com.studiolight.core.*
import kotlinx.coroutines.*

class MainActivity : ComponentActivity() {
  private val repo get() = (application as StudioApplication).repository
  private var overlay by mutableStateOf(false)
  private var lastInteraction by mutableLongStateOf(0L)
  private var row by mutableIntStateOf(0)
  private val handler = Handler(Looper.getMainLooper())
  private var longPressed = false
  private val holdAction = Runnable {
    longPressed = true
    if (heldKey == KeyEvent.KEYCODE_BACK) showSettings() else apply(ClientMessage.Blackout)
  }
  private var heldKey = 0

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    WindowCompat.setDecorFitsSystemWindows(window, false)
    WindowInsetsControllerCompat(window, window.decorView).apply {
      hide(WindowInsetsCompat.Type.systemBars())
      systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
    lifecycleScope.launch {
      repo.ready.await()
      if (repo.firstLaunch.value) { showSettings(); row = 6 }
    }
    setContent {
      MaterialTheme(colorScheme = darkColorScheme()) {
        val frame by repo.state.collectAsState()
        val pin by repo.pin.collectAsState()
        val status by repo.serviceStatus.collectAsState()
        val error by repo.persistenceError.collectAsState()
        LightCanvas(frame)
        if (overlay) {
          LaunchedEffect(lastInteraction) { delay(5000); overlay = false }
          Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.CenterEnd) {
            Column(Modifier.width(440.dp).background(Color(0xF01A1C20), RoundedCornerShape(20.dp))
              .padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Text("Studio Light · ${Build.MODEL}", color = Color(0xFFC8FF75), fontSize = 20.sp)
              Text("↑ ↓ select   ← → adjust   OK apply", color = Color.White, fontSize = 13.sp)
              val s = frame.state
              val labels = listOf("Opacity     ${s.opacity.toInt()}%", "Saturation     ${s.saturation.toInt()}%",
                "White temperature     ${s.kelvin} K", "Preset     ${builtInPresets[presetIndex].name}",
                "Strobe     ${if (s.strobe) "ON" else "OFF"} · ${s.strobeHz.toInt()} Hz",
                "Color cycle     ${if (s.cycle) "ON" else "OFF"} · speed ${s.cycleSpeed.toInt()}",
                "Pairing     PIN $pin", "Regenerate PIN")
              val listState = rememberLazyListState()
              LaunchedEffect(row) { listState.animateScrollToItem(row) }
              LazyColumn(Modifier.height(190.dp), state = listState,
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(labels.size) { i ->
                  Text(labels[i], fontSize = 16.sp, color = if (row == i) Color.Black else Color.White,
                    modifier = Modifier.fillMaxWidth().background(if (row == i) Color(0xFFC8FF75)
                      else Color.Transparent, RoundedCornerShape(6.dp)).padding(8.dp))
                }
              }
              Text("${lanAddresses()}\nPIN $pin · Phone: Studio Light Control", color = Color.White, fontSize = 16.sp)
              Text(status, color = Color.White, fontSize = 12.sp)
              error?.let { Text(it, color = Color(0xFFFFBC90), fontSize = 12.sp) }
              Text("MENU opens settings · hold OK to black out\nHold BACK for settings · BACK closes", color = Color.White, fontSize = 12.sp)
            }
          }
        }
      }
    }
  }

  override fun onStart() {
    super.onStart()
    ContextCompat.startForegroundService(this, Intent(this, LightService::class.java))
  }
  override fun onStop() {
    handler.removeCallbacks(holdAction)
    if (!isChangingConfigurations) stopService(Intent(this, LightService::class.java))
    super.onStop()
  }

  private fun showSettings() { overlay = true; lastInteraction = SystemClock.elapsedRealtime() }
  private fun apply(message: ClientMessage) { lifecycleScope.launch { repo.apply(message) } }
  private var presetIndex by mutableIntStateOf(0)
  private fun adjust(direction: Int) {
    val s = repo.state.value.state
    val patch = when (row) {
      0 -> StatePatch(opacity = (s.opacity + direction * 5).coerceIn(0f, 100f))
      1 -> StatePatch(saturation = (s.saturation + direction * 5).coerceIn(0f, 100f))
      2 -> StatePatch(mode = LightMode.WHITE, kelvin = (s.kelvin + direction * 250).coerceIn(2500, 9000))
      3 -> { presetIndex = (presetIndex + direction + builtInPresets.size) % builtInPresets.size
        StatePatch.from(builtInPresets[presetIndex].state) }
      4 -> StatePatch(strobeHz = (s.strobeHz + direction).coerceIn(1f, 10f))
      5 -> StatePatch(cycleSpeed = (s.cycleSpeed + direction).coerceIn(1f, 10f))
      else -> return
    }
    apply(ClientMessage.Set(patch))
  }

  override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
    handleRemote(event) || super.onKeyDown(keyCode, event)

  override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
    handleRemote(event) || super.onKeyUp(keyCode, event)

  // This TV-only activity opts out of predictive back in the manifest so that
  // physical remote BACK down/up events remain available for the long press.
  @SuppressLint("GestureBackNavigation")
  private fun handleRemote(event: KeyEvent): Boolean {
    val code = event.keyCode
    val ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
    if (ok || code == KeyEvent.KEYCODE_BACK) {
      if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
        heldKey = code; longPressed = false; handler.postDelayed(holdAction, 650)
      }
      if (event.action == KeyEvent.ACTION_UP) {
        handler.removeCallbacks(holdAction)
        if (!longPressed) {
          if (code == KeyEvent.KEYCODE_BACK) {
            if (overlay) overlay = false else finish()
          } else if (overlay) {
            showSettings()
            when (row) {
              4 -> apply(ClientMessage.Set(StatePatch(strobe = !repo.state.value.state.strobe)))
              5 -> apply(ClientMessage.Set(StatePatch(cycle = !repo.state.value.state.cycle, mode = LightMode.COLOR)))
              7 -> lifecycleScope.launch { repo.regeneratePin() }
              else -> adjust(1)
            }
          } else apply(ClientMessage.Set(StatePatch(mode =
            if (repo.state.value.state.mode == LightMode.COLOR) LightMode.WHITE else LightMode.COLOR)))
        }
      }
      return true
    }
    if (event.action != KeyEvent.ACTION_DOWN) return false
    if (code == KeyEvent.KEYCODE_MENU) { showSettings(); return true }
    if (code !in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) return false
    val direction = if (code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_UP) 1 else -1
    if (overlay) {
      showSettings()
      if (code == KeyEvent.KEYCODE_DPAD_UP) row = (row + 7) % 8
      else if (code == KeyEvent.KEYCODE_DPAD_DOWN) row = (row + 1) % 8
      else adjust(direction)
    } else {
      val s = repo.state.value.state
      apply(ClientMessage.Set(if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)
        StatePatch(hue = (s.hue + direction * 5 + 360) % 360, mode = LightMode.COLOR)
        else StatePatch(brightness = (s.brightness + direction * 5).coerceIn(0f, 100f))))
    }
    return true
  }
}

@Composable
private fun LightCanvas(frame: ServerMessage.State) {
  val s = frame.state
  var elapsed by remember { mutableFloatStateOf(0f) }
  LaunchedEffect(s.cycle, s.strobe, s.cycleSpeed, s.strobeHz) {
    val start = withFrameNanos { it }
    while (isActive && (s.cycle || s.strobe)) {
      withFrameNanos { elapsed = (it - start) / 1_000_000_000f }
    }
  }
  val hue = if (s.cycle) (s.hue + elapsed * s.cycleSpeed * 3f) % 360 else s.hue
  val flashOn = !s.strobe || (elapsed * s.strobeHz * 2).toInt() % 2 == 0
  val target = Color(s.rgb(hue).argb()).copy(alpha = s.opacity / 100f)
  val color by animateColorAsState(target, tween(if (frame.instant || s.cycle || s.strobe) 0 else 250), label = "light")
  Box(Modifier.fillMaxSize().background(Color.Black)) {
    Box(Modifier.fillMaxSize().background(if (flashOn) color else Color.Black))
  }
}
