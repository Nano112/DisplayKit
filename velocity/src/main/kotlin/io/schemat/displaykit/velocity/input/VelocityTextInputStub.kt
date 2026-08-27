package io.schemat.displaykit.velocity.input

import io.schemat.displaykit.platform.PlayerRef
import io.schemat.displaykit.platform.TextInput
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger

/**
 * Text input is not implemented on the proxy platform yet.
 *
 * Nothing in core calls it today; the contract is honoured by completing the
 * callback with null, so any future caller degrades to "input cancelled"
 * instead of hanging.
 */
class VelocityTextInputStub(private val logger: Logger) : TextInput {

    private val warned = AtomicBoolean(false)

    override fun requestInput(player: PlayerRef, currentValue: String, callback: (String?) -> Unit) {
        if (warned.compareAndSet(false, true)) {
            logger.warning("Text input is not implemented on the Velocity platform, completing with null")
        }
        callback(null)
    }
}
