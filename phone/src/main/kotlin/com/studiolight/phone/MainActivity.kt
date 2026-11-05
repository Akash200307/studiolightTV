package com.studiolight.phone

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.studiolight.core.*

private val Lime = Color(0xFFC8FF75)
private val Surface = Color(0xFF1B1E24)

class MainActivity : ComponentActivity() {
  private val controller get() = (application as StudioApplication).controller
  private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
    controller.discovery.stop(); controller.discovery.start()
  }
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
    setContent {
      MaterialTheme(colorScheme = darkColorScheme(primary = Lime, surface = Surface, background = Color(0xFF101216))) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
          ControllerScreen(controller)
        }
      }
    }
  }
  override fun onStart() { super.onStart(); controller.start() }
  override fun onStop() { controller.stop(); super.onStop() }
}

@Composable
private fun ControllerScreen(c: Controller) {
  val state by c.state.collectAsState()
  val online by c.connected.collectAsState()
  val status by c.status.collectAsState()
  val name by c.name.collectAsState()
  val saved by c.saved.collectAsState()
  val pinTarget by c.needsPin.collectAsState()
  val storageError by c.storageError.collectAsState()
  var showDevices by remember { mutableStateOf(false) }
  var showSaved by remember { mutableStateOf(false) }
  var showEffects by remember { mutableStateOf(false) }
  var presetNotice by remember { mutableStateOf(false) }

  BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
    .safeDrawingPadding().padding(horizontal = 16.dp, vertical = 4.dp)) {
    val compact = maxHeight < 660.dp
    val tiny = maxHeight < 600.dp
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Row(Modifier.fillMaxWidth().height(48.dp).clickable { showDevices = true },
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(Color(state.rgb().argb())))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
          Text(name, maxLines = 1, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
          Text(status, color = if (online) Lime else Color(0xFFFFCA88), fontSize = 12.sp)
        }
        if (tiny) TextButton(onClick = { showEffects = true }) { Text("Speed") }
        Text("TVs ›", color = Lime, fontSize = 14.sp)
      }
      Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ModeButton("Color", state.mode == LightMode.COLOR, online, Modifier.weight(1f)) {
          c.edit(StatePatch(mode = LightMode.COLOR))
        }
        ModeButton("White", state.mode == LightMode.WHITE, online, Modifier.weight(1f)) {
          c.edit(StatePatch(mode = LightMode.WHITE))
        }
      }
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.SpaceEvenly) {
        if (state.mode == LightMode.COLOR) {
          LightSlider("Hue", "${state.hue.toInt()}°", state.hue, 0f..360f, online,
            rainbow = true, onFinish = c::finishDrag) { c.edit(StatePatch(hue = it), true) }
        } else {
          LightSlider("White", "${state.kelvin} K", state.kelvin.toFloat(), 2500f..9000f, online,
            onFinish = c::finishDrag) { c.edit(StatePatch(kelvin = it.toInt()), true) }
        }
        LightSlider("Saturation", "${state.saturation.toInt()}%", state.saturation, 0f..100f,
          online && state.mode == LightMode.COLOR, onFinish = c::finishDrag) { c.edit(StatePatch(saturation = it), true) }
        LightSlider("Brightness", "${state.brightness.toInt()}%", state.brightness, 0f..100f, online,
          onFinish = c::finishDrag) { c.edit(StatePatch(brightness = it), true) }
        LightSlider("Opacity", "${state.opacity.toInt()}%", state.opacity, 0f..100f, online,
          onFinish = c::finishDrag) { c.edit(StatePatch(opacity = it), true) }
      }
      // Ten lights plus Save and Library, in two fixed rows. No controller scrolling.
      repeat(2) { r ->
        Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          repeat(6) { col ->
            val i = r * 6 + col
            val p = builtInPresets.getOrNull(i)
            Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp))
              .clickable(enabled = if (p != null || i == 10) online else true) {
                when {
                  p != null -> c.edit(StatePatch.from(p.state))
                  i == 10 -> { c.savePreset(); presetNotice = true }
                  else -> showSaved = true
                }
              }, horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)) {
              if (p != null) Box(Modifier.size(22.dp).clip(CircleShape).background(Color(p.state.rgb().argb())))
              else Canvas(Modifier.size(22.dp)) {
                if (i == 10) {
                  drawLine(Lime, Offset(size.width * .2f, center.y), Offset(size.width * .8f, center.y), 2.dp.toPx())
                  drawLine(Lime, Offset(center.x, size.height * .2f), Offset(center.x, size.height * .8f), 2.dp.toPx())
                } else {
                  val cell = size.width / 3
                  val inset = 1.dp.toPx()
                  repeat(3) { y -> repeat(3) { x ->
                    drawRect(Lime, Offset(x * cell + inset, y * cell + inset),
                      Size(cell - inset * 2, cell - inset * 2), style = Stroke(1.dp.toPx()))
                  } }
                }
              }
              Text(p?.name ?: if (i == 10) "Save" else "Saved", fontSize = 10.sp, lineHeight = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth())
            }
          }
        }
      }
      Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = state.strobe, onClick = { c.edit(StatePatch(strobe = !state.strobe)) },
          enabled = online, label = { Text("Strobe") }, modifier = Modifier.weight(1f))
        FilterChip(selected = state.cycle, onClick = {
          c.edit(StatePatch(cycle = !state.cycle, mode = LightMode.COLOR))
        }, enabled = online, label = { Text("Cycle") }, modifier = Modifier.weight(1f))
        if (tiny) {
          Button(onClick = c::blackout, enabled = online, modifier = Modifier.weight(1.4f),
            contentPadding = PaddingValues(horizontal = 8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF30343B), contentColor = Color.White)) {
            Text("BLACKOUT", fontSize = 12.sp)
          }
        } else if (compact) TextButton(onClick = { showEffects = true }) { Text("Speed") }
      }
      if (!compact) EffectSpeeds(state, online, c)
      if (!tiny) Button(onClick = c::blackout, enabled = online,
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF30343B), contentColor = Color.White),
        shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().height(52.dp)) {
        Text("BLACKOUT", fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
      }
    }
  }
  // Discovery and pairing are separate from the one-handed light surface.
  if (showDevices || (saved.tvs.isEmpty() && !online && pinTarget == null)) {
    DeviceDialog(c, onDismiss = { showDevices = false }, onSelect = {
      showDevices = false; c.choose(it)
    })
  }
  pinTarget?.let { tv ->
    var pin by remember(tv.address) { mutableStateOf("") }
    AlertDialog(onDismissRequest = { c.needsPin.value = null }, title = { Text("Pair with ${tv.name}") },
      text = { Column {
        Text("Open the TV’s settings with MENU or hold BACK to see its 4-digit PIN.")
        OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(4) }, label = { Text("TV PIN") },
          singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
          visualTransformation = PasswordVisualTransformation())
      } }, confirmButton = { TextButton(enabled = pin.length == 4, onClick = { c.pair(tv, pin) }) { Text("Connect") } },
      dismissButton = { TextButton(onClick = { c.needsPin.value = null }) { Text("Cancel") } })
  }
  if (showSaved) AlertDialog(onDismissRequest = { showSaved = false }, title = { Text("Saved lights · ${saved.presets.size}/12") },
    text = {
      if (saved.presets.isEmpty()) Text("Tap Save on the controller to keep your current light.")
      else LazyColumn(Modifier.heightIn(max = 420.dp)) {
        items(saved.presets.size) { i ->
          val p = saved.presets[i]
          Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).height(52.dp).clickable(enabled = online) {
              c.edit(StatePatch.from(p)); showSaved = false
            }, verticalAlignment = Alignment.CenterVertically) {
              Box(Modifier.size(28.dp).clip(CircleShape).background(Color(p.rgb().argb())))
              Text("Light ${i + 1}", Modifier.padding(start = 12.dp))
            }
            TextButton(onClick = { c.deletePreset(i) }) { Text("Delete") }
          }
        }
      }
    }, confirmButton = { TextButton(onClick = { showSaved = false }) { Text("Done") } })
  if (showEffects) AlertDialog(onDismissRequest = { showEffects = false }, title = { Text("Effect speeds") },
    text = { Column {
      Text("Strobe · ${state.strobeHz.toInt()} Hz")
      Slider(state.strobeHz, { c.edit(StatePatch(strobeHz = it), true) }, valueRange = 1f..10f,
        enabled = online, onValueChangeFinished = c::finishDrag)
      Text("Cycle · ${state.cycleSpeed.toInt()} / 10")
      Slider(state.cycleSpeed, { c.edit(StatePatch(cycleSpeed = it), true) }, valueRange = 1f..10f,
        enabled = online, onValueChangeFinished = c::finishDrag)
    } }, confirmButton = { TextButton(onClick = { showEffects = false }) { Text("Done") } })
  if (presetNotice) AlertDialog(onDismissRequest = { presetNotice = false }, title = { Text("Light saved") },
    text = { Text("${saved.presets.size}/12 saved. When full, Save replaces the oldest light.") },
    confirmButton = { TextButton(onClick = { presetNotice = false }) { Text("Done") } })
  storageError?.let { error -> AlertDialog(onDismissRequest = { c.storageError.value = null },
    title = { Text("Storage") }, text = { Text(error) },
    confirmButton = { TextButton(onClick = { c.storageError.value = null }) { Text("OK") } }) }
}

