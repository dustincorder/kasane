package cord.kasane.desktop

import cord.kasane.desktop.ui.runUi

import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.firstOrNull() == "--connect-smoke") exitProcess(runConnectSmoke())
    if (args.firstOrNull() == "--render") exitProcess(runRender(java.io.File(args.getOrElse(1) { "/tmp/kasane-render" }), args.getOrNull(2)?.toDoubleOrNull() ?: 1.0))
    if (args.firstOrNull() == "--live") exitProcess(runLive(args.drop(1)))
    if (args.firstOrNull() == "--play") exitProcess(runPlay(args.drop(1)))
    if (args.firstOrNull() == "--smoke") exitProcess(runSmoke(args.drop(1).joinToString(" ").ifBlank { "Daft Punk Around the World" }))
    runUi()
}
