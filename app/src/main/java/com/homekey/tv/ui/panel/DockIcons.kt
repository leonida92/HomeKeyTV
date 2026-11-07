package com.homekey.tv.ui.panel

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import com.homekey.tv.data.models.DomainColorPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

private val appIconCache = ConcurrentHashMap<String, ImageBitmap>()

@Composable
fun rememberAppIconBitmap(packageName: String?): ImageBitmap? {
    if (packageName == null) return null

    // Return the cached bitmap immediately if we have one.
    appIconCache[packageName]?.let { return it }

    // Decode the app icon off the main thread; a placeholder is rendered until it arrives.
    val context = LocalContext.current
    var icon by remember(packageName) { mutableStateOf(appIconCache[packageName]) }
    LaunchedEffect(packageName) {
        if (icon != null) return@LaunchedEffect
        val decoded = withContext(Dispatchers.IO) {
            try {
                val pm = context.packageManager
                val drawable = pm.getApplicationIcon(packageName)
                drawableToImageBitmap(drawable)
            } catch (e: Exception) {
                null
            }
        }
        if (decoded != null) {
            appIconCache[packageName] = decoded
            icon = decoded
        }
    }
    return icon
}

fun drawableToImageBitmap(drawable: Drawable): ImageBitmap? {
    return try {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            drawable.bitmap.asImageBitmap()
        } else {
            val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 96
            val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 96
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bitmap.asImageBitmap()
        }
    } catch (e: Exception) {
        null
    }
}

internal fun getActiveColor(domain: String, palette: DomainColorPalette = DomainColorPalette.CLASSIC): Color {
    return palette.getColorForDomain(domain)
}

private val iconVectorCache = ConcurrentHashMap<String, ImageVector>()

fun resolveDockIcon(customIcon: String?, haIcon: String?, domain: String): ImageVector {
    val cacheKey = "${customIcon ?: ""}|${haIcon ?: ""}|$domain"
    return iconVectorCache.getOrPut(cacheKey) {
        computeDockIcon(customIcon, haIcon, domain)
    }
}

