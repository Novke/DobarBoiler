package rs.novica.dobarboiler.scheduling

/** Which converge passes leave a trace in `action_log` when they send no command (SPEC §6.2 step 5). */
internal object ConvergeLogRules {

    /**
     * Passes that sent commands are always logged. Quiet ones are logged too — "the 00:00 alarm fired,
     * the boiler already matched" and "skipped, manual hold until 08:00" are exactly what a bug report
     * needs — except the frequent verify/band polls that found nothing to do.
     */
    fun logsQuietPass(trigger: String, skipped: Boolean): Boolean =
        skipped || (trigger != Triggers.VERIFY && trigger != Triggers.BAND)
}
