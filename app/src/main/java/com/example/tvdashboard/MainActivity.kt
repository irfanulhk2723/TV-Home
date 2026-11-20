package com.example.tvdashboard

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------------------
// Activity
// ---------------------------------------------------------------------------

class MainActivity : ComponentActivity() {

    // Bumped on every resume so newly installed / removed apps show up.
    private var refreshKey by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // Acting as a home screen: Back should not exit to a blank screen.
            BackHandler(enabled = true) { }
            HomeScreen(refreshKey = refreshKey, onLaunch = ::launch)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshKey++
    }

    private fun launch(app: TvApp) {
        try {
            startActivity(Intent(app.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
        } catch (e: SecurityException) {
        }
    }
}

// ---------------------------------------------------------------------------
// Data
// ---------------------------------------------------------------------------

data class TvApp(
    val key: String,
    val label: String,
    val intent: Intent,
    val banner: ImageBitmap?,
    val icon: ImageBitmap?,
    val accent: Color,
)

object AppRepository {

    fun load(context: Context): List<TvApp> {
        val pm = context.packageManager
        val found = LinkedHashMap<String, TvApp>()
        val queries = listOf(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
        )

        for (query in queries) {
            for (ri in pm.queryIntentActivities(query, 0)) {
                val ai = ri.activityInfo ?: continue
                val pkg = ai.packageName
                if (pkg == context.packageName || found.containsKey(pkg)) continue

                val launchIntent = pm.getLeanbackLaunchIntentForPackage(pkg)
                    ?: pm.getLaunchIntentForPackage(pkg)
                    ?: continue

                val bannerDrawable = ai.loadBanner(pm) ?: ai.applicationInfo?.loadBanner(pm)
                val iconBitmap = toBitmapSafe(ri.loadIcon(pm), 160, 160)
                val bannerBitmap = bannerDrawable?.let { toBitmapSafe(it, 480, 270) }

                found[pkg] = TvApp(
                    key = pkg,
                    label = ri.loadLabel(pm).toString(),
                    intent = launchIntent,
                    banner = bannerBitmap?.asImageBitmap(),
                    icon = iconBitmap?.asImageBitmap(),
                    accent = accentOf(bannerBitmap ?: iconBitmap),
                )
            }
        }

        val apps = found.values
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
            .toMutableList()

        // Always keep a way into system Settings.
        apps += TvApp(
            key = "system.settings",
            label = "Settings",
            intent = Intent(Settings.ACTION_SETTINGS),
            banner = null,
            icon = null,
            accent = Color(0xFF3A3A3C),
        )
        return apps
    }

    private fun toBitmapSafe(d: Drawable, w: Int, h: Int): Bitmap? =
        try {
            d.toBitmap(w, h, Bitmap.Config.ARGB_8888)
        } catch (e: Exception) {
            null
        }

    private fun accentOf(b: Bitmap?): Color {
        if (b == null) return Color(0xFF2C2C2E)
        return try {
            val px = Bitmap.createScaledBitmap(b, 1, 1, true).getPixel(0, 0)
            Color(px).copy(alpha = 1f)
        } catch (e: Exception) {
            Color(0xFF2C2C2E)
        }
    }
}

// ---------------------------------------------------------------------------
// UI
// ---------------------------------------------------------------------------

private val Background = Color(0xFF0A0A0C)
private val TileShape = RoundedCornerShape(16.dp)

@Composable
fun HomeScreen(refreshKey: Int, onLaunch: (TvApp) -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<TvApp>?>(null) }
    var focused by remember { mutableStateOf<TvApp?>(null) }

    LaunchedEffect(refreshKey) {
        apps = withContext(Dispatchers.Default) {
            AppRepository.load(context.applicationContext)
        }
    }

    // Ambient background colour follows the focused app, like a "top shelf".
    val accent by animateColorAsState(
        targetValue = focused?.accent ?: Color(0xFF1C1C1E),
        animationSpec = tween(700),
        label = "accent",
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(Background)
            .background(
                Brush.verticalGradient(
                    0f to accent.copy(alpha = 0.50f),
                    0.55f to Background,
                    1f to Background,
                )
            )
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 56.dp, vertical = 32.dp)
        ) {
            TopBar()
            Spacer(Modifier.height(12.dp))
            Hero(
                app = focused,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
            )
            Spacer(Modifier.height(16.dp))

            val list = apps
            if (list == null) {
                Text("Loading…", color = Color.White.copy(alpha = 0.6f), fontSize = 18.sp)
            } else {
                AppGrid(
                    apps = list,
                    onFocus = { focused = it },
                    onLaunch = onLaunch,
                )
            }
        }
    }
}

