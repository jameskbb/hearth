package io.github.jameskbb.hearth.system

import io.github.jameskbb.hearth.apps.LaunchResult
import kotlinx.coroutines.flow.StateFlow

/**
 * One of the TV's HDMI inputs. [id] is the TV Input Framework's input id, [label] the name the
 * TV reports for it (never Hearth's custom name), [port] its position among the HDMI inputs in
 * port order, from 1 (the framework's own port number is not public API).
 */
data class TvInput(val id: String, val label: String, val port: Int)

/**
 * The TV's HDMI inputs, through the platform's TV Input Framework
 * (docs/decisions/0007-tv-inputs.md). Empty wherever switching cannot work: no TV input service,
 * no HDMI inputs, or no activity for the passthrough intent (a Fire TV stick, the emulator).
 */
interface TvInputs {
    /** Inputs in port order. Empty until the first [refresh] completes, and when unsupported. */
    val inputs: StateFlow<List<TvInput>>

    /** Reads the inputs again, off the calling thread. Safe to call often. */
    fun refresh()

    /** Shows input [id] through the platform's own TV player. Never throws. */
    fun switchTo(id: String): LaunchResult
}

/** One input as the framework reports it, before Hearth's rules are applied. */
internal data class RawTvInput(
    val id: String,
    val isHdmi: Boolean,
    /** Set for an HDMI-CEC device behind a port; the port's own input is listed instead. */
    val parentId: String?,
    val customLabel: String?,
    val label: String?,
)

/**
 * Hearth's listing rules, pure so they run as JVM unit tests:
 * - Only top-level HDMI inputs: no tuner, composite or component inputs, and no HDMI-CEC device
 *   inputs, which sit under the port they are plugged into.
 * - Order: the trailing number of the id ("…/HW5" is 5), inputs without one last, then the TV's
 *   name, then the id. The framework's HDMI port number is not public API; the id's number keeps
 *   port order but is not the port itself (one TV numbers ports 1 to 4 as HW4 to HW7), so it is
 *   used only for ordering. Duplicate ids are dropped.
 * - [TvInput.port] is the position in that order, from 1: the first HDMI input is port 1.
 * - The label is the TV's custom label (the name the user gave it in the TV's settings), else its
 *   label, else "HDMI n" from that position ([numbered] formats it).
 * - Nothing at all unless the passthrough intent resolves ([canSwitch]).
 */
internal object TvInputListing {
    private val trailingNumber = Regex("(\\d+)$")

    /** The id's trailing number, for ordering only. */
    fun orderNumber(id: String): Int? = trailingNumber.find(id)?.groupValues?.get(1)?.takeIf { it.length <= 9 }?.toInt()

    fun list(
        raw: List<RawTvInput>,
        canSwitch: (String) -> Boolean,
        numbered: (Int) -> String,
    ): List<TvInput> {
        val inputs = raw.asSequence()
            .filter { it.isHdmi && it.parentId == null && it.id.isNotBlank() }
            .distinctBy { it.id }
            .map { r ->
                val name = r.customLabel?.trim()?.takeIf { it.isNotEmpty() } ?: r.label?.trim()?.takeIf { it.isNotEmpty() }
                Triple(r.id, name, orderNumber(r.id))
            }
            .sortedWith(
                compareBy<Triple<String, String?, Int?>, Int?>(nullsLast()) { it.third }
                    .thenBy(nullsLast()) { it.second }
                    .thenBy { it.first },
            )
            .mapIndexed { index, (id, name, _) ->
                val position = index + 1
                TvInput(id, name ?: numbered(position), position)
            }
            .toList()
        if (inputs.isEmpty() || !canSwitch(inputs.first().id)) return emptyList()
        return inputs
    }
}