@Composable
private fun ModeButton(text: String, selected: Boolean, enabled: Boolean, modifier: Modifier, click: () -> Unit) {
  Button(onClick = click, enabled = enabled, modifier = modifier.fillMaxHeight(),
    shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(
      containerColor = if (selected) Lime else Surface, contentColor = if (selected) Color.Black else Color.White)) {
    Text(text)
  }
}

@Composable
private fun LightSlider(label: String, valueText: String, value: Float, range: ClosedFloatingPointRange<Float>,
  enabled: Boolean, rainbow: Boolean = false, onFinish: () -> Unit, onChange: (Float) -> Unit) {
  Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.width(86.dp)) {
      Text(label, fontSize = 12.sp, color = Color(0xFFBBC0CC))
      Text(valueText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
      if (rainbow) Canvas(Modifier.fillMaxWidth().height(12.dp).padding(horizontal = 8.dp)) {
        drawRoundRect(Brush.horizontalGradient(listOf(Color.Red, Color.Yellow, Color.Green,
          Color.Cyan, Color.Blue, Color.Magenta, Color.Red)), cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()))
      }
      Slider(value, onChange, enabled = enabled, valueRange = range, onValueChangeFinished = onFinish,
        colors = if (rainbow) SliderDefaults.colors(activeTrackColor = Color.Transparent,
          inactiveTrackColor = Color.Transparent, thumbColor = Color.White) else SliderDefaults.colors())
    }
  }
}

