package io.github.sentinel6systems.fieldmap

import io.github.sentinel6systems.fieldmap.mesh.RadioSetup
import io.github.sentinel6systems.fieldmap.mesh.RadioState
import io.github.sentinel6systems.fieldmap.mesh.Setup
import io.github.sentinel6systems.fieldmap.share.MeshChannel
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.Config
import org.meshtastic.proto.Config.LoRaConfig.ModemPreset
import org.meshtastic.proto.Config.LoRaConfig.RegionCode
import org.meshtastic.proto.DeviceMetadata
import org.meshtastic.proto.DeviceMetrics
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.HardwareModel
import org.meshtastic.proto.LockdownStatus
import org.meshtastic.proto.MyNodeInfo
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.User

/** Setting a Meshtastic radio up for the server's mesh. */
class RadioSetupTest {
    private val key = ByteArray(32) { (it * 7).toByte() }
    private val channel = MeshChannel("FM-0a1b2c", java.util.Base64.getEncoder().encodeToString(key), "LONG_FAST", 3, 60, 600, 15, "a1b2c3d4e5f6")
    private val publicKey = byteArrayOf(1).toByteString()

    /** A radio as it comes from the factory, region set. */
    private fun factory(region: RegionCode = RegionCode.US) = RadioState(
        nodeNum = 0x1234, owner = User(id = "!00001234", long_name = "Meshtastic 1234", short_name = "1234"),
        lora = Config.LoRaConfig(region = region, use_preset = true, modem_preset = ModemPreset.LONG_FAST, hop_limit = 3, tx_enabled = true),
        bluetooth = Config.BluetoothConfig(enabled = true, mode = Config.BluetoothConfig.PairingMode.FIXED_PIN, fixed_pin = RadioSetup.FACTORY_PIN),
        channels = mapOf(0 to Channel(index = 0, role = Channel.Role.PRIMARY, settings = ChannelSettings(psk = publicKey)),
            1 to Channel(index = 1, role = Channel.Role.DISABLED)),
    )

    /** The radio after the changes were written. */
    private fun apply(s: RadioState, change: Setup.Change): RadioState = change.messages.fold(s) { r, m ->
        var n = r
        m.set_config?.lora?.let { n = n.copy(lora = it) }
        m.set_config?.bluetooth?.let { n = n.copy(bluetooth = it) }
        m.set_channel?.let { n = n.copy(channels = n.channels + (it.index to it)) }
        m.set_owner?.let { n = n.copy(owner = it) }
        n
    }

    @Test fun theFirmwaresHashAndTheDefaultSlots() {
        assertEquals(5381L, RadioSetup.hash(""))
        // US, LongFast: slot 20 of 104 (906.875 MHz), the one every factory radio there uses.
        assertEquals(20, RadioSetup.slot(RegionCode.US, ModemPreset.LONG_FAST))
        assertEquals(1, RadioSetup.slot(RegionCode.EU_868, ModemPreset.LONG_FAST), "one slot only")
        assertTrue(RadioSetup.slot(RegionCode.US, ModemPreset.MEDIUM_FAST) in 1..104)
        assertEquals(0, RadioSetup.slot(RegionCode.UNSET, ModemPreset.LONG_FAST))
    }

    @Test fun aFactoryRadioGetsTheServersChannelOnThePublicSlotAndANewPin() {
        val plan = RadioSetup.plan(factory(), channel, "Blue Team Lead") { 424242 }
        assertTrue(plan is Setup.Change)
        plan as Setup.Change
        assertEquals(424242, plan.pin)
        val lora = plan.messages.firstNotNullOf { it.set_config?.lora }
        assertEquals(20, lora.channel_num)
        assertEquals(RegionCode.US, lora.region)
        assertEquals(3, lora.hop_limit)
        val (primary, public) = plan.messages.mapNotNull { it.set_channel }
        assertEquals(Channel.Role.PRIMARY, primary.role)
        assertEquals("FM-0a1b2c", primary.settings?.name)
        assertEquals(key.toByteString(), primary.settings?.psk)
        assertEquals("the app sends its own positions", 0, primary.settings?.module_settings?.position_precision)
        assertEquals("read only: the public channel is a secondary one", Channel.Role.SECONDARY, public.role)
        assertEquals(1, public.index)
        assertEquals("", public.settings?.name)
        assertEquals(publicKey, public.settings?.psk)
        assertEquals(0, public.settings?.module_settings?.position_precision)
        assertTrue("read only: the factory name stays, not the person's", plan.messages.none { it.set_owner != null })
        val bt = plan.messages.firstNotNullOf { it.set_config?.bluetooth }
        assertEquals(Config.BluetoothConfig.PairingMode.FIXED_PIN, bt.mode)
        assertEquals(424242, bt.fixed_pin)
        // Written and read back, nothing more is needed.
        assertEquals(Setup.Ready, RadioSetup.plan(apply(factory(), plan), channel, "Blue Team Lead") { 111111 })
    }

