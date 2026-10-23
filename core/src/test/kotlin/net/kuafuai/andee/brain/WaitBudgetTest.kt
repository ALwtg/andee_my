package net.kuafuai.andee.brain

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WaitBudgetTest {
    @Test fun theNormalClimbIsAllowed() {
        var spent = 0.0
        for (ask in listOf(3.0, 5.0, 10.0, 20.0, 20.0)) {
            assertNull("wait of $ask after $spent", WaitBudget.check(spent, ask))
            spent += ask
        }
    }

    @Test fun pastTheTurnLimitItRefusesAndSaysWhatToDoInstead() {
        val why = WaitBudget.check(80.0, 20.0)
        assertNotNull(why)
        assertTrue(why!!.contains("todo"))
        assertTrue(why.contains("end this turn"))
    }

    @Test fun exactlyAtTheLimitStillPasses() {
        assertNull(WaitBudget.check(70.0, 20.0))
    }
}
