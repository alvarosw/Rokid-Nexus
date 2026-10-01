package com.anezium.rokidbus.phone

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.anezium.rokidbus.client.ui.NexusPluginIcons
import com.anezium.rokidbus.client.ui.NexusUi
import com.anezium.rokidbus.client.ui.PluginCustomIcon
import com.anezium.rokidbus.shared.plugin.PluginCapability
import com.anezium.rokidbus.shared.tile.GridRect
import com.anezium.rokidbus.shared.tile.SystemWidgets
import com.anezium.rokidbus.shared.tile.TileGridLayout
import com.anezium.rokidbus.shared.tile.TileSize
import com.anezium.rokidbus.shared.tile.TileSnapshot

/**
 * The free-placement tile-layout editor: a preview of the glasses' home grid the wearer drags
 * tiles around in, and the size picker for the selected tile. Saving stores the layout in reading
 * order and pushes it to the glasses; the glasses resolve it with the same [TileGridLayout] this
 * screen previews with, from the same inputs, so they show what this screen shows.
 */
open class TileLayoutSettingsActivity : Activity() {
    private lateinit var state: TileLayoutEditorState
    private lateinit var canvasView: TileLayoutCanvasView
    private lateinit var gridLabel: TextView
    private lateinit var cardHost: LinearLayout
    private lateinit var saveButton: Button
    private val entryById = HashMap<String, PluginCatalogEntry?>()
    private val handler = Handler(Looper.getMainLooper())
    private val restoreSaveLabel = Runnable { saveButton.text = SAVE_LABEL }
    private var cardSignature: String? = null