    @Test fun otherChannelsAreSwitchedOff() {
        val s = factory().copy(channels = factory().channels + (2 to Channel(index = 2, role = Channel.Role.SECONDARY, settings = ChannelSettings(name = "x"))))
        val plan = RadioSetup.plan(s, channel, "") { 424242 } as Setup.Change
        assertEquals(listOf(0, 1, 2), plan.messages.mapNotNull { it.set_channel?.index })
        assertEquals(Channel.Role.DISABLED, plan.messages.mapNotNull { it.set_channel }.last().role)
    }

    @Test fun sharingOnThePublicChannelMakesItThePrimaryOneUnderThePersonsName() {
        val readOnly = apply(factory(), RadioSetup.plan(factory(), channel, "Blue Team Lead") { 424242 } as Setup.Change)
        val plan = RadioSetup.plan(readOnly, channel, "Blue Team Lead", readOnly = false) { 424242 } as Setup.Change
        val (first, second) = plan.messages.mapNotNull { it.set_channel }
        assertEquals(publicKey, first.settings?.psk)
        assertEquals(Channel.Role.PRIMARY, first.role)
        assertEquals("FM-0a1b2c", second.settings?.name)
        assertEquals(Channel.Role.SECONDARY, second.role)
        assertEquals(1, RadioSetup.ourIndex(readOnly = false))
        val owner = plan.messages.firstNotNullOf { it.set_owner }
        assertEquals("Blue Team Lead", owner.long_name)
        assertEquals("BTL", owner.short_name)
        assertEquals("!00001234", owner.id)
        // And back: the factory name again.
        val shared = apply(readOnly, plan)
        assertEquals(Setup.Ready, RadioSetup.plan(shared, channel, "Blue Team Lead", readOnly = false) { 1 })
        val back = RadioSetup.plan(shared, channel, "Blue Team Lead", readOnly = true) { 1 } as Setup.Change
        assertEquals("Meshtastic 1234", back.messages.firstNotNullOf { it.set_owner }.long_name)
    }

    @Test fun otherRadiosHeardAreKeptWithHowNearTheyAre() {
        val s = factory()
        val direct = s.heard(org.meshtastic.proto.MeshPacket(from = 0x77, hop_start = 3, hop_limit = 3, rx_snr = 6.5f, channel = 1), 1000)
        val far = direct.heard(org.meshtastic.proto.MeshPacket(from = 0x88, hop_start = 3, hop_limit = 1, channel = 0,
            decoded = org.meshtastic.proto.Data(portnum = org.meshtastic.proto.PortNum.NODEINFO_APP,
                payload = User.ADAPTER.encode(User(long_name = "Hilltop")).toByteString())), 1010)
        assertEquals(0, far.nodes[0x77]?.hops)
        assertEquals(6.5f, far.nodes[0x77]!!.snr, 0f)
        assertEquals("!00000077", far.nodes[0x77]?.name)
        assertEquals("Hilltop", far.nodes[0x88]?.name)
        assertEquals(2, far.nodes[0x88]?.hops)
        assertEquals(1010L, far.nodes[0x88]?.heard)
        assertEquals("its own packets are not another radio", far, far.heard(org.meshtastic.proto.MeshPacket(from = 0x1234), 2000))
    }

    @Test fun aRadioWithoutARegionWaitsForThePersonsChoice() {
        assertEquals(Setup.NeedsRegion, RadioSetup.plan(factory(RegionCode.UNSET), channel, "") { 1 })
        assertEquals(Setup.NeedsRegion, RadioSetup.plan(RadioState(nodeNum = 1), channel, "") { 1 })
        val plan = RadioSetup.plan(factory(RegionCode.UNSET), channel, "", RegionCode.US) { 424242 } as Setup.Change
        assertEquals(RegionCode.US, plan.messages.firstNotNullOf { it.set_config?.lora }.region)
    }

    @Test fun aPinThatIsNotTheFactoryOneOrARandomOneIsLeftAlone() {
        val own = factory().let { it.copy(bluetooth = it.bluetooth!!.copy(fixed_pin = 987654)) }
        val screen = factory().let { it.copy(bluetooth = it.bluetooth!!.copy(mode = Config.BluetoothConfig.PairingMode.RANDOM_PIN)) }
        for (s in listOf(own, screen)) {
            val plan = RadioSetup.plan(s, channel, "") { 424242 } as Setup.Change
            assertNull(plan.pin)
            assertTrue(plan.messages.none { it.set_config?.bluetooth != null })
        }
        val none = factory().let { it.copy(bluetooth = it.bluetooth!!.copy(mode = Config.BluetoothConfig.PairingMode.NO_PIN)) }
        assertEquals(424242, (RadioSetup.plan(none, channel, "") { 424242 } as Setup.Change).pin)
        repeat(1000) { assertNotEquals(RadioSetup.FACTORY_PIN, RadioSetup.randomPin()) }
        repeat(1000) { assertTrue(RadioSetup.randomPin() in 100000..999999) }
    }

