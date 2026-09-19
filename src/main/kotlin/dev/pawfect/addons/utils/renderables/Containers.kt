package dev.pawfect.addons.utils.renderables

class RowRenderable(
    private val children: List<Renderable>,
    private val spacing: Int = 0,
    private val verticalAlign: VerticalAlignment = VerticalAlignment.CENTER,
) : Renderable {

    override val width: Int =
        children.sumOf { it.width } + spacing * (children.size - 1).coerceAtLeast(0)
    override val height: Int = children.maxOfOrNull { it.height } ?: 0

    override fun render(absX: Int, absY: Int) {
        var dx = 0
        for (child in children) {
            Renderable.drawChild(child, absX, absY, dx, verticalAlign.offset(height, child.height))
            dx += child.width + spacing
        }
    }
}

class ColumnRenderable(
    private val children: List<Renderable>,
    private val spacing: Int = 1,
    private val horizontalAlign: HorizontalAlignment = HorizontalAlignment.LEFT,
) : Renderable {

    override val width: Int = children.maxOfOrNull { it.width } ?: 0
    override val height: Int =
        children.sumOf { it.height } + spacing * (children.size - 1).coerceAtLeast(0)

    override fun render(absX: Int, absY: Int) {
        var dy = 0
        for (child in children) {
            Renderable.drawChild(child, absX, absY, horizontalAlign.offset(width, child.width), dy)
            dy += child.height + spacing
        }
    }
}

class TableRenderable(
    private val rows: List<List<Renderable>>,
    private val columnSpacing: Int = 4,
    private val rowSpacing: Int = 1,
    private val columnAlignments: List<HorizontalAlignment> = emptyList(),
) : Renderable {

    private val columnCount: Int = rows.maxOfOrNull { it.size } ?: 0

    private val columnWidths: IntArray = IntArray(columnCount) { column ->
        rows.maxOfOrNull { it.getOrNull(column)?.width ?: 0 } ?: 0
    }

    private val rowHeights: List<Int> = rows.map { row -> row.maxOfOrNull { it.height } ?: 0 }

    override val width: Int =
        columnWidths.sum() + columnSpacing * (columnCount - 1).coerceAtLeast(0)
    override val height: Int =
        rowHeights.sum() + rowSpacing * (rows.size - 1).coerceAtLeast(0)

    override fun render(absX: Int, absY: Int) {
        var dy = 0
        for ((rowIndex, row) in rows.withIndex()) {
            var dx = 0
            val rowHeight = rowHeights[rowIndex]
            for (column in 0 until columnCount) {
                val cell = row.getOrNull(column)
                if (cell != null) {
                    val align = columnAlignments.getOrElse(column) { HorizontalAlignment.LEFT }
                    Renderable.drawChild(
                        cell,
                        absX,
                        absY,
                        dx + align.offset(columnWidths[column], cell.width),
                        dy + VerticalAlignment.CENTER.offset(rowHeight, cell.height),
                    )
                }
                dx += columnWidths[column] + columnSpacing
            }
            dy += rowHeight + rowSpacing
        }
    }
}
