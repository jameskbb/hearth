package io.github.jameskbb.hearth.ui

import io.github.jameskbb.hearth.apps.LaunchResult
import io.github.jameskbb.hearth.system.TvInput
import io.github.jameskbb.hearth.system.TvInputs
import kotlinx.coroutines.flow.MutableStateFlow

/** An in-memory [TvInputs]: the test sets [inputs] and [result]; switches are recorded. */
class FakeTvInputs(initial: List<TvInput> = emptyList()) : TvInputs {
    override val inputs = MutableStateFlow(initial)
    var result: LaunchResult = LaunchResult.Launched
    val switched = mutableListOf<String>()
    var refreshes = 0

    override fun refresh() {
        refreshes++
    }

    override fun switchTo(id: String): LaunchResult {
        switched += id
        return result
    }
}
