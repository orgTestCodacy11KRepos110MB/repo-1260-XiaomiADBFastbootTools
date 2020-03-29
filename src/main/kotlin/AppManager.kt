import javafx.collections.ObservableList
import javafx.scene.control.ProgressBar
import javafx.scene.control.ProgressIndicator
import javafx.scene.control.TableView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.util.*


object AppManager : Command() {

    lateinit var uninstallerTableView: TableView<App>
    lateinit var reinstallerTableView: TableView<App>
    lateinit var disablerTableView: TableView<App>
    lateinit var enablerTableView: TableView<App>
    lateinit var progress: ProgressBar
    lateinit var progressInd: ProgressIndicator
    var user = 0
    private val apps: List<String>? = try {
        URL("https://raw.githubusercontent.com/Szaki/XiaomiADBFastbootTools/master/src/main/resources/apps.yml").readText()
            .trim().lines()
    } catch (ex: Exception) {
        this::class.java.classLoader.getResource("apps.yml")?.readText()?.trim()?.lines()
    }
    val customApps = File(MainController.dir, "apps.yml")
    private val potentialApps = mutableMapOf<String, String>()

    fun readPotentialApps() {
        potentialApps.clear()
        potentialApps["android.autoinstalls.config.Xiaomi.${Device.codename}"] = "PAI"
        apps?.forEach { line ->
            val app = line.split(':')
            potentialApps[app[0].trim()] = app[1].trim()
        }
        if (customApps.exists())
            customApps.forEachLine { line ->
                val app = line.split(':')
                if (app.size == 1) {
                    potentialApps[app[0].trim()] = app[0].trim()
                } else {
                    potentialApps[app[0].trim()] = app[1].trim()
                }
            }
    }

    fun createTables() {
        uninstallerTableView.items.clear()
        reinstallerTableView.items.clear()
        disablerTableView.items.clear()
        enablerTableView.items.clear()
        val uninstallApps = mutableMapOf<String, MutableList<String>>()
        val reinstallApps = mutableMapOf<String, MutableList<String>>()
        val disableApps = mutableMapOf<String, MutableList<String>>()
        val enableApps = mutableMapOf<String, MutableList<String>>()
        val deviceApps = mutableMapOf<String, String>()
        exec("adb shell pm list packages -u --user $user").trim().lines().forEach {
            deviceApps[it.substringAfter(':').trim()] = "uninstalled"
        }
        exec("adb shell pm list packages -d --user $user").trim().lines().forEach {
            deviceApps[it.substringAfter(':').trim()] = "disabled"
        }
        exec("adb shell pm list packages -e --user $user").trim().lines().forEach {
            deviceApps[it.substringAfter(':').trim()] = "enabled"
        }
        potentialApps.forEach { (pkg, name) ->
            when (deviceApps[pkg]) {
                "disabled" -> {
                    uninstallApps.add(name, pkg)
                    enableApps.add(name, pkg)
                }
                "enabled" -> {
                    uninstallApps.add(name, pkg)
                    disableApps.add(name, pkg)
                }
                "uninstalled" -> reinstallApps.add(name, pkg)
            }
        }
        uninstallerTableView.items.addAll(uninstallApps.toSortedMap().map { App(it.key, it.value) })
        reinstallerTableView.items.addAll(reinstallApps.toSortedMap().map { App(it.key, it.value) })
        disablerTableView.items.addAll(disableApps.toSortedMap().map { App(it.key, it.value) })
        enablerTableView.items.addAll(enableApps.toSortedMap().map { App(it.key, it.value) })
        uninstallerTableView.refresh()
        reinstallerTableView.refresh()
        disablerTableView.refresh()
        enablerTableView.refresh()
    }

    suspend inline fun uninstall(selected: ObservableList<App>, n: Int, crossinline func: () -> Unit) {
        pb.redirectErrorStream(false)
        withContext(Dispatchers.Main) {
            outputTextArea.text = ""
            progress.progress = 0.0
            progressInd.isVisible = true
        }
        selected.forEach {
            it.packagenameProperty().get().trim().lines().forEach { pkg ->
                val sb = StringBuilder()
                Scanner(proc.inputStream, "UTF-8").useDelimiter("").use { scanner ->
                    proc =
                        pb.command("${prefix}adb", "shell", "pm", "uninstall", "--user", "$user", pkg.trim())
                            .start()
                    while (scanner.hasNextLine())
                        sb.append(scanner.nextLine() + '\n')
                }
                withContext(Dispatchers.Main) {
                    outputTextArea.apply {
                        appendText("App: ${it.appnameProperty().get()}\n")
                        appendText("Package: $pkg\n")
                        appendText("Result: $sb\n")
                    }
                    progress.progress += 1.0 / n
                }
            }
        }
        withContext(Dispatchers.Main) {
            outputTextArea.appendText("Done!")
            progress.progress = 0.0
            progressInd.isVisible = false
            createTables()
            func()
        }
    }

