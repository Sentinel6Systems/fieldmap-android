package io.github.sentinel6systems.fieldmap

import io.github.sentinel6systems.fieldmap.data.Message
import io.github.sentinel6systems.fieldmap.data.MessageJson
import io.github.sentinel6systems.fieldmap.data.MessageRoute
import io.github.sentinel6systems.fieldmap.data.MessageRoute.Way
import io.github.sentinel6systems.fieldmap.data.MessageStatus
import io.github.sentinel6systems.fieldmap.data.MessageStore
import io.github.sentinel6systems.fieldmap.mesh.MeshCodec
import io.github.sentinel6systems.fieldmap.share.ShareJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Short text messages: which way they go, how they are kept, how they travel. */
class MessagesTest {
    private val me = "00000000-0000-0000-0000-000000000000"
    private val other = "test-phone-0002"

    private fun msg(id: String = "test-msg-0001", from: String = me, to: String = other, mine: Boolean = true, text: String = "Meet at the gate") =
        Message(id, from, "Blue", to, text, "2027-01-15T08:00:00Z", mine)

    @Test fun theServerWhenItHasThePersonThenTheMeshThenWhateverIsThere() {
        assertEquals("both have the server", listOf(Way.SERVER), MessageRoute.choose(other, serverUp = true, onServer = true, meshReady = true, meshNode = 7))
        assertEquals("the person is only on the mesh", listOf(Way.MESH_DIRECT), MessageRoute.choose(other, true, false, true, 7))
        assertEquals("the server keeps it for them, or a relay passes it on", listOf(Way.SERVER), MessageRoute.choose(other, true, false, false, null))
        assertEquals("no server: straight to their radio", listOf(Way.MESH_DIRECT), MessageRoute.choose(other, false, false, true, 7))
        assertEquals("no server, radio not known: to everybody, for a relay", listOf(Way.MESH_BROADCAST), MessageRoute.choose(other, false, false, true, null))
        assertEquals("nothing: it waits", emptyList<Way>(), MessageRoute.choose(other, false, false, false, 7))
        assertEquals("everybody: every way there is", listOf(Way.SERVER, Way.MESH_BROADCAST), MessageRoute.choose(Message.EVERYONE, true, false, true, null))
    }

    @Test fun oneRelayGoesFirstTheSameForEverybodyAndNotAlwaysTheSameOne() {
        val relays = listOf("test-phone-0001", "test-phone-0002", "test-phone-0003")
        val first = MessageRoute.relay("test-msg-0001", relays)
        assertEquals("every device works out the same one, whatever order it knows them in", first, MessageRoute.relay("test-msg-0001", relays.reversed()))
        val chosen = (1..60).map { MessageRoute.relay("test-msg-%04d".format(it), relays) }.toSet()
        assertEquals("the work is shared", relays.toSet(), chosen)
        assertNull(MessageRoute.relay("test-msg-0001", emptyList()))
    }

    @Test fun aDeliveryNoteFitsOnePacket() {
        val ids = (1..40).map { "m" + "a".repeat(12) + it }
        val bytes = MeshCodec.delivered(me, ids)
        assertTrue(bytes.size <= MeshCodec.MAX_PAYLOAD)
        val back = MeshCodec.decode(bytes)!!.delivered!!
        assertEquals(me, MeshCodec.uidText(back.to))
        assertEquals(ids.take(back.ids.size), back.ids)
        assertTrue(back.ids.size >= 10)
    }

    @Test fun messagesAreKeptOnceInConversationsAndCountedUntilRead() {
        val file = File.createTempFile("messages", ".json").apply { delete() }
        val store = MessageStore(file)
        assertTrue(store.add(msg()))
        assertFalse("the same message the other way", store.add(msg()))
        store.add(msg(id = "test-msg-0002", from = other, to = me, mine = false))
        store.add(msg(id = "test-msg-0003", from = other, to = Message.EVERYONE, mine = false))
        assertEquals(listOf(Message.EVERYONE, other), store.partners())
        assertEquals(2, store.conversation(other).size)
        assertEquals(2, store.unread())
        store.markRead(other)
        assertEquals(1, store.unread())
        store.update("test-msg-0001") { it.copy(status = MessageStatus.DELIVERED, via = "mesh") }
        val again = MessageStore(file)
        assertEquals(store.all, again.all)
        assertEquals(listOf<Message>(), again.waiting())
        assertEquals("Blue", again.nameOf(other))
        file.delete()
    }

    @Test fun whatTheServerSaysIsCheckedAsTheServerChecksIt() {
        val answer = MessageJson.fromServer("""{"seq": 12, "delivered": ["test-msg-0009"], "messages": [
            {"seq": 11, "id": "test-msg-0002", "from": "$other", "name": "Amber", "to": "$me", "text": "On my way", "time": "2027-01-15T08:00:00Z"},
            {"seq": 12, "id": "x", "from": "$other", "name": "Amber", "to": "$me", "text": "bad id", "time": "2027-01-15T08:00:00Z"}]}""")!!
        assertEquals(12L, answer.seq)
        assertEquals(listOf("test-msg-0002"), answer.messages.map { it.id })
        assertEquals("server", answer.messages[0].via)
        assertEquals(listOf("test-msg-0009"), answer.delivered)
        assertNull(MessageJson.fromServer("<html>"))
        val sent = JSONObject(MessageJson.toServer(msg()))
        assertEquals(setOf("id", "from", "name", "to", "text", "time"), sent.keys().asSequence().toSet())
    }

