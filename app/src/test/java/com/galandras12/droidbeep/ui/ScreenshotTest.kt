package com.galandras12.droidbeep.ui

import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.galandras12.droidbeep.BeeApp
import com.galandras12.droidbeep.core.*
import com.galandras12.droidbeep.data.ThemeMode
import com.galandras12.droidbeep.ui.screens.*
import com.galandras12.droidbeep.ui.theme.BeeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi", application = BeeApp::class)
class ScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private class Host(val storage0: MemoryStorage) : EngineHost {
        override val storage: EngineStorage = storage0
        override fun openInput(uri: String, skip: Long) = null
        override fun cacheDir() = File(System.getProperty("java.io.tmpdir"), "shot-cache").also { it.mkdirs() }
        override fun downloadDir() = File(System.getProperty("java.io.tmpdir"), "shot-dl").also { it.mkdirs() }
        override fun emit(event: EngineEvent) {}
    }

    private fun engine(): BeeEngine {
        val st = MemoryStorage()
        val now = Instant.now()
        fun peer(name: String, status: Int, online: Boolean, color: String, desc: String = "") =
            Peer("${name.lowercase()}@pc", name, name.lowercase(), "pc", status = status, online = online, color = color, statusDescription = desc,
                host = "192.168.1.${name.length + 10}", port = 6475, version = "5.9.2", qtVersion = "5", protocolVersion = 96, vcard = VCard(firstName = name, lastName = "Doe", email = "${name.lowercase()}@example.org"))
        val anna = peer("Anna", UserStatus.Online, true, "#C2185B")
        val bela = peer("Béla", UserStatus.Busy, true, "#1565C0", "In a meeting")
        val csaba = peer("Csaba", UserStatus.Away, true, "#2E7D32")
        val dora = peer("Dóra", UserStatus.Offline, false, "#6A1B9A")
        st.savePeers(listOf(anna, bela, csaba, dora))
        val pc = { p: Peer -> Chat("u:${p.key}", ChatType.PRIVATE, p.name, "", setOf(p.key), lastActivity = now.minusSeconds(60), lastPreview = "See you at the stand-up", unread = if (p == bela) 2 else 0) }
        val group = Chat("g:abc", ChatType.GROUP, "Project Bee", "abc", setOf(anna.key, bela.key), now, lastActivity = now.minusSeconds(600), lastPreview = "Anna: the build is green ✅")
        val default = Chat("default", ChatType.DEFAULT, Proto.DEFAULT_CHAT_NAME, Proto.DEFAULT_CHAT_PRIVATE_ID, lastActivity = now.minusSeconds(3000), lastPreview = "Béla: lunch at 12?", unread = 1)
        st.saveChats(listOf(default, pc(anna), pc(bela), group))
        var id = 1L
        fun m(chat: String, from: String?, text: String, ago: Long, kind: MsgKind = MsgKind.TEXT, d: Delivery = Delivery.NONE, file: FileRef? = null) =
            ChatMessage(id++, chat, from, text, now.minus(ago, ChronoUnit.MINUTES), kind, d, file = file)
        st.saveMessages("u:${anna.key}", listOf(
            m("u:${anna.key}", null, "Dinner tonight?", 130, d = Delivery.READ),
            m("u:${anna.key}", anna.key, "Sure! Where? I was thinking about the new place near the office 🍕", 125),
            m("u:${anna.key}", null, "Perfect — I'll book a table for 7. Here is the menu:", 120, d = Delivery.READ),
            m("u:${anna.key}", null, "menu.pdf", 119, MsgKind.FILE, file = FileRef(1, "menu.pdf", 482_000, TransferState.COMPLETED, true)),
            m("u:${anna.key}", anna.key, "Receiving the new designs", 30, MsgKind.FILE, file = FileRef(2, "designs-v3.zip", 18_400_000, TransferState.TRANSFERRING, false, 7_200_000)),
            m("u:${anna.key}", anna.key, "design-review.docx", 12, MsgKind.FILE, file = FileRef(3, "design-review.docx", 91_000, TransferState.OFFERED, false)),
            m("u:${anna.key}", null, "https://www.beebeep.net has the desktop client", 10, d = Delivery.DELIVERED),
            m("u:${anna.key}", null, "Anna is buzzing you.", 9, MsgKind.BUZZ),
            m("u:${anna.key}", null, "val bee = BeeBeep()", 4, d = Delivery.SENT),
        ).mapIndexed { i, msg -> if (i == 8) msg.copy(sourceCode = true) else msg })
        st.saveMessages("default", listOf(m("default", bela.key, "lunch at 12?", 50), m("default", null, "Béla is online.", 55, MsgKind.SYSTEM)))
        st.saveMessages("g:abc", listOf(
            m("g:abc", null, "You have created group chat Project Bee.", 700, MsgKind.SYSTEM),
            m("g:abc", anna.key, "the build is green ✅", 10),
            m("g:abc", bela.key, "Great, merging it now", 8),
            m("g:abc", null, "Thanks both!", 5, d = Delivery.DELIVERED),
        ))
        val e = BeeEngine(Host(st), EngineConfig("Me", "me", "phone", "Pixel", Crypto.simpleHash("me"), listenerPort = 0, fileTransferPort = 0, broadcastPort = 37011, useMulticast = false))
        e.start()
        return e
    }

    private fun shot(name: String, content: @androidx.compose.runtime.Composable (BeeApp, BeeEngine) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<BeeApp>()
        app.prefs.update { it.copy(nickname = "Me", accountName = "me", theme = ThemeMode.LIGHT, dynamicColor = false) }
        val e = engine()
        rule.setContent { BeeTheme(ThemeMode.LIGHT, false) { Surface { content(app, e) } } }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/shots/$name.png")
        e.stop()
    }

    @Test fun chats() = shot("chats") { _, e -> ChatsScreen(e, {}, {}) }
    @Test fun people() = shot("people") { _, e -> PeopleScreen(e, {}, {}) }
    @Test fun chatPrivate() = shot("chat_private") { _, e -> ChatScreen(e, "u:anna@pc", {}, {}, {}) }
    @Test fun chatGroup() = shot("chat_group") { _, e -> ChatScreen(e, "g:abc", {}, {}, {}) }
    @Test fun settings() = shot("settings") { a, e -> SettingsScreen(a, e, {}, {}) }
    @Test fun transfers() = shot("transfers") { _, e -> TransfersScreen(e) }
    @Test fun profile() = shot("profile") { _, e -> PeerScreen(e, "béla@pc", {}, {}) }
    @Test fun group() = shot("group_new") { _, e -> GroupScreen(e, null, {}, {}) }
}
