package io.github.schwi2807.atlas.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.schwi2807.atlas.SurveyController
import io.github.schwi2807.atlas.ui.Chip
import io.github.schwi2807.atlas.ui.Mono
import io.github.schwi2807.atlas.ui.P
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.interpolate
import org.maplibre.android.style.expressions.Expression.linear
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.match
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.expressions.Expression.zoom
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconRotate
import org.maplibre.android.style.layers.PropertyFactory.iconRotationAlignment
import org.maplibre.android.style.layers.PropertyFactory.iconSize
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textOffset
import org.maplibre.android.style.layers.PropertyFactory.textOptional
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.PropertyFactory.visibility
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.io.File

private const val CENTER_LAT = 1.8588
private const val CENTER_LON = 103.0839

/** Copy the bundled campus.pmtiles out of the APK (MapLibre reads PMTiles from a file:// URL); redo after updates. */
private fun pmtilesFile(ctx: Context): File {
    val out = File(ctx.filesDir, "campus.pmtiles")
    val stamp = File(ctx.filesDir, "campus.pmtiles.stamp")
    val installed = ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime.toString()
    if (!out.exists() || !stamp.exists() || stamp.readText() != installed) {
        ctx.assets.open("map/campus.pmtiles").use { input -> out.outputStream().use { input.copyTo(it) } }
        stamp.writeText(installed)
    }
    return out
}

private fun arrowBitmap(color: Int): Bitmap {
    val b = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
    val c = Canvas(b)
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    val path = Path().apply { moveTo(24f, 4f); lineTo(42f, 42f); lineTo(24f, 33f); lineTo(6f, 42f); close() }
    c.drawPath(path, p)
    p.style = Paint.Style.STROKE; p.strokeWidth = 3f; p.color = 0xFF0B0C0E.toInt()
    c.drawPath(path, p)
    return b
}