private fun computeDockIcon(customIcon: String?, haIcon: String?, domain: String): ImageVector {
    val key = (customIcon ?: haIcon ?: "").trim().lowercase()
        .removePrefix("mdi:")
        .replace("-", "_")

    return when {
        // Lighting
        key.contains("nightlight") || key.contains("moon") -> Icons.Default.Nightlight
        key.contains("strip") || key.contains("fluorescent") -> Icons.Default.Fluorescent
        key.contains("spotlight") || key.contains("flash") -> Icons.Default.FlashOn
        key.contains("ceiling") || key.contains("chandelier") -> Icons.Default.Light
        key.contains("lamp") || key.contains("incandescent") -> Icons.Default.WbIncandescent
        key.contains("sunny") || key.contains("sun") || key.contains("bright") -> Icons.Default.WbSunny
        key.contains("lightbulb") || key.contains("bulb") || key.contains("light") || key.contains("led") -> Icons.Default.Lightbulb

        // Climate & Air
        key == "ac" || key == "ac_unit" || key.contains("air_condition") || key.contains("aircondition") ||
        key.contains("conditioning") || key.contains("snowflake") || key.contains("cold") || key.contains("frost") -> Icons.Default.AcUnit
        key.contains("fan") || key.contains("vent") || key.contains("blower") -> Icons.Default.Air
        key.contains("fireplace") -> Icons.Default.Fireplace
        key.contains("heater") || key.contains("radiator") || key.contains("warm") || key.contains("flame") || key.contains("fire") || key.contains("candle") -> Icons.Default.Whatshot
        key.contains("thermostat") || key.contains("temp") || key.contains("hvac") -> Icons.Default.Thermostat

        // Security & Access (check doorbell and locks before door/window)
        key.contains("doorbell") || key.contains("bell") -> Icons.Default.Doorbell
        key.contains("lock_open") || key.contains("unlock") -> Icons.Default.LockOpen
        key.contains("lock") -> Icons.Default.Lock
        key == "key" || key.contains("key_") || key.contains("_key") || key.contains("passkey") -> Icons.Default.Key
        key.contains("alarm") || key.contains("siren") -> Icons.Default.Alarm
        key.contains("shield") || key.contains("protect") -> Icons.Default.Shield
        key.contains("security") -> Icons.Default.Security
        key.contains("camera") || key.contains("cctv") || key.contains("video") || key.contains("cam") -> Icons.Default.Videocam

        // Doors, Windows & Covers
        key.contains("door_sliding") || key.contains("sliding") -> Icons.Default.DoorSliding
        key == "door" || key == "door_front" || key.contains("front_door") || (key.contains("door") && !key.contains("outdoor")) -> Icons.Default.DoorFront
        key.contains("garage") -> Icons.Default.Garage
        key.contains("curtain") -> Icons.Default.Curtains
        key.contains("blind") -> Icons.Default.Blinds
        key.contains("roller") || key.contains("shade") -> Icons.Default.RollerShades
        key.contains("window") -> Icons.Default.Window

        // Vehicles
        key.contains("electric_car") || key.contains("ev_car") -> Icons.Default.ElectricCar
        key.contains("ev_station") || key.contains("charger") || key.contains("charging") -> Icons.Default.EvStation
        key.contains("car") || key.contains("auto") || key.contains("vehicle") -> Icons.Default.DirectionsCar
        key.contains("bike") || key.contains("bicycle") -> Icons.Default.PedalBike
        key.contains("motorcycle") || key.contains("scooter") -> Icons.Default.TwoWheeler

        // Power & Energy
        key == "socket" || key.contains("socket") || key.contains("outlet") -> Icons.Default.Outlet
        key == "plug" || key.contains("plug") -> Icons.Default.Power
        key.contains("bolt") || key.contains("energy") || key.contains("voltage") || key.contains("current") || (key.contains("electric") && !key.contains("car")) -> Icons.Default.Bolt
        key.contains("battery") -> Icons.Default.BatteryChargingFull
        key.contains("solar") -> Icons.Default.SolarPower
        key.contains("power") || key.contains("switch") || key.contains("button") -> Icons.Default.PowerSettingsNew

        // Appliances
        key.contains("vacuum") || key.contains("roomba") || key.contains("robot") -> Icons.Default.SmartToy
        key.contains("laundry") || key.contains("washer") || key.contains("washing") -> Icons.Default.LocalLaundryService
        key.contains("coffee") || key.contains("cafe") || key.contains("espresso") -> Icons.Default.CoffeeMaker
        key.contains("microwave") -> Icons.Default.Microwave
        key.contains("blender") -> Icons.Default.Blender
        key == "iron" || key.contains("iron_") || key.contains("_iron") -> Icons.Default.Iron
        key.contains("grill") || key.contains("bbq") || key.contains("barbecue") -> Icons.Default.OutdoorGrill
        key.contains("clean") -> Icons.Default.CleaningServices

        // Media & Audio (check tablet before table, headphone before phone)
        key.contains("headphone") -> Icons.Default.Headphones
        key.contains("tablet") || key.contains("ipad") -> Icons.Default.Tablet
        key.contains("speaker_group") || key.contains("speakers") -> Icons.Default.SpeakerGroup
        key.contains("speaker") || key.contains("sound") || key.contains("volume") -> Icons.Default.Speaker
        key.contains("live_tv") || key.contains("livetv") -> Icons.Default.LiveTv
        key.contains("tv") || key.contains("television") || key.contains("screen") || key.contains("display") -> Icons.Default.Tv
        key.contains("monitor") || key.contains("desktop") || key.contains("computer") -> Icons.Default.DesktopWindows
        key.contains("laptop") -> Icons.Default.Laptop
        key.contains("smartphone") || key.contains("mobile") || (key.contains("phone") && !key.contains("headphone")) -> Icons.Default.Smartphone
        key.contains("music") || key.contains("audio") || key.contains("song") -> Icons.Default.MusicNote
        key.contains("radio") -> Icons.Default.Radio
        key == "mic" || key.contains("microphone") -> Icons.Default.Mic
        key.contains("game") || key.contains("gamepad") || key.contains("controller") || key.contains("playstation") || key.contains("xbox") -> Icons.Default.SportsEsports

        // Rooms & Furniture (check hot_tub before tub/bath)
        key.contains("hot_tub") || key.contains("jacuzzi") -> Icons.Default.HotTub
        key.contains("sofa") || key.contains("couch") || key.contains("living") -> Icons.Default.Weekend
        key.contains("bed") || key.contains("sleep") || key.contains("bedroom") -> Icons.Default.Bed
        key.contains("chair") -> Icons.Default.Chair
        key.contains("desk") || key.contains("office") -> Icons.Default.Desk
        key == "table" || key.contains("dining") || (key.contains("table") && !key.contains("tablet")) -> Icons.Default.TableRestaurant
        key.contains("kitchen") -> Icons.Default.Kitchen
        key.contains("restaurant") -> Icons.Default.Restaurant
        key.contains("bath") || key.contains("tub") -> Icons.Default.Bathtub
        key.contains("shower") -> Icons.Default.Shower

        // Outdoor & Garden
        key.contains("balcony") || key.contains("terrace") -> Icons.Default.Balcony
        key.contains("yard") || key.contains("garden") || key.contains("plant") -> Icons.Default.Yard
        key.contains("pool") || key.contains("swimming") -> Icons.Default.Pool
        key.contains("deck") || key.contains("patio") -> Icons.Default.Deck
        key.contains("fence") || key.contains("gate") -> Icons.Default.Fence

        // Sensors & Automation
        key.contains("water") || key.contains("leak") || key.contains("moisture") || key.contains("drop") -> Icons.Default.WaterDrop
        key.contains("co2") || key.contains("smoke") || key.contains("gas") -> Icons.Default.Co2
        key.contains("timer") || key.contains("clock") -> Icons.Default.Timer
        key.contains("palette") || key.contains("scene") || key.contains("color") -> Icons.Default.Palette
        key.contains("play") || key.contains("script") -> Icons.Default.PlayArrow
        key.contains("wifi") || key.contains("router") -> Icons.Default.Wifi
        key.contains("sensor") || key.contains("motion") || key.contains("presence") -> Icons.Default.Sensors
        key.contains("pet") || key.contains("dog") || key.contains("cat") -> Icons.Default.Pets

        // Domain Fallback
        else -> when (domain) {
            "light" -> Icons.Default.Lightbulb
            "switch", "input_boolean" -> Icons.Default.PowerSettingsNew
            "scene" -> Icons.Default.Palette
            "script" -> Icons.Default.PlayArrow
            "climate" -> Icons.Default.Thermostat
            "media_player" -> Icons.Default.Tv
            "cover" -> Icons.Default.Curtains
            "fan" -> Icons.Default.Air
            "lock" -> Icons.Default.Lock
            "vacuum" -> Icons.Default.SmartToy
            "sensor", "binary_sensor" -> Icons.Default.Sensors
            else -> Icons.Default.DeviceHub
        }
    }
}
