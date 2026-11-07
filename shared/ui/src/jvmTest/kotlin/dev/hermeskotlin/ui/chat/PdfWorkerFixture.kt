package dev.hermeskotlin.ui.chat

import java.io.DataInputStream
import java.io.DataOutputStream

/** A real child that deliberately stops making progress or returns an invalid protocol message. */
internal object PdfWorkerFixture {
    @JvmStatic
    fun main(args: Array<String>) {
        val input = DataInputStream(System.`in`)
        val output = DataOutputStream(System.out)
        val size = input.readInt()
        input.readNBytes(size)
        if (args[0] == "early-exit") return
        if (args[0] == "hang-open") Thread.sleep(60_000)
        output.writeInt(PdfWire.READY)
        output.writeInt(if (args[0] == "huge-count") Int.MAX_VALUE else 1)
        output.writeFloat(if (args[0] == "bad-aspect") Float.NaN else 0.5f)
        output.flush()
        repeat(3) { input.readInt() }
        System.err.close()
        if (args[0] == "draw") {
            output.writeInt(100)
            output.writeInt(200)
            repeat(100 * 200) { output.writeInt(0xff0000ff.toInt()) }
            output.flush()
        }
        if (args[0] == "wrong-dimensions") {
            output.writeInt(Int.MAX_VALUE)
            output.writeInt(Int.MAX_VALUE)
            output.flush()
        }
        Thread.sleep(60_000)
    }
}
