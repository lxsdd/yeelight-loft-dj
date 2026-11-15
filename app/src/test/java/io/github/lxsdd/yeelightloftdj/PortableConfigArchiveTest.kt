package io.github.lxsdd.yeelightloftdj

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class PortableConfigArchiveTest {
    private fun fixture(): PortableBackupData = PortableBackupData(
        devices = listOf(
            PortableLampRef("lamp-1", "0x00000000001", "yeelink.light.color4", "Couchtisch"),
            PortableLampRef("lamp-2", null, "yeelink.light.color4", "Unbekannte Lampe"),
        ),
        rooms = listOf("Wohnzimmer", "Flur"),
        assignments = mapOf("lamp-1" to "Wohnzimmer"),
        groups = mapOf("Show" to setOf("lamp-1", "lamp-2")),
        order = listOf("lamp-1", "lamp-2"),
        selected = listOf("lamp-1"),
        scenes = SceneArchiveData(listOf(SceneLibrary.scenes[0]), emptyList()),
        restoreAfterStop = false,
    )

    @Test fun fullRoundTripKeepsRoomsAndSceneFrames() {
        val original = fixture()
        val encoded = PortableConfigArchive.encode(original)
        val imported = PortableConfigArchive.decode(encoded)
        assertEquals(original.devices, imported.devices)
        assertEquals(original.rooms, imported.rooms)
        assertEquals(original.assignments, imported.assignments)
        assertEquals(original.groups, imported.groups)
        assertEquals(original.order, imported.order)
        assertEquals(original.selected, imported.selected)
        assertEquals(original.restoreAfterStop, imported.restoreAfterStop)
        assertEquals(original.scenes.flows.first().frames, imported.scenes.flows.first().frames)
        assertFalse(encoded.contains("192.168."))
        assertFalse(encoded.contains("refresh_token"))
        assertFalse(encoded.contains("pending-scene-recovery"))
    }

    @Test fun repeatedEncodeIsSemanticallyEquivalent() {
        val first = PortableConfigArchive.decode(PortableConfigArchive.encode(fixture()))
        assertEquals(first, PortableConfigArchive.decode(PortableConfigArchive.encode(first)))
    }

    @Test fun ipDerivedIdsNeverBecomePortableHardwareIds() {
        assertNull(PortableConfigArchive.stableHardwareId("ip-192.168.1.9"))
        assertNull(PortableConfigArchive.stableHardwareId("192.168.1.9"))
        assertEquals("0x00000000001", PortableConfigArchive.stableHardwareId("0x00000000001"))
    }

    @Test fun rejectsInjectedIpInReadableNames() {
        val wrong = fixture().copy(devices = listOf(
            PortableLampRef("lamp-1", "0x00000000001", "color4", "192.168.1.20")
        ))
        assertThrows(IllegalArgumentException::class.java) {
            PortableConfigArchive.encode(wrong)
        }
    }

    @Test fun corruptedPayloadRejectedBeforeImport() {
        val envelope = JSONObject(PortableConfigArchive.encode(fixture()))
        envelope.put("sha256", "0".repeat(64))
        assertThrows(IllegalArgumentException::class.java) {
            PortableConfigArchive.decode(envelope.toString())
        }
    }

    @Test fun differentValidPayloadWithOldDigestRejected() {
        val envelope = JSONObject(PortableConfigArchive.encode(fixture()))
        val base = String(Base64.getDecoder().decode(envelope.getString("payloadBase64")))
        val changed = base.replace("Couchtisch", "Andere Lampe")
        assertNotEquals(base, changed)
        envelope.put("payloadBase64", Base64.getEncoder().encodeToString(changed.toByteArray()))
        assertThrows(IllegalArgumentException::class.java) {
            PortableConfigArchive.decode(envelope.toString())
        }
    }

    @Test fun rejectsUnknownFormatAndVersion() {
        val good = PortableConfigArchive.encode(fixture())
        assertThrows(IllegalArgumentException::class.java) {
            PortableConfigArchive.decode(JSONObject(good).put("format", "fake").toString())
        }
        assertThrows(IllegalArgumentException::class.java) {
            PortableConfigArchive.decode(JSONObject(good).put("version", 2).toString())
        }
    }

    @Test fun rejectsExtraFields() {
        val original = JSONObject(PortableConfigArchive.encode(fixture()))
        original.put("oauth_token", "should-not-be-here")
        assertThrows(IllegalArgumentException::class.java) {
            PortableConfigArchive.decode(original.toString())
        }
    }

    @Test fun rejectsUnknownRoomAndLampReferences() {
        assertThrows(IllegalArgumentException::class.java) {
            PortableConfigArchive.encode(fixture().copy(
                assignments = mapOf("lamp-1" to "Keller")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PortableConfigArchive.encode(fixture().copy(
                groups = mapOf("Show" to setOf("lamp-99"))))
        }
    }

    @Test fun rejectsDuplicateHardwareIdsAndLogicalIds() {
        assertThrows(IllegalArgumentException::class.java) {
            PortableConfigArchive.encode(fixture().copy(
                devices = listOf(
                    PortableLampRef("lamp-1", "0x00000000001", "color4", "A"),
                    PortableLampRef("lamp-2", "0x00000000001", "color4", "B"))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PortableConfigArchive.encode(fixture().copy(
                devices = listOf(
                    PortableLampRef("same", null, "color4", "A"),
                    PortableLampRef("same", null, "color4", "B"))))
        }
    }

    @Test fun rejectsOversizedData() {
        assertThrows(IllegalArgumentException::class.java) {
            PortableConfigArchive.decode(" ".repeat(PortableConfigArchive.maxBytes + 1))
        }
    }

    @Test fun noCredentialsOrNetworkEndpointsAreSerialized() {
        val encoded = PortableConfigArchive.encode(fixture())
        for (forbidden in listOf("192.168.", "api-de.yeelight", "password",
                "refresh_token", "client quota exceeded", "pending-scene-recovery")) {
            assertFalse(encoded.contains(forbidden))
        }
    }
}