    @Test fun theTwoGroupsAreConversationsOfTheirOwn() {
        assertEquals(Message.PUBLIC, msg(to = Message.PUBLIC).partner)
        assertEquals(Message.PUBLIC, msg(from = "node-1a2b3c4d", to = Message.PUBLIC, mine = false).partner)
        assertEquals(Message.EVERYONE, msg(from = other, to = Message.EVERYONE, mine = false).partner)
        assertEquals(other, msg(from = other, to = me, mine = false).partner)
    }

    @Test fun theServerSaysWhoHasOurGroupMessagesAndTheStoreKeepsIt() {
        val answer = MessageJson.fromServer("""{"seq": 3, "messages": [], "delivered": [], "groupDelivered": {"test-msg-0001": ["$other", "x!"]}}""")!!
        assertEquals(mapOf("test-msg-0001" to setOf(other)), answer.group)
        val file = File.createTempFile("messages", ".json").apply { delete() }
        val store = MessageStore(file)
        store.add(msg(to = Message.EVERYONE))
        store.update("test-msg-0001") { it.copy(deliveredTo = it.deliveredTo + other + other) }
        assertEquals(setOf(other), MessageStore(file).get("test-msg-0001")?.deliveredTo)
        file.delete()
    }

    @Test fun aDeliveryNoteSaysWhoHasTheMessages() {
        val note = MeshCodec.decode(MeshCodec.delivered(me, listOf("test-msg-0001"), by = other))!!.delivered!!
        assertEquals(other, MeshCodec.uidText(note.by_))
        assertEquals(null, MeshCodec.uidText(MeshCodec.decode(MeshCodec.delivered(me, listOf("test-msg-0001")))!!.delivered!!.by_))
    }

    @Test fun aMeshMessageNotConfirmedGoesThroughTheServerToo() {
        val now = ShareJson.millis("2027-01-15T09:00:00Z")
        val sent = msg().copy(status = MessageStatus.SENT, via = "mesh")
        assertTrue(MessageRoute.retryThroughServer(sent, now))
        assertTrue("to the group too", MessageRoute.retryThroughServer(sent.copy(to = Message.EVERYONE), now))
        assertFalse("delivered", MessageRoute.retryThroughServer(sent.copy(status = MessageStatus.DELIVERED), now))
        assertFalse("through the server already", MessageRoute.retryThroughServer(sent.copy(via = "mesh+server"), now))
        assertFalse("still waiting: the normal way takes it", MessageRoute.retryThroughServer(sent.copy(status = MessageStatus.WAITING), now))
        assertFalse("the public channel is mesh only", MessageRoute.retryThroughServer(sent.copy(to = Message.PUBLIC), now))
        assertFalse("not ours", MessageRoute.retryThroughServer(sent.copy(mine = false), now))
        assertFalse("older than the server keeps", MessageRoute.retryThroughServer(sent, now + 8 * 86_400_000L))
    }

    @Test fun aTypedMessageIsOneLineAndNoLongerThanFits() {
        assertEquals("a b", Message.clean("  a\n\tb "))
        assertEquals(Message.MAX, Message.clean("x".repeat(500)).length)
        assertTrue(ShareJson.ID.matches(Message.newId()))
    }

    @Test fun overTheMeshAFullMessageFitsOnePacketAndComesBack() {
        val longest = msg(id = "m" + "a".repeat(15), text = "x".repeat(Message.MAX)).copy(name = "N".repeat(24))
        val bytes = MeshCodec.text(longest)!!
        assertTrue("${bytes.size} bytes", bytes.size <= MeshCodec.MAX_PAYLOAD)
        val back = MeshCodec.message(MeshCodec.decode(bytes)!!.text!!) { null }!!
        assertEquals(longest.copy(mine = false, status = MessageStatus.DELIVERED, via = "mesh"), back)
        val everybody = MeshCodec.message(MeshCodec.decode(MeshCodec.text(msg(to = Message.EVERYONE))!!)!!.text!!) { null }!!
        assertEquals(Message.EVERYONE, everybody.to)
    }

    @Test fun overTheMeshAMessageTooLargeLeavesOutTheNameOrDoesNotGo() {
        val emoji = "😀"
        val wide = msg(text = emoji.repeat(40)).copy(name = "N".repeat(24))         // 160 bytes of text
        val bytes = MeshCodec.text(wide)
        assertNotNull(bytes)
        val back = MeshCodec.message(MeshCodec.decode(bytes!!)!!.text!!) { "Known" }!!
        assertEquals("the name from what is known of the sender", "Known", back.name)
        assertNull("too large even without the name", MeshCodec.text(msg(text = emoji.repeat(70))))
    }
}