@Composable
fun MapScreen(survey: SurveyController) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val gnss by survey.gnss.state.collectAsState()
    val compass by survey.sensors.compass.collectAsState()
    val features by survey.db.features().all().collectAsState(initial = emptyList())
    val track by survey.db.track().all().collectAsState(initial = emptyList())
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var follow by remember { mutableStateOf(true) }
    var showCoverage by remember { mutableStateOf(true) }
    var showTrack by remember { mutableStateOf(true) }

    val mapView = remember {
        MapLibre.getInstance(ctx)
        MapView(ctx).apply {
            onCreate(null)
            getMapAsync { m ->
                m.uiSettings.isCompassEnabled = true
                m.uiSettings.isAttributionEnabled = true
                m.uiSettings.isLogoEnabled = false
                m.cameraPosition = CameraPosition.Builder().target(LatLng(CENTER_LAT, CENTER_LON)).zoom(16.0).build()
                m.setMinZoomPreference(13.5)
                val json = ctx.assets.open("basemap-dark.json").bufferedReader().readText()
                    .replace("__PMTILES__", "pmtiles://file://" + pmtilesFile(ctx).absolutePath)
                m.setStyle(Style.Builder().fromJson(json)) { s ->
                    s.addImage("arrow", arrowBitmap(0xFFFFB000.toInt()))
                    s.addSource(GeoJsonSource("coverage"))
                    s.addSource(GeoJsonSource("track"))
                    s.addSource(GeoJsonSource("features"))
                    s.addSource(GeoJsonSource("me"))
                    // coverage: every recorded fix, coloured by accuracy (amber = good, red = weak GPS zone)
                    s.addLayer(CircleLayer("coverage", "coverage").withProperties(
                        circleRadius(interpolate(linear(), zoom(), stop(14, 2f), stop(19, 9f))),
                        circleColor(interpolate(linear(), get("acc"), stop(3, literal("#FFB000")), stop(8, literal("#FF8A3D")), stop(15, literal("#FF2D2D")))),
                        circleOpacity(0.35f),
                    ))
                    s.addLayer(LineLayer("track", "track").withProperties(lineColor("#FFB000"), lineWidth(2f), lineOpacity(0.55f)))
                    s.addLayer(CircleLayer("features", "features").withFilter(org.maplibre.android.style.expressions.Expression.neq(get("kind"), literal("entrance")))
                        .withProperties(
                            circleRadius(interpolate(linear(), zoom(), stop(14, 3f), stop(19, 8f))),
                            circleColor(match(get("kind"), literal("#FFB000"), stop("building", "#FFD36B"), stop("path_note", "#8B9098"))),
                            circleStrokeColor("#0B0C0E"), circleStrokeWidth(1.5f),
                        ))
                    s.addLayer(SymbolLayer("entrances", "features").withFilter(org.maplibre.android.style.expressions.Expression.eq(get("kind"), literal("entrance")))
                        .withProperties(iconImage("arrow"), iconRotate(get("b")), iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                            iconAllowOverlap(true), iconSize(interpolate(linear(), zoom(), stop(15, 0.35f), stop(19, 0.8f)))))
                    s.addLayer(SymbolLayer("labels", "features").withProperties(
                        textField(get("label")), textFont(arrayOf("Noto Sans Medium")), textSize(11f), textColor("#E8E6E1"),
                        textHaloColor("#0B0C0E"), textHaloWidth(1.5f), textOffset(arrayOf(0f, 1.2f)), textOptional(true),
                    ))
                    s.addLayer(CircleLayer("me", "me").withProperties(
                        circleRadius(7f), circleColor("#FFFFFF"), circleStrokeColor("#FFB000"), circleStrokeWidth(3f)))
                    style = s
                }
                m.addOnCameraMoveStartedListener { reason -> if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) follow = false }
                map = m
            }
        }
    }

    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        onDispose {
            lifecycle.removeObserver(obs)
            mapView.onPause(); mapView.onStop(); mapView.onDestroy()
        }
    }

    // data -> sources
    LaunchedEffect(style, features) {
        val s = style ?: return@LaunchedEffect
        val fc = FeatureCollection.fromFeatures(features.map { f ->
            Feature.fromGeometry(Point.fromLngLat(f.lon, f.lat)).apply {
                addStringProperty("kind", f.kind)
                addStringProperty("label", (f.code?.let { "$it " } ?: "") + f.nameEn)
                addNumberProperty("b", f.bearingDeg ?: 0.0)
            }
        })
        (s.getSource("features") as? GeoJsonSource)?.setGeoJson(fc)
    }
    LaunchedEffect(style, track) {
        val s = style ?: return@LaunchedEffect
        val lines = track.groupBy { it.sessionId to it.segment }.values.filter { it.size > 1 }.map { pts ->
            Feature.fromGeometry(LineString.fromLngLats(pts.map { Point.fromLngLat(it.lon, it.lat) }))
        }
        (s.getSource("track") as? GeoJsonSource)?.setGeoJson(FeatureCollection.fromFeatures(lines))
    }
    LaunchedEffect(style) {
        while (true) { // coverage refresh: fixes table can be large, so sample it and refresh every 20 s
            val s = style ?: break
            val pts = withContext(Dispatchers.IO) {
                val all = survey.db.fixes().sample(1)
                val every = maxOf(1, all.size / 4000)
                all.filterIndexed { i, _ -> i % every == 0 }
            }
            (s.getSource("coverage") as? GeoJsonSource)?.setGeoJson(FeatureCollection.fromFeatures(pts.map {
                Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)).apply { addNumberProperty("acc", it.acc) }
            }))
            delay(20_000)
        }
    }
    LaunchedEffect(style, gnss.fix, follow) {
        val s = style ?: return@LaunchedEffect
        val f = gnss.fix ?: return@LaunchedEffect
        (s.getSource("me") as? GeoJsonSource)?.setGeoJson(Point.fromLngLat(f.longitude, f.latitude))
        if (follow) map?.animateCamera(CameraUpdateFactory.newLatLng(LatLng(f.latitude, f.longitude)), 600)
    }
    LaunchedEffect(style, showCoverage, showTrack) {
        val s = style ?: return@LaunchedEffect
        s.getLayer("coverage")?.setProperties(visibility(if (showCoverage) Property.VISIBLE else Property.NONE))
        s.getLayer("track")?.setProperties(visibility(if (showTrack) Property.VISIBLE else Property.NONE))
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView({ mapView }, Modifier.fillMaxSize())
        Column(Modifier.align(Alignment.TopStart).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("Follow", follow) { follow = !follow }
                Chip("Coverage", showCoverage) { showCoverage = !showCoverage }
                Chip("Track", showTrack) { showTrack = !showTrack }
            }
            Text(
                "±${gnss.fix?.accuracy?.let { "%.1f".format(it) } ?: "—"} m · ${gnss.used}/${gnss.visible} sats · " +
                    (compass.headingDeg?.let { "%03.0f°".format(it) } ?: "no compass"),
                fontFamily = Mono, fontSize = 12.sp, color = P.amber,
            )
        }
    }
}
