package rs.novica.dobarboiler.scheduling

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConvergeLogRulesTest {

    /** A converge that sent nothing still explains itself — except the frequent technical polls. */
    @Test
    fun `transitions, user, boot, watchdog and boost passes are logged even without commands`() {
        for (t in listOf(Triggers.ALARM, Triggers.USER, Triggers.BOOT, Triggers.WATCHDOG, Triggers.BOOST)) {
            assertTrue(t, ConvergeLogRules.logsQuietPass(t, skipped = false))
        }
    }

    @Test
    fun `verify and band polls without commands stay out of the log`() {
        assertFalse(ConvergeLogRules.logsQuietPass(Triggers.VERIFY, skipped = false))
        assertFalse(ConvergeLogRules.logsQuietPass(Triggers.BAND, skipped = false))
    }

    /** A pass skipped because of a manual hold is the evidence for "why did it not heat?" — always logged. */
    @Test
    fun `a skipped pass is always logged`() {
        for (t in listOf(Triggers.VERIFY, Triggers.BAND, Triggers.ALARM)) {
            assertTrue(t, ConvergeLogRules.logsQuietPass(t, skipped = true))
        }
    }
}
