package com.ioniq.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ioniq.ui.theme.IoniqBg
import com.ioniq.ui.theme.IoniqBorder
import com.ioniq.ui.theme.IoniqCyan
import com.ioniq.ui.theme.IoniqSurface
import com.ioniq.ui.theme.JetBrainsMono
import kotlin.math.cos
import kotlin.math.sin

// ─────────────────────────── IoniqCard ───────────────────────────
// Level-1 cockpit surface: #112240 fill, 1px #1D3557 stroke, 20dp corners.

@Composable
fun IoniqCard(
    modifier: Modifier = Modifier,
    contentPadding: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout.PaddingValues(16.dp),
    containerColor: Color = IoniqSurface,
    borderColor: Color = IoniqBorder,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(containerColor)
            .border(1.dp, borderColor, RoundedCornerShape(20.dp))
            .padding(contentPadding)
    ) { content() }
}

// ─────────────────────────── Status pill ───────────────────────────

@Composable
fun StatusPill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    pulsing: Boolean = false
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.35f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = color
            )
            Spacer(Modifier.width(5.dp))
        } else {
            val alpha = if (pulsing) {
                val t = rememberInfiniteTransition(label = "pill")
                val a by t.animateFloat(
                    initialValue = 0.35f, targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
                    label = "alpha"
                )
                a
            } else 1f
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = alpha))
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
    }
}

// ─────────────────────────── Section header ───────────────────────────

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(4.dp).clip(CircleShape).background(IoniqCyan))
        Spacer(Modifier.width(8.dp))
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = ChipLabel,
            letterSpacing = 2.sp,
            modifier = Modifier.weight(1f)
        )
        trailing?.invoke()
    }
}

// ─────────────────────────── Metric tile ───────────────────────────
// Label + big monospace value + accent bar along the base.

@Composable
fun MetricTile(
    label: String,
    value: String,
    unit: String = "",
    tint: Color,
    modifier: Modifier = Modifier,
    fraction: Float? = null
) {
    IoniqCard(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp)
    ) {
        Column {
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = ChipLabel,
                letterSpacing = 1.5.sp
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    value,
                    fontFamily = JetBrainsMono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    color = ChipValue
                )
                if (unit.isNotEmpty()) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        unit,
                        style = MaterialTheme.typography.labelSmall,
                        color = ChipNeutral,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            // Accent bar (fraction of track lit)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(CircleShape)
                    .background(IoniqBorder)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction?.coerceIn(0.02f, 1f) ?: 0.35f)
                        .height(3.dp)
                        .clip(CircleShape)
                        .background(tint)
                )
            }
        }
    }
}

// ─────────────────────────── SOC ring gauge ───────────────────────────

