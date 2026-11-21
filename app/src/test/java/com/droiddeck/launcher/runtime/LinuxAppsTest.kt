package com.droiddeck.launcher.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.Locale

class LinuxAppsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val root by lazy { tmp.newFolder("rootfs") }

    private fun put(path: String, text: String = ""): File =
        File(root, path).apply { parentFile!!.mkdirs(); writeText(text) }

    private fun app(name: String, vararg extra: String) =
        (listOf("[Desktop Entry]", "Type=Application", "Name=$name", "Exec=$name") + extra).joinToString("\n")

    @Test fun scanKeepsWhatPlasmasMenuWouldShow() {
        put("usr/share/applications/org.kde.kate.desktop", app("Kate", "Name[de]=Kate DE", "Icon=kate", "Categories=Utility;TextEditor;"))
        put("usr/share/applications/hidden.desktop", app("Hidden", "NoDisplay=true"))
        put("usr/share/applications/gone.desktop", app("Gone", "Hidden=true"))
        put("usr/share/applications/gnome.desktop", app("Gnome", "OnlyShowIn=GNOME;"))
        put("usr/share/applications/notkde.desktop", app("NotKde", "NotShowIn=KDE;"))
        put("usr/share/applications/link.desktop", "[Desktop Entry]\nType=Link\nName=Link\nURL=https://x")
        put("usr/share/applications/tryexec.desktop", app("Missing", "TryExec=/usr/bin/missing"))
        put("usr/share/applications/droiddeck-return-android.desktop", app("Return"))
        put("usr/share/applications/kde/sub.desktop", app("Sub"))
        put("usr/share/applications/notes.txt", app("Notes"))
        put("usr/bin/present")
        put("usr/share/applications/present.desktop", app("Present", "TryExec=present", "[Desktop Action new]", "Name=Other"))

        val apps = LinuxApps.scan(root, Locale.GERMANY)
        assertEquals(listOf("Kate DE", "Present", "Sub"), apps.map { it.name })
        val kate = apps.first()
        assertEquals("org.kde.kate.desktop", kate.id)
        assertEquals("kate", kate.icon)
        assertEquals(listOf("Utility", "TextEditor"), kate.categories)
        assertEquals("kde-sub.desktop", apps.last().id)
    }

    @Test fun theUsersEntryReplacesTheSystemOne() {
        put("usr/share/applications/a.desktop", app("System"))
        put("root/.local/share/applications/a.desktop", app("Mine"))
        put("usr/local/share/applications/b.desktop", app("Shown"))
        put("root/.local/share/applications/b.desktop", app("Removed", "Hidden=true"))
        assertEquals(listOf("Mine"), LinuxApps.scan(root).map { it.name })
    }

    @Test fun flatpakExportsLinkIntoTheRuntimeNotAndroidsRoot() {
        val real = put("root/.local/share/flatpak/app/org.x.App/current/active/export/share/applications/org.x.App.desktop",
            app("Flat", "X-Flatpak=org.x.App"))
        val link = File(root, "root/.local/share/flatpak/exports/share/applications/org.x.App.desktop").apply { parentFile!!.mkdirs() }
        Files.createSymbolicLink(link.toPath(), File("/" + real.relativeTo(root).path).toPath())
        val flat = LinuxApps.scan(root).single()
        assertEquals("org.x.App", flat.flatpak)
        assertEquals(listOf(FlatpakManager.LAUNCHER, "org.x.App"), LinuxApps.command(root, flat))
    }

    @Test fun execLinesLoseTheirFieldCodesAndKeepQuoting() {
        assertEquals(listOf("kate", "-b"), LinuxApps.argv("kate -b %U"))
        assertEquals(listOf("sh", "-c", "echo \"hi there\" 100%"), LinuxApps.argv("sh -c \"echo \\\\\"hi there\\\\\" 100%%\""))
        assertEquals(listOf("env", "A=1", "app", "--file="), LinuxApps.argv("env A=1 app --file=%f %i %c"))
        assertEquals(listOf("two words", ""), LinuxApps.argv("\"two words\" \"\""))
    }

    @Test fun commandsRunFromTheGuestPathAndTerminalsOpenKonsole() {
        put("usr/bin/htop")
        put("usr/bin/konsole")
        val entry = LinuxApps.parse("htop.desktop", app("htop", "Terminal=true"), File("x"), root)!!
        assertEquals(listOf("/usr/bin/konsole", "-e", "/usr/bin/htop"), LinuxApps.command(root, entry))
        val unknown = LinuxApps.parse("u.desktop", app("unknown"), File("x"), root)!!
        assertEquals(listOf("unknown"), LinuxApps.command(root, unknown))
        assertNull(LinuxApps.parse("e.desktop", "[Desktop Entry]\nType=Application\nName=E\nExec=", File("x"), root))
    }

    @Test fun iconsPreferTheDesktopThemesAppIconsAndLargerSizes() {
        put("usr/share/icons/hicolor/16x16/apps/kate.png")
        val big = put("usr/share/icons/hicolor/256x256/apps/kate.png")
        val breeze = put("usr/share/icons/breeze/apps/48/kate.svg")
        put("usr/share/icons/breeze/actions/48/kate.svg")
        val pixmap = put("usr/share/pixmaps/legacy.xpm")
        val absolute = put("opt/app/icon.png")
        val icons = LinuxApps.iconFiles(root, setOf("kate", "legacy", "/opt/app/icon.png", "nothing"))
        assertEquals(breeze, icons.getValue("kate").first())
        assertEquals(big, icons.getValue("kate")[1])
        assertEquals(listOf(pixmap), icons["legacy"])
        assertEquals(listOf(absolute), icons["/opt/app/icon.png"])
        assertNull(icons["nothing"])
    }

    @Test fun iconLinksResolveInsideTheRuntime() {
        val target = put("usr/share/icons/hicolor/scalable/apps/real.svg")
        val link = File(root, "usr/share/icons/breeze/apps/48/linked.svg").apply { parentFile!!.mkdirs() }
        Files.createSymbolicLink(link.toPath(), File("/usr/share/icons/hicolor/scalable/apps/real.svg").toPath())
        assertEquals(target.canonicalFile, LinuxApps.iconFiles(root, setOf("linked")).getValue("linked").single().canonicalFile)
    }
}
