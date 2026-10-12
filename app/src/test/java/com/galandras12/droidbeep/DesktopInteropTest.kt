package com.galandras12.droidbeep

import com.galandras12.droidbeep.core.BeeEngine
import com.galandras12.droidbeep.core.Crypto
import com.galandras12.droidbeep.core.EngineConfig
import com.galandras12.droidbeep.core.EngineEvent
import com.galandras12.droidbeep.core.EngineHost
import com.galandras12.droidbeep.core.EngineStorage
import com.galandras12.droidbeep.core.LocalFile
import com.galandras12.droidbeep.core.MemoryStorage
import com.galandras12.droidbeep.core.UserStatus
import kotlinx.coroutines.flow.first
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * Manual interoperability test against a real BeeBEEP desktop client running on this machine
 * (port 6475). Enable with BEEBEEP_INTEROP=1. It stays connected for BEEBEEP_INTEROP_SECONDS (default 60)
 * and prints everything it receives, so that the desktop client can be driven by hand or by xdotool.
 */
class DesktopInteropTest {
    private class TestHost(val root: File) : EngineHost {
        var engine: BeeEngine? = null
        override val storage: EngineStorage = MemoryStorage()
        override fun openInput(uri: String, skip: Long): InputStream? = FileInputStream(uri).also { it.skip(skip) }
        override fun cacheDir() = File(root, "cache").also { it.mkdirs() }
        override fun downloadDir() = File(root, "downloads").also { it.mkdirs() }
        override fun emit(event: EngineEvent) {
            when (event) {
                is EngineEvent.NewMessage -> println("EVENT message in '${event.chat.name}' from ${event.sender?.displayName}: ${event.message.text}")
                is EngineEvent.FileOffer -> {
                    println("EVENT file offer ${event.message.file}")
                    engine?.transfers?.acceptOffer(event.message.localId)
                }
                is EngineEvent.FileReceived -> println("EVENT file received ${event.localPath} ${File(event.localPath).length()} bytes")
                else -> println("EVENT $event")
            }
        }
        override fun log(message: String) = println("LOG $message")
    }

    @Test
    fun talkToDesktop() {
        assumeTrue(System.getenv("BEEBEEP_INTEROP") == "1")
        val seconds = (System.getenv("BEEBEEP_INTEROP_SECONDS") ?: "60").toInt()
        val root = File(System.getProperty("java.io.tmpdir"), "droidbeep-interop").also { it.mkdirs() }
        val th = TestHost(root)
        val engine = BeeEngine(
            th,
            EngineConfig(
                nickname = "DroidTest", accountName = "droidtest", domainName = "android", localHostName = "Pixel",
                userHash = Crypto.simpleHash("droidtest-interop"), listenerPort = 16475, fileTransferPort = 16476, broadcastPort = 36999, useMulticast = false,
            )
        )
        th.engine = engine
        engine.start()
        engine.connectToAddress("127.0.0.1", 6475)
        val t0 = System.currentTimeMillis()
        while (engine.peers.value.none { it.online } && System.currentTimeMillis() - t0 < 15000) Thread.sleep(200)
        val peer = engine.peers.value.firstOrNull { it.online }
        println("PEER $peer")
        check(peer != null) { "desktop did not connect" }
        engine.setStatus(UserStatus.Online, "from Kotlin")
        Thread.sleep(1000)
        engine.sendMessage(BeeEngine.DEFAULT_CHAT_ID, "Hello everybody from Kotlin <b>& co</b>")
        engine.sendMessage(engine.openPrivateChat(peer.key), "Private hello from Kotlin: árvíztűrő tükörfúrógép 😀")
        val f = File(root, "sample.txt").also { it.writeText("file from kotlin\n".repeat(5000)) }
        Thread.sleep(500)
        engine.transfers.sendFiles(engine.openPrivateChat(peer.key), listOf(LocalFile(f.absolutePath, f.name, f.length(), f.lastModified(), "text/plain")))
        Thread.sleep(1500)
        engine.setStatus(UserStatus.Busy, "busy from Kotlin")
        engine.setVCard(com.galandras12.droidbeep.core.VCard(firstName = "Droid", lastName = "Tester", email = "droid@example.org", phone = "+36 1 234", info = "Kotlin client"))
        engine.sendBuzz(peer.key)
        val gid = engine.createGroup("Kotlin Group", setOf(peer.key))
        println("GROUP $gid")
        Thread.sleep(1500)
        if (gid != null) engine.sendMessage(gid, "Hello group members")
        engine.sendTyping(BeeEngine.DEFAULT_CHAT_ID)
        val end = System.currentTimeMillis() + seconds * 1000L
        while (System.currentTimeMillis() < end) Thread.sleep(500)
        println("CHATS " + engine.chats.value.map { "${it.id}:${it.name}:${it.members}:unread=${it.unread}" })
        engine.chats.value.forEach { c -> println("MSGS ${c.id}: " + kotlinx.coroutines.runBlocking { engine.messages(c.id).first() }.map { "[${it.kind}/${it.delivery}] ${it.senderKey}: ${it.text}" }) }
        engine.stop()
    }
}