@Composable
fun SocRing(
    soc: Float?,
    ringColor: Color,
    modifier: Modifier = Modifier,
    size: Int = 200
) {
    val fraction = ((soc ?: 0f) / 100f).coerceIn(0f, 1f)
    Box(modifier = modifier.size(size.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = size * 0.065f
            val r = (this.size.minDimension - stroke) / 2f
            val center = Offset(this.size.width / 2f, this.size.height / 2f)
            // Track
            drawCircle(
                color = IoniqBorder,
                radius = r,
                center = center,
                style = Stroke(width = stroke)
            )
            // Glow underlay
            drawArc(
                color = ringColor.copy(alpha = 0.25f),
                startAngle = -90f,
                sweepAngle = 360f * fraction,
                useCenter = false,
                topLeft = Offset(center.x - r, center.y - r),
                size = androidx.compose.ui.geometry.Size(r * 2f, r * 2f),
                style = Stroke(width = stroke + 14f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            )
            // Progress arc
            drawArc(
                color = ringColor,
                startAngle = -90f,
                sweepAngle = 360f * fraction,
                useCenter = false,
                topLeft = Offset(center.x - r, center.y - r),
                size = androidx.compose.ui.geometry.Size(r * 2f, r * 2f),
                style = Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "${(soc?.toInt() ?: 0)}%",
                fontFamily = JetBrainsMono,
                fontWeight = FontWeight.Bold,
                fontSize = (size * 0.22f).sp,
                color = ringColor
            )
            Text(
                "STATE OF CHARGE",
                style = MaterialTheme.typography.labelSmall,
                color = ChipLabel,
                letterSpacing = 2.sp
            )
        }
    }
}

// ─────────────────────────── Cell balance bar ───────────────────────────
// Segmented equilibrium visualizer between min and max cell voltage.

@Composable
fun CellBalanceBar(
    minMv: Int?,
    maxMv: Int?,
    modifier: Modifier = Modifier,
    segments: Int = 32
) {
    Row(
        modifier = modifier.fillMaxWidth().height(10.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        val lo = minMv ?: 0
        val hi = maxMv ?: 0
        for (i in 0 until segments) {
            val t = if (segments <= 1) 0f else i / (segments - 1f)
            val mv = if (minMv != null && maxMv != null) {
                (lo + (hi - lo) * t).toInt()
            } else null
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clip(RoundedCornerShape(2.dp))
                    .background(cellColor(mv).copy(alpha = if (mv == null) 0.25f else 1f))
            )
        }
    }
}

// ─────────────────────────── Radar scan visualizer ───────────────────────────

@Composable
fun RadarSweep(
    modifier: Modifier = Modifier,
    size: Int = 220
) {
    val transition = rememberInfiniteTransition(label = "radar")
    val angle by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing)),
        label = "sweep"
    )
    // Blip positions (angle°, radius fraction)
    val blips = listOf(35f to 0.62f, 140f to 0.45f, 250f to 0.75f, 310f to 0.35f)

    Box(modifier = modifier.size(size.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val c = Offset(this.size.width / 2f, this.size.height / 2f)
            val rMax = this.size.minDimension / 2f - 4f

            // Concentric rings
            for (f in listOf(1f, 0.66f, 0.33f)) {
                drawCircle(
                    color = IoniqBorder.copy(alpha = 0.9f),
                    radius = rMax * f,
                    center = c,
                    style = Stroke(width = 1.5f)
                )
            }
            // Cross hairs
            drawLine(IoniqBorder.copy(alpha = 0.6f), Offset(c.x - rMax, c.y), Offset(c.x + rMax, c.y), 1f)
            drawLine(IoniqBorder.copy(alpha = 0.6f), Offset(c.x, c.y - rMax), Offset(c.x, c.y + rMax), 1f)

            // Rotating sweep beam
            rotate(degrees = angle, pivot = c) {
                drawArc(
                    brush = Brush.sweepGradient(
                        0.0f to IoniqCyan.copy(alpha = 0.0f),
                        0.85f to IoniqCyan.copy(alpha = 0.0f),
                        1.0f to IoniqCyan.copy(alpha = 0.55f)
                    ),
                    startAngle = -60f,
                    sweepAngle = 60f,
                    useCenter = true
                )
                drawLine(
                    color = IoniqCyan.copy(alpha = 0.9f),
                    start = c,
                    end = Offset(c.x + rMax, c.y),
                    strokeWidth = 2.5f
                )
            }

            // Detected blips
            blips.forEach { (aDeg, rFrac) ->
                val rad = Math.toRadians(aDeg.toDouble()).toFloat()
                val p = Offset(c.x + rMax * rFrac * cos(rad), c.y + rMax * rFrac * sin(rad))
                drawCircle(IoniqCyan.copy(alpha = 0.35f), radius = 10f, center = p)
                drawCircle(IoniqCyan, radius = 4f, center = p)
            }
            // Hub
            drawCircle(IoniqCyan.copy(alpha = 0.25f), radius = 14f, center = c)
            drawCircle(IoniqCyan, radius = 6f, center = c)
        }
    }
}

// ─────────────────────────── Energy progress row ───────────────────────────

@Composable
fun EnergyRow(
    label: String,
    value: String,
    tint: Color,
    fraction: Float
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = ChipLabel, letterSpacing = 1.5.sp)
            Text(
                value,
                fontFamily = JetBrainsMono,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                color = ChipValue
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape)
                .background(IoniqBorder)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(tint)
            )
        }
    }
}

// ─────────────────────────── Icon button pill (header gear) ───────────────────────────

@Composable
fun IconPillButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(IoniqSurface.copy(alpha = 0.8f))
            .border(1.dp, IoniqBorder, CircleShape)
            .size(40.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = ChipValue,
            modifier = Modifier.size(20.dp)
        )
    }
}