    suspend inline fun reinstall(selected: ObservableList<App>, n: Int, crossinline func: () -> Unit) {
        pb.redirectErrorStream(false)
        withContext(Dispatchers.Main) {
            outputTextArea.text = ""
            progress.progress = 0.0
            progressInd.isVisible = true
        }
        selected.forEach {
            it.packagenameProperty().get().trim().lines().forEach { pkg ->
                val sb = StringBuilder()
                Scanner(proc.inputStream, "UTF-8").useDelimiter("").use { scanner ->
                    proc =
                        pb.command(
                            "${prefix}adb",
                            "shell",
                            "cmd",
                            "package",
                            "install-existing",
                            "--user",
                            "$user",
                            pkg.trim()
                        )
                            .start()
                    while (scanner.hasNextLine())
                        sb.append(scanner.nextLine() + '\n')
                }
                var output = sb.toString()
                output = if ("installed for user" in output)
                    "Success\n"
                else "Failure [${output.substringAfter(pkg).trim()}]\n"
                withContext(Dispatchers.Main) {
                    outputTextArea.apply {
                        appendText("App: ${it.appnameProperty().get()}\n")
                        appendText("Package: $pkg\n")
                        appendText("Result: $output\n")
                    }
                    progress.progress += 1.0 / n
                }
            }
        }
        withContext(Dispatchers.Main) {
            outputTextArea.appendText("Done!")
            progress.progress = 0.0
            progressInd.isVisible = false
            createTables()
            func()
        }
    }

    suspend inline fun disable(selected: ObservableList<App>, n: Int, crossinline func: () -> Unit) {
        pb.redirectErrorStream(false)
        withContext(Dispatchers.Main) {
            outputTextArea.text = ""
            progress.progress = 0.0
            progressInd.isVisible = true
        }
        selected.forEach {
            it.packagenameProperty().get().trim().lines().forEach { pkg ->
                proc = pb.command(
                    "${prefix}adb",
                    "shell",
                    "pm",
                    "disable-user",
                    "--user",
                    "$user",
                    pkg.trim()
                ).start()
                val sb = StringBuilder()
                Scanner(proc.inputStream, "UTF-8").useDelimiter("").use { scanner ->
                    while (scanner.hasNextLine())
                        sb.append(scanner.nextLine() + '\n')
                }
                val output = if ("disabled-user" in sb.toString())
                    "Success\n"
                else "Failure\n"
                withContext(Dispatchers.Main) {
                    outputTextArea.apply {
                        appendText("App: ${it.appnameProperty().get()}\n")
                        appendText("Package: $pkg\n")
                        appendText("Result: $output\n")
                    }
                    progress.progress += 1.0 / n
                }
            }
        }
        withContext(Dispatchers.Main) {
            outputTextArea.appendText("Done!")
            progress.progress = 0.0
            progressInd.isVisible = false
            createTables()
            func()
        }
    }

    suspend inline fun enable(selected: ObservableList<App>, n: Int, crossinline func: () -> Unit) {
        pb.redirectErrorStream(false)
        withContext(Dispatchers.Main) {
            outputTextArea.text = ""
            progress.progress = 0.0
            progressInd.isVisible = true
        }
        selected.forEach {
            it.packagenameProperty().get().trim().lines().forEach { pkg ->
                proc =
                    pb.command("${prefix}adb", "shell", "pm", "enable", "--user", "$user", pkg.trim())
                        .start()
                val sb = StringBuilder()
                Scanner(proc.inputStream, "UTF-8").useDelimiter("").use { scanner ->
                    while (scanner.hasNextLine())
                        sb.append(scanner.nextLine() + '\n')
                }
                val output = if ("enabled" in sb.toString())
                    "Success\n"
                else "Failure\n"
                withContext(Dispatchers.Main) {
                    outputTextArea.apply {
                        appendText("App: ${it.appnameProperty().get()}\n")
                        appendText("Package: $pkg\n")
                        appendText("Result: $output\n")
                    }
                    progress.progress += 1.0 / n
                }
            }
        }
        withContext(Dispatchers.Main) {
            outputTextArea.appendText("Done!")
            progress.progress = 0.0
            progressInd.isVisible = false
            createTables()
            func()
        }
    }
}