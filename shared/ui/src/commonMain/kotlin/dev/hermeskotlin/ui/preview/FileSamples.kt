package dev.hermeskotlin.ui.preview

import dev.hermeskotlin.core.media.FileText
import dev.hermeskotlin.core.media.GatewayFile
import dev.hermeskotlin.ui.chat.FileLoad

/** Files a reply linked to, as their viewer shows them. */
internal object FileSamples {

    fun of(scene: PreviewScene): Pair<GatewayFile, FileLoad> = when (scene) {
        PreviewScene.FileCode -> GatewayFile("/srv/app/server/limits.py", line = 14) to
            FileLoad.Ready(FileText(CODE, 1_870, truncated = false, binary = false, language = "python"))
        PreviewScene.FileTable -> GatewayFile("/opt/data/out/road-trip-fuel.csv") to
            FileLoad.Ready(FileText(CSV, CSV.length.toLong(), truncated = false, binary = false))
        PreviewScene.FileMissing -> GatewayFile("/opt/data/out/old-report.md") to
            FileLoad.Failed("It isn't on the gateway, or it has moved since the reply was written.")
        else -> GatewayFile("/opt/data/out/trip-plan.md") to
            FileLoad.Ready(FileText(MARKDOWN, 2_410, truncated = false, binary = false, language = "markdown"))
    }

    val pdfFile = GatewayFile("/opt/data/out/trip-budget.pdf")

    /** A two-page trip budget: a summary with a cost chart, then the fuel log. */
    val pdf: ByteArray by lazy {
        val (r, g, b) = Triple(0.20f, 0.45f, 0.85f)
        val summary = pdfPage {
            rect(0, 742, 612, 50, r, g, b)
            text(56, 760, "Weekend drive: Los Angeles to Zion", size = 18, bold = true, gray = 1f)
            text(56, 706, "Trip budget", size = 22, bold = true)
            text(56, 684, "Two people, three nights, about 860 miles there and back.", gray = 0.35f)
            line(56, 666, 556, 666)
            val costs = listOf("Fuel" to 416, "Lodging" to 540, "Food" to 310, "Park pass" to 35, "Extras" to 120)
            costs.forEachIndexed { i, (name, usd) ->
                val y = 630 - i * 34
                val width = usd * 340 / 540
                text(56, y + 6, name, size = 12)
                rect(150, y, width, 22, r, g, b)
                text(150 + width + 8, y + 6, "$$usd", size = 12, bold = true)
            }
            line(56, 450, 556, 450)
            text(56, 424, "Total", size = 14, bold = true)
            text(490, 424, "$1,421", size = 14, bold = true)
            text(56, 380, "Notes", size = 14, bold = true)
            listOf(
                "Fill up in Barstow: the stretch past Baker has few stations.",
                "Lodging is two nights in Springdale and one in Las Vegas.",
                "The park pass covers the car for seven days.",
            ).forEachIndexed { i, note -> text(68, 356 - i * 20, "- $note", gray = 0.3f) }
            text(56, 40, "Page 1 of 2", size = 9, gray = 0.5f)
        }
        val log = pdfPage {
            text(56, 730, "Fuel log", size = 22, bold = true)
            val xs = listOf(56, 156, 386, 476)
            rect(50, 686, 512, 26, 0.93f, 0.94f, 0.96f)
            listOf("Date", "Station", "Gallons", "Cost").forEachIndexed { i, heading -> text(xs[i], 695, heading, bold = true) }
            CSV.lines().drop(1).filter { it.isNotBlank() }.forEachIndexed { row, entry ->
                val cells = entry.split(',')
                val y = 662 - row * 28
                listOf(cells[0], cells[1], cells[2], "$" + cells[3]).forEachIndexed { i, cell -> text(xs[i], y, cell) }
                line(56, y - 10, 556, y - 10, gray = 0.9f)
            }
            text(56, 40, "Page 2 of 2", size = 9, gray = 0.5f)
        }
        samplePdf(listOf(summary, log))
    }

    private val MARKDOWN = """
        # Weekend drive: Los Angeles to Zion

        About **430 miles** each way. Leave Friday night to miss the I-15 traffic.

        ## Stops
        1. **Barstow**, fuel and dinner (2 h in)
        2. **Las Vegas**, overnight
        3. **St. George**, coffee and groceries
        4. **Springdale**, check in by 3 pm

        ## Packing
        - [x] Tire gauge and compressor
        - [x] Two days of water
        - [ ] Phone mount for the passenger side

        > The stretch past Baker has few gas stations. Fill up in Barstow even if the tank looks fine.

        | Leg | Distance | Time |
        |---|---:|---:|
        | Los Angeles to Barstow | 115 mi | 2 h |
        | Barstow to Las Vegas | 155 mi | 2 h 20 |
        | Las Vegas to Springdale | 160 mi | 2 h 40 |
    """.trimIndent()

    private val CODE = """
        import time
        from collections import deque


        class RateLimiter:
            ""${'"'}Sliding-window limiter: at most `limit` calls in any `window` seconds.""${'"'}

            def __init__(self, limit: int, window: float = 60.0):
                self.limit = limit
                self.window = window
                self.calls: deque[float] = deque()

            def allow(self) -> bool:
                now = time.monotonic()
                while self.calls and now - self.calls[0] > self.window:
                    self.calls.popleft()
                if len(self.calls) >= self.limit:
                    return False
                self.calls.append(now)
                return True

            def wait_time(self) -> float:
                if len(self.calls) < self.limit:
                    return 0.0
                return max(0.0, self.window - (time.monotonic() - self.calls[0]))


        limiter = RateLimiter(limit=20, window=60)  # 20 requests a minute per user
    """.trimIndent()

    private val CSV = buildString {
        appendLine("date,station,gallons,price_usd,miles,notes")
        val rows = listOf(
            "2026-10-01,Chevron Pasadena,12.4,63.12,352,\"full, highway\"",
            "2026-10-04,Shell Barstow,11.9,58.43,318,",
            "2026-10-06,Arco Las Vegas,13.1,55.93,370,before the desert stretch",
            "2026-10-07,Maverik St. George,12.0,47.00,331,",
            "2026-10-09,Sinclair Springdale,10.3,44.66,292,\"quiet, cheap coffee\"",
            "2026-10-11,Maverik St. George,12.7,49.98,350,",
            "2026-10-12,Arco Las Vegas,11.2,47.13,320,traffic",
            "2026-10-13,Chevron Pasadena,9.6,49.32,271,home",
        )
        rows.forEach { appendLine(it) }
    }
}
