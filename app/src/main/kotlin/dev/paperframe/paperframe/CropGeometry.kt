package dev.paperframe.paperframe

import org.opencv.core.Point
import org.opencv.core.Size
import kotlin.math.*

/** Size policy recovered from getWidthHeight/getTargetPointsForPerspectiveTransform. */
object CropGeometry {
    fun targetSize(points: List<Point>): Size {
        require(points.size == 4)
        fun length(a: Int, b: Int) = hypot(points[a].x - points[b].x, points[a].y - points[b].y)
        val top = length(0, 1); val bottom = length(2, 3)
        val left = length(0, 3); val right = length(1, 2)
        val minWidth = min(top, bottom); val minHeight = min(left, right)
        val maxWidth = max(top, bottom); val maxHeight = max(left, right)
        require(minWidth > 1 && minHeight > 1)
        val ratio = minWidth / minHeight
        var width: Int; var height: Int
        if (abs(top - bottom) <= abs(left - right)) {
            height = maxHeight.toInt(); width = (ratio * height + .5).toInt()
        } else {
            width = maxWidth.toInt(); height = (width / ratio + .5).toInt()
        }
        width = (width + 3) and -4; height = (height + 3) and -4
        if (width / maxWidth > 2.5 || height / maxHeight > 2.5) {
            width = ((top + bottom) * .5).toInt(); height = ((left + right) * .5).toInt()
        }
        return Size(width.toDouble(), height.toDouble())
    }
}
