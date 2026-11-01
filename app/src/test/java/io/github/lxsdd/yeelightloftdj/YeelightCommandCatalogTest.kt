package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

class YeelightCommandCatalogTest {
    @Test fun publishedMainMethodsAreAccountedForAndHaveUniqueIds() {
        val commands = YeelightCommandCatalog.methods
        assertEquals(commands.size,commands.map { it.id }.toSet().size)
        assertTrue(listOf("get_prop", "set_power", "set_bright","set_rgb",
            "set_ct_abx", "set_hsv", "start_cf", "stop_cf", "set_scene",
            "set_music", "set_default", "bg_set_power")
            .all { id -> commands.any { it.id == id } })
        assertEquals(ImplementationStatus.PARTIAL,
            commands.single { it.id=="set_scene" }.status)
    }

    @Test fun reportedFeatureIsNotConfusedWithAppImplementation() {
        val reported = setOf("set_power","set_music","bg_set_power","set_rgb")
        val unavailable = YeelightCommandCatalog.reportedButNotImplemented(reported)
        assertEquals(setOf("set_music","bg_set_power"),unavailable.map {it.id}.toSet())
    }

    @Test fun futureFirmwareCommandsAreNotSilentlyClaimed() {
        assertEquals(setOf("set_segment_rgb"),
            YeelightCommandCatalog.unknownReported(setOf("set_power","set_segment_rgb")))
    }
}
