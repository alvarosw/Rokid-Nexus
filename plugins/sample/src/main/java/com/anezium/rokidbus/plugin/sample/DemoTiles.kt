package com.anezium.rokidbus.plugin.sample

import com.anezium.rokidbus.shared.tile.TileContent
import com.anezium.rokidbus.shared.tile.TileSnapshot
import com.anezium.rokidbus.shared.tile.TileTone

/**
 * One demo tile per template, with the data of the live tiles design artboards, so each template
 * can be seen on the glasses grid and in the phone's size preview.
 */
internal object DemoTiles {
    val all: List<TileSnapshot> = listOf(
        TileSnapshot(
            pluginId = "hello",
            contentKey = "demo-generic",
            title = "12",
            subtitle = "Downtown bus",
            unit = "min",
            tone = TileTone.INFO,
            rows = listOf("Line 4 to Riverside  3 min", "Line 9 to Old Town  11 min", "Line 2 to Central  18 min"),
        ),
        TileSnapshot(
            pluginId = "hello",
            contentKey = "demo-music",
            content = TileContent.Music(
                title = "Low Tide Static",
                artist = "Marta Velez",
                album = "Salt Rooms",
                source = "Spotify",
                playing = true,
                positionMs = 102_000,
                durationMs = 236_000,
            ),
            tone = TileTone.OK,
        ),
        TileSnapshot(
            pluginId = "hello",
            contentKey = "demo-lines",
            content = TileContent.Lines(
                lines = listOf(
                    TileContent.Lines.Line("salt on the window, salt on the sleeve", startMs = 88_000),
                    TileContent.Lines.Line("and the radio kept the weather to itself", startMs = 95_000),
                    TileContent.Lines.Line("I counted cars until the counting stopped", startMs = 101_000),
                    TileContent.Lines.Line("you said the river doesn’t owe us anything", startMs = 108_000),
                ),
                current = 1,
                title = "Low Tide Static",
                subtitle = "Marta Velez",
                source = "LrcLib",
                positionMs = 96_000,
                durationMs = 236_000,
                playing = true,
            ),
        ),
        TileSnapshot(
            pluginId = "hello",
            contentKey = "demo-list",
            content = TileContent.ListContent(
                sections = listOf(
                    TileContent.ListContent.Section(
                        items = listOf(
                            item("Ana Ribeiro", "WhatsApp", "Leaving now, ten minutes away. Want me to grab bread on the way?", 60_000, "AR"),
                            item("Family", "WhatsApp", "Dad: dinner moved to 8, bring the speaker", 360_000, "FA"),
                            item("Tomás Prado", "Telegram", "Did the files come through?", 1_080_000, "TP"),
                        ),
                    ),
                ),
                summary = "3 new",
                summaryShort = "3",
            ),
            tone = TileTone.INFO,
        ),
    )

    private fun item(title: String, detail: String, paragraph: String, ageMs: Long, initials: String) =
        TileContent.ListContent.Item(
            title = title,
            detail = detail,
            paragraph = paragraph,
            ageMs = ageMs,
            leading = TileContent.ListContent.Leading.Initials(initials),
        )
}
