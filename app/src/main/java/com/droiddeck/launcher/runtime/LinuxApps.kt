package com.droiddeck.launcher.runtime

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.nio.file.Files
import java.util.Locale

object LinuxApps {
    data class Entry(
        val id: String,
        val name: String,
        val exec: String,
        val icon: String?,
        val terminal: Boolean,
        val path: String?,
        val categories: List<String>,
        val flatpak: String?,
        val file: File,
    )

    // Lowest precedence first: XDG_DATA_DIRS in reverse, then XDG_DATA_HOME (runtime/GuestCommand.kt).
    internal val DATA_DIRS = listOf("usr/share", "usr/local/share", "root/.local/share/flatpak/exports/share", "root/.local/share")
    private val PATH = listOf("usr/local/sbin", "usr/local/bin", "usr/sbin", "usr/bin", "sbin", "bin")
    private val THEMES = listOf("breeze", "hicolor", "Adwaita")
    private val ICON_EXTENSIONS = listOf("png", "svg", "xpm")
    private const val DESKTOP = "KDE"
    private val INTERNAL = setOf(
        "droiddeck-return-gaming.desktop", "droiddeck-return-android.desktop", "droiddeck-ime.desktop",
        "steamdeck-bigpicture.desktop",
    )

    private val UTILITY_IDS = setOf(
        "org.kde.ark.desktop", "org.kde.drkonqi.coredump.gui.desktop", "org.kde.khelpcenter.desktop", "org.kde.kinfocenter.desktop",
        "org.kde.kdeconnect.app.desktop", "org.kde.kdeconnect.sms.desktop", "org.kde.kdeconnect-settings.desktop",
        "org.kde.kdeconnect.nonplasma.desktop", "org.kde.kdeconnect.handler.desktop", "org.kde.plasma.emojier.desktop",
        "org.kde.partitionmanager.desktop", "org.kde.kmenuedit.desktop", "org.kde.plasma-welcome.desktop",
        "org.kde.kwalletmanager.desktop", "org.kde.kwalletmanager5.desktop", "org.kde.kuserfeedback-console.desktop",
        "org.kde.plasma.themeexplorer.desktop", "org.kde.kcharselect.desktop", "org.freedesktop.IBus.Setup.desktop",
        "avahi-discover.desktop", "bssh.desktop", "bvnc.desktop", "qv4l2.desktop", "qvidcap.desktop", "lstopo.desktop",
        "foot-server.desktop", "footclient.desktop", "htop.desktop", "kdesystemsettings.desktop",
    )
    private val KEPT_SYSTEM = setOf("TerminalEmulator", "FileManager", "Monitor", "PackageManager")

    /** System utilities a launcher grid has little use for; they start hidden and can be shown again. */
    fun hiddenByDefault(entry: Entry): Boolean {
        if (entry.id in UTILITY_IDS) return true
        if (entry.id.startsWith("droiddeck-") && !entry.id.startsWith("droiddeck-app-") && !entry.id.startsWith("droiddeck-program-")) return true
        val categories = entry.categories.toSet()
        if (categories.any { it == "Settings" || it.endsWith("Settings") || it == "Documentation" }) return true
        return "System" in categories && categories.none { it in KEPT_SYSTEM }
    }

