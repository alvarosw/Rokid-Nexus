package com.anezium.rokidbus.phone

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusUi
import com.anezium.rokidbus.shared.tile.TileLayoutEntry
import com.anezium.rokidbus.shared.tile.TileGridPacker
import com.anezium.rokidbus.shared.tile.TileSize

/**
 * The ordered-list tile-layout editor: Delivery 4's override of Delivery 1's auto-pack default.
 *
 * Per the roadmap's option 1 (recommended): position is implied by list order, not by a
 * drag-and-drop canvas — reordering a row moves it in the list, and the glasses re-run
 * [TileGridPacker] over that order on next sync. No collision UI is needed because the packer
 * already guarantees no overlap by construction.
 */
class TileLayoutSettingsActivity : Activity() {
    private data class Row(
        val pluginId: String,
        val displayName: String,
        val availableSizes: List<TileSize>,
        var selectedSize: TileSize,
    )

    private val rows = mutableListOf<Row>()
    private lateinit var rowsColumn: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rows += buildInitialRows()
        buildUi()
    }

    /**
     * Initial order: the stored layout's order for plugins it already placed, then every other
     * currently-launchable plugin appended in the catalog's own order — the same "unplaced
     * plugin lands after the custom-ordered ones" rule the glasses apply when merging a synced
     * layout against the live launcher list.
     */
    private fun buildInitialRows(): List<Row> {
        val catalog = BusHubService.pluginCatalog(this).launchableEntries
        val availableSizesById = catalog.associate { entry ->
            entry.id.orEmpty() to TileSizeOptions.forPlugin(
                entry.principal?.descriptor?.supportedTileSizes.orEmpty(),
            )
        }
        val storedById = TileLayoutSettingsStore(this).getEntries().associateBy { it.pluginId }
        val storedOrder = storedById.keys.filter { it in availableSizesById }
        val remaining = catalog.map { it.id.orEmpty() }.filter { it !in storedById }
        return (storedOrder + remaining).mapNotNull { pluginId ->
            val entry = catalog.firstOrNull { it.id == pluginId } ?: return@mapNotNull null
            val available = availableSizesById[pluginId] ?: return@mapNotNull null
            val stored = storedById[pluginId]?.size?.takeIf { it in available }
            Row(
                pluginId = pluginId,
                displayName = entry.displayName,
                availableSizes = available,
                selectedSize = stored ?: available.first(),
            )
        }
    }

    private fun buildUi() {
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG

        rowsColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        renderRows()

        val content = NexusUi.contentColumn(this).apply {
            addView(
                NexusUi.cardBody(
                    this@TileLayoutSettingsActivity,
                    "Order and size the plugins that show up in the grid launcher. New plugins " +
                        "appear at the end until placed.",
                ),
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@TileLayoutSettingsActivity, 18))
            addView(rowsColumn, NexusUi.block())
            addView(BusTheme.gap(this@TileLayoutSettingsActivity, 18))
            addView(
                NexusUi.pillButton(this@TileLayoutSettingsActivity, "Save layout").apply {
                    setOnClickListener { save() }
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }

        val scroll = ScrollView(this).apply {
            setBackgroundColor(NexusUi.BG)
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(
                content,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }

        setContentView(
            NexusUi.fixedRoot(this).apply {
                addView(titleHeader("TILE LAYOUT"), NexusUi.block())
                addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            },
        )
    }

    private fun renderRows() {
        rowsColumn.removeAllViews()
        rows.forEachIndexed { index, row ->
            if (index > 0) rowsColumn.addView(BusTheme.gap(this, 10))
            rowsColumn.addView(pluginRow(row, index), NexusUi.block())
        }
    }

    private fun pluginRow(row: Row, index: Int): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = NexusUi.bordered(this@TileLayoutSettingsActivity, NexusUi.PANEL, NexusUi.LINE, 15)
            setPadding(
                NexusUi.dp(this@TileLayoutSettingsActivity, 15),
                NexusUi.dp(this@TileLayoutSettingsActivity, 12),
                NexusUi.dp(this@TileLayoutSettingsActivity, 15),
                NexusUi.dp(this@TileLayoutSettingsActivity, 12),
            )
            addView(
                LinearLayout(this@TileLayoutSettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        NexusUi.rowTitle(this@TileLayoutSettingsActivity, row.displayName),
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    addView(
                        reorderButton("▲", enabled = index > 0) { move(index, index - 1) },
                    )
                    addView(
                        reorderButton("▼", enabled = index < rows.size - 1) { move(index, index + 1) },
                    )
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            addView(BusTheme.gap(this@TileLayoutSettingsActivity, 8))
            addView(
                LinearLayout(this@TileLayoutSettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    row.availableSizes.forEach { size ->
                        if (childCount > 0) addView(BusTheme.gap(this@TileLayoutSettingsActivity, 8))
                        addView(sizeChip(row, size))
                    }
                },
            )
        }

    private fun reorderButton(label: String, enabled: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            isEnabled = enabled
            setTextColor(if (enabled) NexusUi.INK else NexusUi.INK4)
            isClickable = enabled
            isFocusable = enabled
            if (enabled) {
                background = NexusUi.pressed(this@TileLayoutSettingsActivity, Color.TRANSPARENT, 18)
                setOnClickListener { onClick() }
            }
            layoutParams = LinearLayout.LayoutParams(
                NexusUi.dp(this@TileLayoutSettingsActivity, 36),
                NexusUi.dp(this@TileLayoutSettingsActivity, 36),
            ).apply { marginStart = NexusUi.dp(this@TileLayoutSettingsActivity, 4) }
        }

    private fun sizeChip(row: Row, size: TileSize): Button {
        val selected = row.selectedSize == size
        return Button(this).apply {
            text = size.wireValue
            textSize = 11f
            setAllCaps(false)
            stateListAnimator = null
            minHeight = NexusUi.dp(this@TileLayoutSettingsActivity, 34)
            minimumHeight = NexusUi.dp(this@TileLayoutSettingsActivity, 34)
            minWidth = 0
            minimumWidth = 0
            includeFontPadding = false
            setPadding(
                NexusUi.dp(this@TileLayoutSettingsActivity, 12),
                0,
                NexusUi.dp(this@TileLayoutSettingsActivity, 12),
                0,
            )
            setTextColor(if (selected) NexusUi.ON_ACCENT else NexusUi.GREEN)
            background = if (selected) {
                NexusUi.rounded(this@TileLayoutSettingsActivity, NexusUi.GREEN, 12)
            } else {
                StateListDrawable().apply {
                    addState(
                        intArrayOf(android.R.attr.state_pressed),
                        NexusUi.bordered(this@TileLayoutSettingsActivity, NexusUi.alpha(NexusUi.GREEN, 0x18), NexusUi.alpha(NexusUi.GREEN, 0x60), 12),
                    )
                    addState(
                        intArrayOf(),
                        NexusUi.bordered(this@TileLayoutSettingsActivity, NexusUi.alpha(NexusUi.GREEN, 0x0A), NexusUi.alpha(NexusUi.GREEN, 0x38), 12),
                    )
                }
            }
            setOnClickListener {
                row.selectedSize = size
                renderRows()
            }
        }
    }

    private fun move(from: Int, to: Int) {
        if (to !in rows.indices) return
        val row = rows.removeAt(from)
        rows.add(to, row)
        renderRows()
    }

    private fun save() {
        val placements = TileGridPacker.pack(rows.map { it.pluginId to it.selectedSize })
        val placementByPluginId = placements.associateBy { it.pluginId }
        val entries = rows.map { row ->
            val placement = placementByPluginId[row.pluginId]
            TileLayoutEntry(
                pluginId = row.pluginId,
                size = row.selectedSize,
                col = placement?.col ?: 0,
                row = placement?.row ?: 0,
            )
        }
        TileLayoutSettingsStore(this).setEntries(entries)
        BusHubService.onTileLayoutSettingChanged()
        finish()
    }

    private fun titleHeader(title: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                LinearLayout(this@TileLayoutSettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(
                        NexusUi.dp(this@TileLayoutSettingsActivity, 10),
                        NexusUi.dp(this@TileLayoutSettingsActivity, 12),
                        NexusUi.dp(this@TileLayoutSettingsActivity, 22),
                        NexusUi.dp(this@TileLayoutSettingsActivity, 12),
                    )
                    addView(backButton())
                    addView(
                        NexusUi.metaLabel(this@TileLayoutSettingsActivity, title, NexusUi.INK).apply {
                            textSize = 12f
                            letterSpacing = 0.2f
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                },
                NexusUi.block(),
            )
            addView(
                View(this@TileLayoutSettingsActivity).apply {
                    setBackgroundColor(NexusUi.LINE)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        NexusUi.dp(this@TileLayoutSettingsActivity, 1),
                    )
                },
            )
        }

    private fun backButton(): TextView =
        TextView(this).apply {
            text = "‹"
            textSize = 26f
            includeFontPadding = false
            gravity = Gravity.CENTER
            setTextColor(NexusUi.INK)
            background = NexusUi.pressed(this@TileLayoutSettingsActivity, Color.TRANSPARENT, 22)
            isClickable = true
            isFocusable = true
            setOnClickListener { finish() }
            layoutParams = LinearLayout.LayoutParams(
                NexusUi.dp(this@TileLayoutSettingsActivity, 44),
                NexusUi.dp(this@TileLayoutSettingsActivity, 44),
            )
        }
}