    @Test fun aNewChannelKeyIsWrittenAgain() {
        val done = apply(factory(), RadioSetup.plan(factory(), channel, "") { 424242 } as Setup.Change)
        assertTrue(RadioSetup.hasChannel(done, channel))
        val renewed = channel.copy(psk = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 9 }), rev = "b1")
        assertFalse(RadioSetup.hasChannel(done, renewed))
        val plan = RadioSetup.plan(done, renewed, "") { 424242 } as Setup.Change
        assertEquals(listOf(0), plan.messages.mapNotNull { it.set_channel?.index })
    }

    @Test fun theRadiosAnswerIsReadIntoItsState() {
        var s = RadioState()
        for (m in listOf(
            FromRadio(my_info = MyNodeInfo(my_node_num = 0x1234)),
            FromRadio(metadata = DeviceMetadata(firmware_version = "2.8.3", hw_model = HardwareModel.HELTEC_MESH_NODE_T114)),
            FromRadio(node_info = NodeInfo(num = 0x9999, user = User(long_name = "Someone else"))),
            FromRadio(node_info = NodeInfo(num = 0x1234, user = User(long_name = "Mine"), device_metrics = DeviceMetrics(battery_level = 87))),
            FromRadio(config = Config(lora = Config.LoRaConfig(region = RegionCode.US))),
            FromRadio(channel = Channel(index = 0, role = Channel.Role.PRIMARY)),
        )) s = s.take(m)
        assertEquals(0x1234, s.nodeNum)
        assertEquals("2.8.3", s.firmware)
        assertEquals("HELTEC_MESH_NODE_T114", s.model)
        assertEquals("Mine", s.owner?.long_name)
        assertEquals(87, s.battery)
        assertEquals(RegionCode.US, s.region)
        assertEquals(setOf(0), s.channels.keys)
        assertFalse(s.locked)
        assertTrue(s.take(FromRadio(lockdown_status = LockdownStatus(state = LockdownStatus.State.LOCKED))).locked)
    }

    @Test fun whereOtherRadiosAreIsKeptRoughOrExact() {
        val s = factory().take(FromRadio(node_info = NodeInfo(num = 0x55, user = User(long_name = "Ridge"), last_heard = 100,
            position = org.meshtastic.proto.Position(latitude_i = 481_234_567, longitude_i = -1_227_654_321, precision_bits = 13))))
        val n = s.nodes[0x55]!!
        assertEquals(48.1234567, n.lat!!, 1e-7)
        assertEquals(-122.7654321, n.lon!!, 1e-7)
        assertEquals("13 bits: within about 2.9 km", 2912.5, n.radius, 0.1)
        // A packet with no position keeps the one known; a position packet replaces it.
        val later = s.heard(org.meshtastic.proto.MeshPacket(from = 0x55, hop_start = 3, hop_limit = 2), 200)
        assertEquals(48.1234567, later.nodes[0x55]!!.lat!!, 1e-7)
        val moved = later.heard(org.meshtastic.proto.MeshPacket(from = 0x55, decoded = org.meshtastic.proto.Data(portnum = org.meshtastic.proto.PortNum.POSITION_APP,
            payload = org.meshtastic.proto.Position.ADAPTER.encode(org.meshtastic.proto.Position(latitude_i = 480_000_000, longitude_i = -1_220_000_000, precision_bits = 32)).toByteString())), 300)
        assertEquals(48.0, moved.nodes[0x55]!!.lat!!, 1e-7)
        assertEquals("exact", 0.0, moved.nodes[0x55]!!.radius, 0.0)
        assertNull("no position: not on the map", factory().heard(org.meshtastic.proto.MeshPacket(from = 0x66), 1).nodes[0x66]!!.lat)
    }

    @Test fun shortNamesAreInitialsOrTheStartOfTheName() {
        assertEquals("BTL", RadioSetup.shortName("Blue Team Lead"))
        assertEquals("ALPH", RadioSetup.shortName("alpha"))
        assertEquals("FM", RadioSetup.shortName("!!"))
    }

    private fun assertEquals(expected: Int, actual: Int, why: String) = assertEquals(why, expected.toLong(), actual.toLong())
}