    fun scan(root: File, locale: Locale = Locale.getDefault()): List<Entry> {
        val byId = LinkedHashMap<String, Entry?>()
        for (data in DATA_DIRS) {
            val apps = File(root, "$data/applications")
            apps.walkTopDown().filter { it.name.endsWith(".desktop") }.forEach { link ->
                val file = rooted(root, link)?.takeIf { it.isFile } ?: return@forEach
                val id = link.relativeTo(apps).path.replace(File.separatorChar, '-')
                byId[id] = runCatching { parse(id, file.readText(), file, root, locale) }.getOrNull()
            }
        }
        return byId.values.filterNotNull().sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    internal fun parse(id: String, text: String, file: File, root: File?, locale: Locale = Locale.getDefault()): Entry? {
        if (id in INTERNAL) return null
        val keys = mainGroup(text)
        if (keys["Type"] != "Application") return null
        if (keys["NoDisplay"] == "true" || keys["Hidden"] == "true") return null
        keys["OnlyShowIn"]?.let { if (DESKTOP !in list(it)) return null }
        keys["NotShowIn"]?.let { if (DESKTOP in list(it)) return null }
        val exec = keys["Exec"]?.takeIf { it.isNotBlank() } ?: return null
        if (root != null) keys["TryExec"]?.let { if (resolve(root, unescape(it)) == null) return null }
        val name = localized(keys, "Name", locale) ?: return null
        return Entry(
            id, name, exec,
            keys["Icon"]?.takeIf { it.isNotBlank() },
            keys["Terminal"] == "true",
            keys["Path"]?.takeIf { it.isNotBlank() },
            keys["Categories"]?.let(::list).orEmpty(),
            keys["X-Flatpak"]?.takeIf { it.isNotBlank() },
            file,
        )
    }

    private fun mainGroup(text: String): Map<String, String> {
        val keys = HashMap<String, String>()
        var inMain = false
        for (line in text.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            if (t.startsWith("[")) { inMain = t == "[Desktop Entry]"; continue }
            if (!inMain) continue
            val eq = t.indexOf('=')
            if (eq <= 0) continue
            keys.putIfAbsent(t.substring(0, eq).trim(), t.substring(eq + 1).trim())
        }
        return keys
    }

    private fun localized(keys: Map<String, String>, key: String, locale: Locale): String? {
        val lang = locale.language
        val country = locale.country
        val candidates = listOfNotNull(
            if (country.isNotEmpty()) "$key[${lang}_$country]" else null,
            if (lang.isNotEmpty()) "$key[$lang]" else null,
            key,
        )
        return candidates.firstNotNullOfOrNull { keys[it]?.takeIf { v -> v.isNotBlank() } }?.let(::unescape)
    }

    private fun list(value: String) = value.split(';').map { it.trim() }.filter { it.isNotEmpty() }

    private fun unescape(value: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (val n = value[i + 1]) {
                    's' -> out.append(' ')
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    'r' -> out.append('\r')
                    else -> out.append(n)
                }
                i += 2
            } else {
                out.append(c); i++
            }
        }
        return out.toString()
    }

    fun argv(exec: String): List<String> {
        val args = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var started = false
        val text = unescape(exec)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '\\' && i + 1 < text.length && text[i + 1] in "\"`$\\" -> { current.append(text[i + 1]); i++ }
                c == '"' -> { quoted = !quoted; started = true }
                !quoted && c.isWhitespace() -> {
                    if (started) { args += current.toString(); current.clear(); started = false }
                }
                else -> { current.append(c); started = true }
            }
            i++
        }
        if (started) args += current.toString()
        return args.mapNotNull { arg ->
            if (arg.length == 2 && arg[0] == '%' && arg[1] != '%') return@mapNotNull null
            arg.replace(Regex("%[fFuUdDnNickvm]"), "").replace("%%", "%")
        }
    }

    fun command(root: File, entry: Entry): List<String>? {
        entry.flatpak?.let { return listOf(FlatpakManager.LAUNCHER, it) }
        val args = argv(entry.exec).toMutableList()
        if (args.isEmpty()) return null
        resolve(root, args[0])?.let { args[0] = it }
        return if (entry.terminal) listOf(resolve(root, "konsole") ?: "konsole", "-e") + args else args
    }

    fun resolve(root: File, program: String): String? {
        if (program.startsWith("/")) return program.takeIf { exists(root, it) }
        return PATH.firstOrNull { exists(root, "/$it/$program") }?.let { "/$it/$program" }
    }

    private fun exists(root: File, guestPath: String): Boolean = rooted(root, File(root, guestPath.trimStart('/')))?.isFile == true

    // Absolute link targets point into the runtime, not Android's root.
    internal fun rooted(root: File, file: File, depth: Int = 0): File? {
        if (depth > 8) return null
        val path = file.toPath()
        if (!Files.isSymbolicLink(path)) return file.takeIf { it.exists() }
        val target = runCatching { Files.readSymbolicLink(path).toString() }.getOrNull() ?: return null
        val next = if (target.startsWith("/")) File(root, target.trimStart('/')) else File(file.parentFile, target)
        return rooted(root, next.normalize(), depth + 1)
    }

    fun iconFiles(root: File, icons: Set<String>): Map<String, List<File>> {
        val found = HashMap<String, MutableList<Pair<Int, File>>>()
        fun add(icon: String, rank: Int, file: File) { rooted(root, file)?.let { found.getOrPut(icon) { mutableListOf() } += rank to it } }
        val named = icons.filterNot { it.startsWith("/") }.toSet()
        for (icon in icons - named) add(icon, 0, File(root, icon.trimStart('/')))
        for ((themeRank, theme) in THEMES.withIndex()) {
            for (data in DATA_DIRS.reversed()) {
                val dir = File(root, "$data/icons/$theme")
                if (!dir.isDirectory) continue
                dir.walkTopDown().forEach { f ->
                    val icon = f.name.substringBeforeLast('.')
                    if (icon in named && f.extension in ICON_EXTENSIONS) add(icon, score(f.relativeTo(dir).path, f.extension, themeRank), f)
                }
            }
        }
        for (data in DATA_DIRS.reversed()) for (icon in named) for (ext in ICON_EXTENSIONS) {
            val f = File(root, "$data/pixmaps/$icon.$ext")
            if (Files.exists(f.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) add(icon, -1_000_000 + extensionScore(ext), f)
        }
        return found.mapValues { (_, files) -> files.sortedByDescending { it.first }.map { it.second }.distinct() }
    }

    private fun score(relative: String, extension: String, themeRank: Int): Int {
        val app = if ("apps/" in relative || "/apps" in relative || relative.startsWith("apps")) 100_000 else 0
        val size = Regex("(\\d+)(?:x\\d+)?").findAll(relative).mapNotNull { it.groupValues[1].toIntOrNull() }.filter { it in 8..1024 }.maxOrNull()
        val sized = when {
            extension == "svg" -> 600
            size == null -> 0
            size >= 128 -> 500 + minOf(size, 512) / 8
            else -> size * 3
        }
        return app - themeRank * 10_000 + sized
    }

    private fun extensionScore(extension: String) = when (extension) { "svg" -> 2; "png" -> 1; else -> 0 }

    fun iconBitmap(context: Context, files: List<File>): Bitmap? {
        val cache = File(context.cacheDir, "linux-icons").apply { mkdirs() }
        for (source in files) {
            if (source.extension == "xpm") continue
            val key = Integer.toHexString((source.path + ":" + source.lastModified() + ":" + source.length()).hashCode())
            val png = File(cache, "$key.png")
            if (!png.isFile && !UserApps.saveIcon(source, png)) continue
            BitmapFactory.decodeFile(png.path)?.let { return it }
            png.delete()
        }
        return null
    }

    class Loaded(val root: String, val stamp: Long, val apps: List<Pair<Entry, Bitmap?>>)

    @Volatile private var last: Loaded? = null

    fun load(context: Context): List<Pair<Entry, Bitmap?>> {
        val root = LinuxRuntime.rootDir(context)
        if (!File(root, "usr/share/applications").isDirectory) return emptyList()
        val stamp = stamp(root)
        last?.takeIf { it.root == root.path && it.stamp == stamp }?.let { return it.apps }
        val entries = scan(root)
        val icons = iconFiles(root, entries.mapNotNull { it.icon }.toSet())
        val apps = entries.map { e -> e to e.icon?.let { icons[it] }?.let { iconBitmap(context, it) } }
        last = Loaded(root.path, stamp, apps)
        return apps
    }

    fun stamp(root: File): Long = DATA_DIRS.maxOf { data ->
        File(root, "$data/applications").let { dir -> if (dir.isDirectory) dir.walkTopDown().filter { it.isDirectory }.maxOf { it.lastModified() } else 0L }
    }
}