@Composable
private fun TopBar() {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Home",
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.weight(1f))
        Clock()
    }
}

@Composable
private fun Clock() {
    val format = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    var now by remember { mutableStateOf(format.format(Date())) }
    LaunchedEffect(Unit) {
        while (true) {
            now = format.format(Date())
            delay(1_000)
        }
    }
    Text(now, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun Hero(app: TvApp?, modifier: Modifier) {
    Crossfade(
        targetState = app,
        modifier = modifier,
        animationSpec = tween(450),
        label = "hero",
    ) { current ->
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    current?.label ?: "Welcome",
                    color = Color.White,
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (current == null) "Use the remote to browse your apps" else "Press OK to open",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 18.sp,
                )
            }
            val banner = current?.banner
            if (banner != null) {
                Image(
                    bitmap = banner,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .height(190.dp)
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(20.dp)),
                )
            }
        }
    }
}

@Composable
private fun AppGrid(
    apps: List<TvApp>,
    onFocus: (TvApp) -> Unit,
    onLaunch: (TvApp) -> Unit,
) {
    val firstTile = remember { FocusRequester() }
    var initialFocusDone by remember { mutableStateOf(false) }

    LazyVerticalGrid(
        columns = GridCells.Fixed(5),
        // Padding leaves room for the zoomed tile + shadow so nothing is clipped.
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp),
        verticalArrangement = Arrangement.spacedBy(40.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(apps, key = { _, app -> app.key }) { index, app ->
            AppTile(
                app = app,
                modifier = if (index == 0) Modifier.focusRequester(firstTile) else Modifier,
                onFocus = { onFocus(app) },
                onClick = { onLaunch(app) },
            )
        }
    }

    LaunchedEffect(apps.isNotEmpty()) {
        if (!initialFocusDone && apps.isNotEmpty()) {
            delay(150)
            runCatching { firstTile.requestFocus() }
            initialFocusDone = true
        }
    }
}

@Composable
private fun AppTile(
    app: TvApp,
    modifier: Modifier,
    onFocus: () -> Unit,
    onClick: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }

    // Springy zoom = the "Apple TV" feel.
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.10f else 1f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow),
        label = "scale",
    )
    val elevation by animateDpAsState(
        targetValue = if (isFocused) 28.dp else 0.dp,
        animationSpec = tween(250),
        label = "elevation",
    )
    val focusAlpha by animateFloatAsState(
        targetValue = if (isFocused) 1f else 0f,
        animationSpec = tween(200),
        label = "focusAlpha",
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .shadow(
                    elevation = elevation,
                    shape = TileShape,
                    clip = false,
                    ambientColor = Color.Black,
                    spotColor = Color.Black,
                )
                .clip(TileShape)
                .background(
                    Brush.linearGradient(
                        listOf(app.accent, lerp(app.accent, Color.Black, 0.45f))
                    )
                )
                .onFocusChanged {
                    isFocused = it.isFocused
                    if (it.isFocused) onFocus()
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            TileArt(app)
            // Soft glossy sheen on the focused tile.
            Box(
                Modifier
                    .matchParentSize()
                    .alpha(focusAlpha)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = 0.22f), Color.Transparent)
                        )
                    )
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            app.label,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.alpha(focusAlpha),
        )
    }
}

@Composable
private fun BoxScope.TileArt(app: TvApp) {
    val banner = app.banner
    val icon = app.icon
    when {
        banner != null -> Image(
            bitmap = banner,
            contentDescription = app.label,
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize(),
        )
        icon != null -> Image(
            bitmap = icon,
            contentDescription = app.label,
            modifier = Modifier.size(72.dp),
        )
        else -> Text(
            app.label.take(1).uppercase(Locale.getDefault()),
            color = Color.White,
            fontSize = 44.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
