package io.github.lxsdd.yeelightloftdj

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class KnightRiderRunnerTest {
    private val bulbs = (0..4).map {
        YeelightDevice("b$it", "192.168.0.${100 + it}",
            methods = setOf("get_prop", "set_power", "set_scene", "set_rgb"))
    }
    private val config = ChaseConfig(
        style=ChaseStyle.KNIGHT_RIDER,palette=listOf(0xFF0000),
        stepMs=600, tailLength=1, brightness=80,bounce=true
    )

    @Test fun noTwoBulbsAreEverOnAndAllAreOffOnStop() = runBlocking {
        val lit=mutableSetOf<String>();val actions=mutableListOf<String>()
        var done=false
        val result=KnightRiderRunner().execute(bulbs,config,
            isStopping={done},
            powerOff={d-> lit.remove(d.ip);actions+="off:${d.id}";true},
            lightOn={d,_,_->assertTrue("Prior bulb still lit: $lit",lit.isEmpty());lit.add(d.ip);actions+="on:${d.id}"},
            waitStep={},
            onStep={_,n-> if(n>=12)done=true}
        )
        assertTrue(lit.isEmpty())
        assertTrue(result.unresolved.isEmpty())
        assertEquals(12L,result.steps)
        assertNull(result.error)
        assertTrue(actions.take(5).all{it.startsWith("off:")})
    }

    @Test fun failedPreviousOffNeverAllowsNextOn() = runBlocking {
        var stopping=false;var onCount=0;var offCount=0
        val result=KnightRiderRunner().execute(bulbs.take(3),config,
            isStopping={stopping},
            powerOff={_->offCount++;offCount!=4},
            lightOn={_,_,_->onCount++},
            waitStep={},
            onStep={_,_->}
        )
        assertEquals(1,onCount)
        assertNotNull(result.error)
    }

    @Test fun unknownOffStateDuringPreflightMeansNoLightCommand()=runBlocking{
        var turnedOn=false
        val r=KnightRiderRunner().execute(bulbs.take(2),config,
            isStopping={false},powerOff={ d->d.id!="b0" },
            lightOn={_,_,_->turnedOn=true},waitStep={},onStep={_,_->})
        assertFalse(turnedOn)
        assertNotNull(r.error)
    }
}
