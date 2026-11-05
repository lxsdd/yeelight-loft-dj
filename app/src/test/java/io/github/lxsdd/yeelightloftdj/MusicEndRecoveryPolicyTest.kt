package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

class MusicEndRecoveryPolicyTest {
    @Test fun focusedResponseParsesExactlyTheReportedProperties() {
        assertEquals(MusicEndStatus(true, false), MusicEndStatus.parse(listOf("on", "0")))
        assertEquals(MusicEndStatus(false, true), MusicEndStatus.parse(listOf("off", "1")))
        assertEquals(MusicEndStatus(true, null), MusicEndStatus.parse(listOf("on", "")))
        assertEquals(MusicEndStatus(null, null), MusicEndStatus.parse(listOf("","")))
        assertEquals(MusicEndStatus(null, null), MusicEndStatus.parse(emptyList()))
        assertEquals(MusicEndStatus(true, null), MusicEndStatus.parse(listOf("on", "OK")))
    }

    @Test fun explicitFirmwareZeroIsValidAfterCleanOwnStop() {
        assertEquals(MusicEndRelease.FIRMWARE_CONFIRMED,
            MusicEndRecoveryPolicy.afterOwnStop(MusicEndStatus(true,false), false, false))
    }

    @Test fun onlyOurClosedSocketAndResponsiveLampResolveUnsupportedMusicOn() {
        val unknown=MusicEndStatus(true, null)
        assertEquals(MusicEndRelease.OWN_SOCKET_CLOSED,
            MusicEndRecoveryPolicy.afterOwnStop(unknown, true, false))
        assertEquals(MusicEndRelease.UNRESOLVED,
            MusicEndRecoveryPolicy.afterOwnStop(unknown, false, false))
        assertEquals(MusicEndRelease.UNRESOLVED,
            MusicEndRecoveryPolicy.afterOwnStop(MusicEndStatus(null,null), true, false))
    }

    @Test fun stopFailureOrConfirmedActiveMusicRemainBlocked() {
        assertEquals(MusicEndRelease.UNRESOLVED,
            MusicEndRecoveryPolicy.afterOwnStop(MusicEndStatus(true,false),true,true))
        assertEquals(MusicEndRelease.UNRESOLVED,
            MusicEndRecoveryPolicy.afterOwnStop(MusicEndStatus(true,true),true,false))
        assertEquals(MusicEndRelease.UNRESOLVED,
            MusicEndRecoveryPolicy.afterOwnStop(null,true,false))
    }

    @Test fun physicalPowerCycleMustBeConfirmedAndHaveReadablePower() {
        assertEquals(MusicEndRelease.OWNER_POWER_CYCLE,
            MusicEndRecoveryPolicy.afterPhysicalPowerCycle(
                MusicEndStatus(true,null),true,true))
        assertEquals(MusicEndRelease.UNRESOLVED,
            MusicEndRecoveryPolicy.afterPhysicalPowerCycle(
                MusicEndStatus(true,null),false,true))
        assertEquals(MusicEndRelease.UNRESOLVED,
            MusicEndRecoveryPolicy.afterPhysicalPowerCycle(
                MusicEndStatus(null,null),true,true))
        assertEquals(MusicEndRelease.UNRESOLVED,
            MusicEndRecoveryPolicy.afterPhysicalPowerCycle(
                MusicEndStatus(true,null),true,false))
    }

    @Test fun ownerResetNeverOverridesPositiveMusicOn() {
        assertEquals(MusicEndRelease.UNRESOLVED,
            MusicEndRecoveryPolicy.afterPhysicalPowerCycle(
                MusicEndStatus(true,true),true,true))
        assertEquals(MusicEndRelease.FIRMWARE_CONFIRMED,
            MusicEndRecoveryPolicy.afterPhysicalPowerCycle(
                MusicEndStatus(false,false),true,true))
    }

    @Test fun oneShotUnknownStatePermitCannotOverrideActiveMusicOrOffBulb() {
        assertNull(MusicEndRecoveryPolicy.admissionRefusal(
            MusicEndStatus(true,false),false))
        assertNull(MusicEndRecoveryPolicy.admissionRefusal(
            MusicEndStatus(true,null),true))
        assertNotNull(MusicEndRecoveryPolicy.admissionRefusal(
            MusicEndStatus(true,null),false))
        assertNotNull(MusicEndRecoveryPolicy.admissionRefusal(
            MusicEndStatus(true,true),true))
        assertNotNull(MusicEndRecoveryPolicy.admissionRefusal(
            MusicEndStatus(false,null),true))
        assertNotNull(MusicEndRecoveryPolicy.admissionRefusal(
            MusicEndStatus(null,null),true))
    }
}
