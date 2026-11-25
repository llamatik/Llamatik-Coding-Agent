package com.llamatik.cli

import com.llamatik.agent.backends.BackendFactory
import com.llamatik.agent.backends.remote.FileTokenStore
import java.io.File
import kotlin.system.exitProcess

/** Held so the level setting is not garbage-collected. */
private val quietLogger: java.util.logging.Logger = java.util.logging.Logger.getLogger("com.llamatik")

fun main(args: Array<String>) {
    useLauncherStderr()
    var options = try {
        CliArgs.parse(args.toList())
    } catch (e: UsageException) {
        System.err.println("llamatik: ${e.message}")
        System.err.println("Try 'llamatik --help'.")
        exitProcess(64)
    }
    // The backend logs "Model loaded" at INFO; on a live terminal that line lands in the middle of the status line.
    if (!options.debug && !options.nativeLogs) quietLogger.level = java.util.logging.Level.WARNING
    val terminal = SystemTerminal()
    if (!options.help && !options.version) {
        options = try {
            options.withSettings(SettingsLoader.load(java.nio.file.Path.of(System.getProperty("user.home")), options.workDir, options.configFile))
        } catch (e: SettingsException) {
            terminal.error(terminal.style(Style.RED, "error: ${e.message}"))
            exitProcess(2)
        }
    }
    if (options.discover && !options.help && !options.version) {
        when (val picked = ServerPicker(terminal).pick()) {
            is ServerPicker.Result.Picked -> options = options.copy(server = picked.address, discover = false)
            is ServerPicker.Result.Failed -> { terminal.error(terminal.style(Style.RED, "error: ${picked.reason}")); exitProcess(2) }
            ServerPicker.Result.Cancelled -> exitProcess(130)
        }
    }
    val tokens = FileTokenStore(File(System.getProperty("user.home"), ".llamatik/cli.properties"))
    val app = CliApp(options, terminal) {
        BackendFactory.create(
            model = options.model, server = options.server, params = options.params, tokens = tokens,
            onWaitingForApproval = { terminal.println("Waiting for the owner of the server to approve this device…") },
        )
    }
    installInterruptHandler(app)
    exitProcess(app.run())
}

/** Ctrl-C stops the running task; with nothing running it ends the program. */
private fun installInterruptHandler(app: CliApp) {
    try {
        sun.misc.Signal.handle(sun.misc.Signal("INT")) {
            if (!app.cancelCurrent()) exitProcess(130)
        }
    } catch (_: Throwable) {
        // Signals are unavailable on this platform: Ctrl-C then terminates the process as usual.
    }
}

/**
 * The launcher hides the native library's log noise by pointing fd 2 at /dev/null and passing the real
 * stderr as the descriptor named in LLAMATIK_ERR_FD; our own errors go there.
 */
private fun useLauncherStderr() {
    val fd = System.getenv("LLAMATIK_ERR_FD")?.toIntOrNull() ?: return
    try {
        System.setErr(java.io.PrintStream(java.io.FileOutputStream("/dev/fd/$fd"), true))
    } catch (_: Exception) {
        // Not available here: errors stay on the default stderr.
    }
}
