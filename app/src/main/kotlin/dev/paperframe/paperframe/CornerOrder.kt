package dev.paperframe.paperframe

import org.opencv.core.Point

/** Preserve corner identity across detections whose canonical start flips near 45 degrees. */
object CornerOrder {
    fun match(points: List<Point>, reference: List<Point>): List<Point> {
        if (points.size != 4 || reference.size != 4) return points
        val start = (0..3).minBy { shift ->
            (0..3).sumOf { i ->
                val p = points[(i + shift) % 4]; val q = reference[i]
                (p.x - q.x) * (p.x - q.x) + (p.y - q.y) * (p.y - q.y)
            }
        }
        return List(4) { points[(it + start) % 4] }
    }
}