@Composable
private fun EffectSpeeds(s: LightState, enabled: Boolean, c: Controller) {
  Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    Column(Modifier.weight(1f)) {
      Text("Flash · ${s.strobeHz.toInt()} Hz", fontSize = 10.sp)
      Slider(s.strobeHz, { c.edit(StatePatch(strobeHz = it), true) }, valueRange = 1f..10f,
        enabled = enabled, onValueChangeFinished = c::finishDrag, modifier = Modifier.height(32.dp))
    }
    Column(Modifier.weight(1f)) {
      Text("Cycle · ${s.cycleSpeed.toInt()}/10", fontSize = 10.sp)
      Slider(s.cycleSpeed, { c.edit(StatePatch(cycleSpeed = it), true) }, valueRange = 1f..10f,
        enabled = enabled, onValueChangeFinished = c::finishDrag, modifier = Modifier.height(32.dp))
    }
  }
}

@Composable
private fun DeviceDialog(c: Controller, onDismiss: () -> Unit, onSelect: (TvDevice) -> Unit) {
  val devices by c.discovery.devices.collectAsState()
  val saved by c.saved.collectAsState()
  val message by c.discovery.message.collectAsState()
  var ip by remember { mutableStateOf("") }
  var error by remember { mutableStateOf<String?>(null) }
  AlertDialog(onDismissRequest = onDismiss, title = { Text("Find your light") }, text = {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(message, fontSize = 13.sp)
      LazyColumn(Modifier.heightIn(max = 210.dp)) {
        val all = (devices + saved.tvs).distinctBy { it.address }
        items(all.size) { i ->
          val tv = all[i]
          Column(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { onSelect(tv) }.padding(6.dp)) {
            Text(tv.name, color = Lime)
            Text(tv.address, fontSize = 12.sp)
          }
        }
      }
      OutlinedTextField(ip, { ip = it; error = null }, label = { Text("Connect by IP") },
        placeholder = { Text("192.168.1.42:8765") }, singleLine = true, isError = error != null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
      error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
      Text("Same WiFi · Open Studio Light on your TV", fontSize = 12.sp)
    }
  }, confirmButton = { TextButton(onClick = {
    runCatching { LanEndpoint.parse(ip) }.onSuccess { onSelect(TvDevice("Studio Light", it.authority)) }
      .onFailure { error = it.message ?: "Enter a local IP address" }
  }, enabled = ip.isNotBlank()) { Text("Connect") } }, dismissButton = {
    TextButton(onClick = { c.discovery.stop(); c.discovery.start() }) { Text("Refresh") }
  })
}