    /** The size the card previews: the last size chip tapped for [previewTileId], room or not. */
    private var previewTileId: String? = null
    private var previewSize: TileSize? = null
    private val samples = HashMap<String, TileSnapshot?>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val catalog = launchableEntries().filter { it.id != null }
        val cameraName = cameraTileName()
        val tiles = buildList {
            if (cameraName != null) {
                add(EditorTile(CAMERA_TILE_ID, cameraName, TileSize.entries.toSet(), live = false))
                entryById[CAMERA_TILE_ID] = null
            }
            catalog.forEach { entry ->
                val id = entry.id.orEmpty()
                val descriptor = entry.principal?.descriptor
                add(
                    EditorTile(
                        id = id,
                        name = entry.displayName,
                        sizes = TileSizeOptions.forPlugin(descriptor?.supportedTileSizes.orEmpty()).toSet(),
                        live = descriptor?.requestedCapabilities?.contains(PluginCapability.WIDGET_TILE) == true,
                    ),
                )
                entryById[id] = entry
            }
            SystemWidgets.all.forEach { widget ->
                add(EditorTile(widget.id, widget.displayName, widget.supportedSizes.toSet(), live = false, widget = widget))
            }
        }
        state = TileLayoutEditorState.load(tiles, TileLayoutSettingsStore(this).getEntries())
        buildUi()
        onEditorChanged()
    }

    internal open fun launchableEntries(): List<PluginCatalogEntry> =
        BusHubService.pluginCatalog(this).launchableEntries

    internal open fun cameraTileName(): String? = BusHubService.cameraTileName()

    override fun onDestroy() {
        handler.removeCallbacks(restoreSaveLabel)
        super.onDestroy()
    }

    private fun dp(value: Int) = NexusUi.dp(this, value)

    /** The plugin's declared `TILE_PREVIEW` sample, read once per screen. */
    internal open fun tilePreviewSample(tileId: String): TileSnapshot? =
        samples.getOrPut(tileId) {
            entryById[tileId]?.principal?.let { PluginTilePreviewReader.read(this, it) }
        }

    internal open fun glyphFor(tileId: String): Drawable {
        val entry = entryById[tileId]
        return NexusPluginIcons.resolve(
            context = this,
            iconKey = when (tileId) {
                CAMERA_TILE_ID -> CAMERA_ICON_KEY
                else -> SystemWidgets.byId(tileId)?.iconKey ?: entry?.iconKey
            },
            customIcon = entry?.iconDrawableResId?.let { resId ->
                entry.principal?.packageName?.let { PluginCustomIcon(it, resId) }
            },
            pluginId = tileId,
        )
    }

    /** The live tile first, then the plugin's declared sample, else the header alone. */
    private fun visualFor(tile: EditorTile): TileVisual =
        TileVisual(glyphFor(tile.id), TileSnapshotCache.get(tile.id) ?: tilePreviewSample(tile.id))

    private fun buildUi() {
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG

        val reported = BusHubService.glassesHomeGridVisibleRows()
        val hudPosition = PhoneHudPositionStore(this)
        val visibleRows = TileLayoutEditorState.visibleRows(
            glassesReported = reported,
            topInsetDp = hudPosition.hudTopInsetDp(),
            autoPosition = hudPosition.hudPositionAuto(),
        )

        canvasView = TileLayoutCanvasView(this).apply {
            bind(
                state,
                state.tiles.filter { it.id in state.layout }.associate { tile -> tile.id to visualFor(tile) },
                visibleRows,
            )
            onChanged = ::onEditorChanged
        }
        gridLabel = mono("", 9.5f, 0.22f, NexusUi.GREEN_DIM)
        cardHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val preview = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                LinearLayout(this@TileLayoutSettingsActivity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(2), 0, dp(2), 0)
                    addView(
                        mono("GLASSES HUD", 9.5f, 0.22f, NexusUi.INK3),
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    addView(gridLabel)
                },
                NexusUi.block(),
            )
            addView(canvasView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
            addView(
                LinearLayout(this@TileLayoutSettingsActivity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(2), 0, dp(2), 0)
                    addView(DashedLineView(this@TileLayoutSettingsActivity), LinearLayout.LayoutParams(dp(18), dp(1)))
                    addView(
                        mono(
                            if (reported > 0) {
                                "First view on the glasses · rows below scroll"
                            } else {
                                "Estimated first view · rows below scroll"
                            },
                            10.5f,
                            0f,
                            NexusUi.INK3,
                            upper = false,
                        ),
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) },
                    )
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) },
            )
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(20))
            addView(intro(), NexusUi.block())
            addView(preview, NexusUi.block().apply { topMargin = dp(14) })
            addView(cardHost, NexusUi.block().apply { topMargin = dp(14) })
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(NexusUi.BG)
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        setContentView(
            NexusUi.fixedRoot(this).apply {
                addView(header(), NexusUi.block())
                addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                addView(footer(), NexusUi.block())
            },
        )
    }

    private fun intro(): TextView =
        NexusUi.cardBody(
            this,
            "Drag a tile to move it — the others slide into the nearest space that fits. " +
                "Tap a tile to change its size.",
        ).apply {
            val target = android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_SP, 13f * 1.55f, resources.displayMetrics,
            )
            setLineSpacing(target - paint.getFontMetricsInt(null), 1f)
        }

    private fun mono(
        value: String,
        sizeSp: Float,
        tracking: Float,
        color: Int,
        upper: Boolean = true,
    ): TextView =
        TextView(this).apply {
            text = if (upper) value.uppercase() else value
            textSize = sizeSp
            typeface = Typeface.MONOSPACE
            letterSpacing = tracking
            setTextColor(color)
            includeFontPadding = false
        }

    /** Re-renders everything that mirrors the editor state, after any change to it. */
    private fun onEditorChanged() {
        gridLabel.text = "4 cols · ${state.gridRows()} rows".uppercase()
        handler.removeCallbacks(restoreSaveLabel)
        saveButton.text = SAVE_LABEL
        renderCard()
    }

    private fun renderCard() {
        val id = state.selectedId
        val rect = id?.let { state.layout[it] }
        if (id != previewTileId) {
            previewTileId = id
            previewSize = rect?.let { TileLayoutEditorState.sizeOf(it) }
        }
        val signature = "$id|$rect|${state.message}|$previewSize"
        if (signature == cardSignature) return
        cardSignature = signature
        cardHost.removeAllViews()
        val tile = state.tiles.firstOrNull { it.id == id }
        if (tile == null || rect == null) {
            cardHost.addView(
                TextView(this).apply {
                    text = "Tap a tile to pick its size."
                    textSize = 13f
                    setTextColor(NexusUi.INK2)
                    background = NexusUi.bordered(this@TileLayoutSettingsActivity, Color.TRANSPARENT, EMPTY_BORDER, 13, 1, 4, 3)
                    setPadding(dp(14), dp(16), dp(14), dp(16))
                },
                NexusUi.block(),
            )
            return
        }
        cardHost.addView(selectedCard(tile, rect), NexusUi.block())
    }

    private fun selectedCard(tile: EditorTile, rect: GridRect): LinearLayout {
        val supported = TileSize.PICKER_ORDER.count { it in tile.sizes }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            contentDescription = "Selected tile"
            background = NexusUi.bordered(this@TileLayoutSettingsActivity, NexusUi.PANEL, NexusUi.LINE2, 15)
            setPadding(dp(15), dp(14), dp(15), dp(14))
            addView(
                LinearLayout(this@TileLayoutSettingsActivity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        FrameLayout(this@TileLayoutSettingsActivity).apply {
                            background = NexusUi.rounded(this@TileLayoutSettingsActivity, NexusUi.alpha(NexusUi.GREEN, 0x12), 8)
                            addView(
                                ImageView(this@TileLayoutSettingsActivity).apply {
                                    setImageDrawable(
                                        glyphFor(tile.id).mutate().apply { colorFilter = PorterDuffColorFilter(NexusUi.GREEN, PorterDuff.Mode.SRC_IN) },
                                    )
                                },
                                FrameLayout.LayoutParams(dp(15), dp(15), Gravity.CENTER),
                            )
                        },
                        LinearLayout.LayoutParams(dp(30), dp(30)),
                    )
                    addView(
                        LinearLayout(this@TileLayoutSettingsActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(
                                TextView(this@TileLayoutSettingsActivity).apply {
                                    text = tile.name
                                    textSize = 15f
                                    typeface = Typeface.SANS_SERIF
                                    setTextColor(NexusUi.INK)
                                    includeFontPadding = false
                                    maxLines = 1
                                    ellipsize = android.text.TextUtils.TruncateAt.END
                                },
                            )
                            addView(
                                mono(
                                    "${TileLayoutEditorState.sizeLabel(rect)} · col ${rect.col + 1} · row ${rect.row + 1}",
                                    10.5f, 0f, NexusUi.INK3, upper = false,
                                ),
                                NexusUi.block().apply { topMargin = dp(4) },
                            )
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                            marginStart = dp(12)
                            marginEnd = dp(12)
                        },
                    )
                    addView(
                        mono(if (tile.live) "LIVE" else "APP", 9.5f, 0.12f, NexusUi.GREEN).apply {
                            background = NexusUi.bordered(
                                this@TileLayoutSettingsActivity, Color.TRANSPARENT, NexusUi.alpha(NexusUi.GREEN, 0x61), 10,
                            )
                            setPadding(dp(8), dp(4), dp(8), dp(4))
                        },
                    )
                },
                NexusUi.block(),
            )
            addView(
                LinearLayout(this@TileLayoutSettingsActivity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        mono("TILE SIZE", 9.5f, 0.22f, NexusUi.INK3),
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    addView(mono("$supported of ${TileSize.PICKER_ORDER.size} supported", 9.5f, 0.22f, NexusUi.GREEN_DIM))
                },
                NexusUi.block().apply { topMargin = dp(14) },
            )
            addView(sizeGrid(tile, rect), NexusUi.block().apply { topMargin = dp(10) })
            if (state.message.isNotEmpty()) {
                addView(
                    mono(state.message, 10.5f, 0f, NexusUi.AMBER, upper = false).apply {
                        setLineSpacing(0f, 1.2f)
                        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                    },
                    NexusUi.block().apply { topMargin = dp(10) },
                )
            }
            val size = previewSize ?: TileLayoutEditorState.sizeOf(rect)
            addView(
                mono("PREVIEW · ${TileLayoutEditorState.sizeLabel(size)}", 9.5f, 0.22f, NexusUi.INK3),
                NexusUi.block().apply { topMargin = dp(14) },
            )
            addView(
                TileSizePreviewView(this@TileLayoutSettingsActivity).apply {
                    // The live tile first, then the plugin's declared sample, else the header alone.
                    bind(
                        tile.name,
                        glyphFor(tile.id),
                        TileSnapshotCache.get(tile.id) ?: tilePreviewSample(tile.id),
                        size,
                    )
                },
                NexusUi.block().apply { topMargin = dp(10) },
            )
        }
    }

    private fun sizeGrid(tile: EditorTile, rect: GridRect): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            TileSize.PICKER_ORDER.chunked(4).forEachIndexed { rowIndex, sizes ->
                addView(
                    LinearLayout(this@TileLayoutSettingsActivity).apply {
                        sizes.forEachIndexed { index, size ->
                            addView(
                                sizeChip(tile, size, on = size.cols == rect.cols && size.rows == rect.rows),
                                LinearLayout.LayoutParams(0, dp(56), 1f).apply { if (index > 0) marginStart = dp(8) },
                            )
                        }
                        repeat(4 - sizes.size) {
                            addView(View(this@TileLayoutSettingsActivity), LinearLayout.LayoutParams(0, dp(56), 1f).apply { marginStart = dp(8) })
                        }
                    },
                    NexusUi.block().apply { if (rowIndex > 0) topMargin = dp(8) },
                )
            }
        }

    private fun sizeChip(tile: EditorTile, size: TileSize, on: Boolean): LinearLayout {
        val ok = size in tile.sizes
        val label = TileLayoutEditorState.sizeLabel(size)
        val ink = when {
            on -> NexusUi.ON_ACCENT
            ok -> NexusUi.GREEN
            else -> NexusUi.INK4
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = when {
                on -> NexusUi.bordered(this@TileLayoutSettingsActivity, NexusUi.GREEN, NexusUi.GREEN, 12)
                ok -> NexusUi.bordered(
                    this@TileLayoutSettingsActivity, NexusUi.alpha(NexusUi.GREEN, 0x0A), NexusUi.alpha(NexusUi.GREEN, 0x38), 12,
                )
                else -> NexusUi.bordered(this@TileLayoutSettingsActivity, Color.TRANSPARENT, CHIP_DISABLED_BORDER, 12, 1, 3, 3)
            }
            isEnabled = ok
            isClickable = ok
            isFocusable = ok
            isSelected = on
            contentDescription = if (ok) label else "$label, not supported by ${tile.name}"
            addView(
                SizeIconView(
                    this@TileLayoutSettingsActivity,
                    size,
                    filled = ink,
                    empty = if (on) NexusUi.alpha(NexusUi.ON_ACCENT, 0x2E) else NexusUi.alpha(NexusUi.INK2, 0x24),
                ),
                LinearLayout.LayoutParams(dp(22), dp(22)),
            )
            addView(
                mono(label, 11f, 0f, ink, upper = false),
                NexusUi.block().apply { topMargin = dp(6) },
            )
            (getChildAt(1) as TextView).gravity = Gravity.CENTER
            if (ok) {
                setOnClickListener {
                    state.select(tile.id)
                    previewTileId = tile.id
                    previewSize = size
                    state.resize(size)
                    canvasView.stateChanged()
                    onEditorChanged()
                }
            }
        }
    }

    private fun header(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                LinearLayout(this@TileLayoutSettingsActivity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(10), dp(12), dp(18), dp(12))
                    addView(backButton())
                    addView(
                        NexusUi.metaLabel(this@TileLayoutSettingsActivity, "TILE LAYOUT", NexusUi.INK).apply {
                            textSize = 12f
                            letterSpacing = 0.2f
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4) },
                    )
                    addView(
                        mono("RESET", 10.5f, 0.16f, NexusUi.INK2).apply {
                            gravity = Gravity.CENTER
                            setPadding(dp(10), 0, dp(10), 0)
                            background = NexusUi.pressed(this@TileLayoutSettingsActivity, Color.TRANSPARENT, 10)
                            isClickable = true
                            isFocusable = true
                            setOnClickListener {
                                state.reset()
                                canvasView.stateChanged()
                                onEditorChanged()
                            }
                        },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)),
                    )
                },
                NexusUi.block(),
            )
            addView(
                View(this@TileLayoutSettingsActivity).apply { setBackgroundColor(NexusUi.LINE) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)),
            )
        }

    private fun footer(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                View(this@TileLayoutSettingsActivity).apply { setBackgroundColor(NexusUi.LINE) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)),
            )
            addView(
                LinearLayout(this@TileLayoutSettingsActivity).apply {
                    setPadding(dp(20), dp(11), dp(20), dp(22))
                    addView(
                        footerButton("AUTO-PACK", filled = false).apply {
                            setOnClickListener {
                                state.autoPack()
                                canvasView.stateChanged()
                                onEditorChanged()
                            }
                        },
                        LinearLayout.LayoutParams(0, dp(46), 1f),
                    )
                    saveButton = footerButton(SAVE_LABEL, filled = true).apply {
                        setOnClickListener { save() }
                    }
                    addView(saveButton, LinearLayout.LayoutParams(0, dp(46), 1.4f).apply { marginStart = dp(10) })
                },
                NexusUi.block(),
            )
        }

    private fun footerButton(label: String, filled: Boolean): Button =
        Button(this).apply {
            text = label
            textSize = 11f
            typeface = Typeface.create(Typeface.MONOSPACE, if (filled) Typeface.BOLD else Typeface.NORMAL)
            letterSpacing = 0.1f
            setAllCaps(false)
            stateListAnimator = null
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
            if (filled) {
                setTextColor(NexusUi.ON_ACCENT)
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_pressed), NexusUi.rounded(this@TileLayoutSettingsActivity, NexusUi.GREEN_DIM, 13))
                    addState(intArrayOf(), NexusUi.rounded(this@TileLayoutSettingsActivity, NexusUi.GREEN, 13))
                }
            } else {
                setTextColor(NexusUi.GREEN)
                background = StateListDrawable().apply {
                    addState(
                        intArrayOf(android.R.attr.state_pressed),
                        NexusUi.bordered(this@TileLayoutSettingsActivity, NexusUi.alpha(NexusUi.GREEN, 0x18), NexusUi.alpha(NexusUi.GREEN, 0x60), 13),
                    )
                    addState(
                        intArrayOf(),
                        NexusUi.bordered(this@TileLayoutSettingsActivity, NexusUi.alpha(NexusUi.GREEN, 0x0A), NexusUi.alpha(NexusUi.GREEN, 0x38), 13),
                    )
                }
            }
        }

    private fun save() {
        TileLayoutSettingsStore(this).setEntries(state.toEntries())
        BusHubService.onTileLayoutSettingChanged()
        saveButton.text = if (BusHubService.isGlassesLinkUp()) SENT_LABEL else SAVED_OFFLINE_LABEL
        handler.removeCallbacks(restoreSaveLabel)
        handler.postDelayed(restoreSaveLabel, SAVED_LABEL_MS)
    }

    private fun backButton(): View =
        object : View(this) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                color = NexusUi.INK
            }

            override fun onDraw(canvas: Canvas) {
                val unit = dp(20) / 24f
                paint.strokeWidth = 1.8f * unit
                val path = Path().apply {
                    moveTo(15f * unit, 5f * unit)
                    lineTo(8f * unit, 12f * unit)
                    lineTo(15f * unit, 19f * unit)
                }
                canvas.translate((width - dp(20)) / 2f, (height - dp(20)) / 2f)
                canvas.drawPath(path, paint)
            }
        }.apply {
            contentDescription = "Back"
            background = NexusUi.pressed(this@TileLayoutSettingsActivity, Color.TRANSPARENT, 22)
            isClickable = true
            isFocusable = true
            setOnClickListener { finish() }
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        }

    /** The 3x3 cell icon of a size chip: [size]'s cells lit, the rest of the 3x3 dim. */
    private class SizeIconView(
        context: Context,
        private val size: TileSize,
        private val filled: Int,
        private val empty: Int,
    ) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            val cell = 6f * resources.displayMetrics.density
            val gap = 2f * resources.displayMetrics.density
            for (row in 0 until 3) {
                for (col in 0 until 3) {
                    paint.color = if (col < size.cols && row < size.rows) filled else empty
                    val x = col * (cell + gap)
                    val y = row * (cell + gap)
                    canvas.drawRoundRect(x, y, x + cell, y + cell, gap / 2f, gap / 2f, paint)
                }
            }
        }
    }

    private class DashedLineView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = NexusUi.GREEN_DIM
            strokeWidth = resources.displayMetrics.density
        }

        override fun onDraw(canvas: Canvas) {
            val unit = resources.displayMetrics.density
            paint.pathEffect = DashPathEffect(floatArrayOf(3f * unit, 3f * unit), 0f)
            canvas.drawLine(0f, height / 2f, width.toFloat(), height / 2f, paint)
        }
    }

    private companion object {
        /** The glasses launcher's id for the camera tile: `GlassesHub.CAMERA_LAUNCHER_ID`. */
        const val CAMERA_TILE_ID = "camera"
        const val CAMERA_ICON_KEY = "lens"
        const val SAVE_LABEL = "SAVE LAYOUT"
        const val SENT_LABEL = "SENT TO GLASSES"
        const val SAVED_OFFLINE_LABEL = "SAVED · SYNCS ON CONNECT"
        const val SAVED_LABEL_MS = 1800L
        val EMPTY_BORDER = 0xFF2C4A37.toInt()
        val CHIP_DISABLED_BORDER = 0xFF1F3325.toInt()
    }
}
