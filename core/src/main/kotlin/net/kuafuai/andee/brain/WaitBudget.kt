package net.kuafuai.andee.brain

/**
 * How long one turn may spend in `wait_for_screen`, and what it is told when the answer is "no more".
 *
 * Three kinds of waiting get mixed up. Waiting for a *screen* (seconds) is what the tool is for. Waiting for
 * *somebody else's work* — a build that takes twenty minutes, a research agent, an upload — is not, and doing
 * it by looking again and again costs a screenshot and the whole tool list per look and fills the history
 * until the conversation breaks. Waiting for the *user* is a question, not a wait.
 *
 * Ninety seconds covers every legitimate climb (3 → 5 → 10 → 20 → 20 is under a minute) and is a long way
 * short of staring at a progress bar. Past it the refusal carries the way out: hand the wait to the clock.
 */
object WaitBudget {

    /** The most one call may wait, matching the tool's own cap. */
    const val PER_CALL_MAX_S = 20.0

    /** The most a single turn may wait in total. */
    const val TURN_MAX_S = 90.0

    /**
     * @param spent seconds this turn has already waited
     * @param ask seconds this call wants
     * @return null to allow; otherwise the refusal, written for the model to act on
     */
    fun check(spent: Double, ask: Double): String? {
        if (spent + ask <= TURN_MAX_S) return null
        return "Not waited: this turn has already spent ${spent.toInt()}s waiting, and the limit is ${TURN_MAX_S.toInt()}s. " +
            "Waiting and looking again is not how a long job is watched. Set a todo for when to come back " +
            "(todo with `at`, a few minutes ahead — and say in `what` exactly what to check and what to do if it is done), " +
            "tell the user in one sentence that it is running and roughly when you will look, and end this turn. " +
            "If the screen is stuck on something that needs the user (a login, a permission, a dialog), say that instead."
    }
}
