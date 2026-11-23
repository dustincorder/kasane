package cord.kasane.desktop

import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.firstOrNull() == "--smoke") exitProcess(runSmoke(args.drop(1).joinToString(" ").ifBlank { "Daft Punk Around the World" }))
    runUi()
}
