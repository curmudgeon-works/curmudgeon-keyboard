// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.text.format.DateUtils
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.gesture.SwipeMetrics
import helium314.keyboard.settings.dialogs.ThreeButtonAlertDialog
import kotlin.math.ceil

/**
 * The swipe results over time: % first choice right and % never offered (left axis), the average decode time (right
 * axis), from the start of the chosen range to now. The ranges: all of the log, since the swipe tuning last changed,
 * since the tracking was last restarted.
 */
@Composable
fun SwipeResultsChartDialog(onDismissRequest: () -> Unit) {
    val log = remember { SwipeMetrics.read() }
    var range by rememberSaveable { mutableStateOf(SwipeMetrics.Range.ALL) }
    ThreeButtonAlertDialog(
        onDismissRequest = onDismissRequest,
        onConfirmed = { },
        title = { Text(stringResource(R.string.swipe_metrics)) },
        content = {
            Column {
                val labels = listOf(
                    SwipeMetrics.Range.ALL to R.string.swipe_metrics_all,
                    SwipeMetrics.Range.SINCE_TUNING to R.string.swipe_metrics_range_tuning,
                    SwipeMetrics.Range.SINCE_RESTART to R.string.swipe_metrics_range_restart,
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    labels.forEachIndexed { i, (r, label) ->
                        SegmentedButton(
                            selected = range == r,
                            onClick = { range = r },
                            shape = SegmentedButtonDefaults.itemShape(i, labels.size),
                            icon = { },
                        ) { Text(stringResource(label), style = MaterialTheme.typography.labelSmall, maxLines = 2) }
                    }
                }
                val from = SwipeMetrics.rangeStart(log, range)
                val points = if (from == null) emptyList() else SwipeMetrics.points(log, from)
                Box(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    when {
                        from == null && range == SwipeMetrics.Range.SINCE_RESTART ->
                            Text(stringResource(R.string.swipe_metrics_chart_no_restart), style = MaterialTheme.typography.bodySmall)
                        points.isEmpty() -> Text(stringResource(R.string.swipe_metrics_none), style = MaterialTheme.typography.bodySmall)
                        else -> Column {
                            SwipeResultsChart(points, from!!)
                            val ctx = LocalContext.current
                            val inRange = log.outcomes.filter { it.time >= from }
                            Text(stringResource(R.string.swipe_metrics_chart_span,
                                DateUtils.formatDateTime(ctx, from, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH),
                                inRange.size), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                            val s = SwipeMetrics.summaryOf(inRange)
                            Text(stringResource(R.string.swipe_metrics_line, s.swipes, s.pct(s.firstChoice), s.pct(s.fromStrip),
                                s.pct(s.neverOffered), s.decodeAverage, s.decodeWorst),
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        },
        scrollContent = true,
        cancelButtonText = stringResource(android.R.string.ok),
        confirmButtonText = null,
    )
}

@Composable
private fun SwipeResultsChart(points: List<SwipeMetrics.Point>, from: Long) {
    val firstColor = MaterialTheme.colorScheme.primary
    val neverColor = MaterialTheme.colorScheme.error
    val decodeColor = MaterialTheme.colorScheme.tertiary
    val axisColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = labelColor)
    val now = remember { System.currentTimeMillis() }
    val nowLabel = stringResource(R.string.swipe_metrics_chart_now)
    // the decode axis runs to a round number above the slowest point
    val maxMs = points.maxOf { it.decodeMs }.coerceAtLeast(1f)
    val msTop = (ceil(maxMs / 10f) * 10f).coerceAtLeast(10f)
    Column {
        Canvas(Modifier.fillMaxWidth().height(200.dp)) {
            val left = 34.dp.toPx()
            val right = size.width - 40.dp.toPx()
            val top = 8.dp.toPx()
            val bottom = size.height - 20.dp.toPx()
            fun x(t: Long) = left + (right - left) * ((t - from).toFloat() / (now - from).coerceAtLeast(1L))
            fun yPct(v: Float) = bottom - (bottom - top) * v / 100f
            fun yMs(v: Float) = bottom - (bottom - top) * v / msTop
            for (pct in listOf(0f, 50f, 100f)) {
                drawLine(axisColor, Offset(left, yPct(pct)), Offset(right, yPct(pct)), strokeWidth = 1.dp.toPx())
                label(measurer, "${pct.toInt()}%", labelStyle, Offset(0f, yPct(pct)), alignRight = false)
                label(measurer, "${(msTop * pct / 100f).toInt()} ms", labelStyle, Offset(right + 4.dp.toPx(), yPct(pct)), alignRight = false)
            }
            label(measurer, DateUtils.getRelativeTimeSpanString(from, now, DateUtils.MINUTE_IN_MILLIS).toString(), labelStyle,
                Offset(left, bottom + 10.dp.toPx()), alignRight = false)
            label(measurer, nowLabel, labelStyle, Offset(right, bottom + 10.dp.toPx()), alignRight = true)
            line(points.map { Offset(x(it.time), yPct(it.firstChoicePct)) }, firstColor, null)
            line(points.map { Offset(x(it.time), yPct(it.neverOfferedPct)) }, neverColor, null)
            line(points.map { Offset(x(it.time), yMs(it.decodeMs)) }, decodeColor, PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
        }
        val last = points.last()
        Legend(firstColor, stringResource(R.string.swipe_metrics_chart_first), "${last.firstChoicePct.toInt()}%")
        Legend(neverColor, stringResource(R.string.swipe_metrics_chart_never), "${last.neverOfferedPct.toInt()}%")
        Legend(decodeColor, stringResource(R.string.swipe_metrics_chart_decode), "${last.decodeMs.toInt()} ms")
    }
}

private fun DrawScope.line(offsets: List<Offset>, color: Color, effect: PathEffect?) {
    if (offsets.isEmpty()) return
    val path = Path().apply {
        moveTo(offsets[0].x, offsets[0].y)
        for (o in offsets.drop(1)) lineTo(o.x, o.y)
    }
    drawPath(path, color, style = Stroke(width = 2.dp.toPx(), pathEffect = effect))
    if (offsets.size <= 40) for (o in offsets) drawCircle(color, radius = 3.dp.toPx(), center = o)
}

private fun DrawScope.label(measurer: androidx.compose.ui.text.TextMeasurer, text: String, style: TextStyle, at: Offset, alignRight: Boolean) {
    val layout = measurer.measure(text, style)
    val x = if (alignRight) at.x - layout.size.width else at.x
    drawText(layout, topLeft = Offset(x, at.y - layout.size.height / 2f))
}

@Composable
private fun Legend(color: Color, name: String, latest: String) {
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(10.dp).background(color))
        Text(name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(latest, style = MaterialTheme.typography.bodySmall)
    }
}
