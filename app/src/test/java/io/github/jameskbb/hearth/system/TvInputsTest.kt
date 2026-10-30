package io.github.jameskbb.hearth.system

import android.content.ActivityNotFoundException
import io.github.jameskbb.hearth.apps.LaunchResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hearth's listing rules and [AndroidTvInputs]'s failure handling, against a fake platform. */
@OptIn(ExperimentalCoroutinesApi::class)
class TvInputsTest {
    private fun hdmi(id: String, label: String? = null, custom: String? = null, parent: String? = null) =
        RawTvInput(id, isHdmi = true, parentId = parent, customLabel = custom, label = label)

    private fun list(raw: List<RawTvInput>, canSwitch: Boolean = true) =
        TvInputListing.list(raw, { canSwitch }, { "HDMI $it" })

    @Test fun keepsTopLevelHdmiInputsInPortOrder() {
        val raw = listOf(
            hdmi("tv/.Hdmi/HW3", "HDMI 3"),
            RawTvInput("tv/.Tuner", isHdmi = false, parentId = null, customLabel = null, label = "Antenna"),
            hdmi("tv/.Hdmi/HW1", "HDMI 1"),
            hdmi("tv/.Hdmi/HDMI100008", "Console", parent = "tv/.Hdmi/HW1"),
            hdmi("tv/.Hdmi/HW2", "HDMI 2"),
            hdmi("tv/.Hdmi/HW1", "duplicate"),
        )
        assertEquals(
            listOf(TvInput("tv/.Hdmi/HW1", "HDMI 1", 1), TvInput("tv/.Hdmi/HW2", "HDMI 2", 2), TvInput("tv/.Hdmi/HW3", "HDMI 3", 3)),
            list(raw),
        )
    }

    @Test fun labelPrefersTheTvsCustomLabelThenItsLabelThenThePosition() {
        val raw = listOf(
            hdmi("tv/HW1", label = "Living room", custom = "Game console"),
            hdmi("tv/HW2", label = "  ", custom = " "),
            hdmi("tv/HW3", label = "Blu-ray"),
            hdmi("tv/hdmi"),
        )
        assertEquals(listOf("Game console", "HDMI 2", "Blu-ray", "HDMI 4"), list(raw).map { it.label })
        assertEquals(listOf(1, 2, 3, 4), list(raw).map { it.port })
    }

    @Test fun theIdsNumberOrdersButNeverNamesThePort() {
        // One TV lists ports 1 to 4 as HW4 to HW7.
        val raw = listOf(hdmi("tv/HW6"), hdmi("tv/HW4"), hdmi("tv/HW7", label = "Console"), hdmi("tv/HW5"))
        assertEquals(
            listOf(
                TvInput("tv/HW4", "HDMI 1", 1),
                TvInput("tv/HW5", "HDMI 2", 2),
                TvInput("tv/HW6", "HDMI 3", 3),
                TvInput("tv/HW7", "Console", 4),
            ),
            list(raw),
        )
    }

    @Test fun nothingWhenThePassthroughIntentDoesNotResolve() {
        assertEquals(emptyList<TvInput>(), list(listOf(hdmi("tv/HW1", "HDMI 1")), canSwitch = false))
        assertEquals(emptyList<TvInput>(), list(emptyList()))
    }

    @Test fun orderNumberIsTheTrailingNumber() {
        assertEquals(4, TvInputListing.orderNumber("com.example/.HDMIInputService/HW4"))
        assertEquals(null, TvInputListing.orderNumber("com.example/.HDMIInputService"))
        assertEquals(null, TvInputListing.orderNumber("x/HW123456789012"))
    }

    private class FakePlatform : TvInputPlatform {
        var raw: List<RawTvInput>? = emptyList()
        var listFailure: Exception? = null
        var resolves = true
        var viewFailure: Exception? = null
        val viewed = mutableListOf<String>()
        var onChange: (() -> Unit)? = null
        override fun inputs(): List<RawTvInput>? {
            listFailure?.let { throw it }
            return raw
        }
        override fun canView(id: String) = resolves
        override fun view(id: String) {
            viewFailure?.let { throw it }
            viewed += id
        }
        override fun watch(onChange: () -> Unit) {
            this.onChange = onChange
        }
        override fun numbered(port: Int) = "HDMI $port"
    }

    private fun inputs(platform: FakePlatform): AndroidTvInputs {
        val dispatcher = UnconfinedTestDispatcher()
        return AndroidTvInputs(platform, CoroutineScope(dispatcher), dispatcher)
    }

    @Test fun noServiceMeansNoInputs() {
        val p = FakePlatform().apply { raw = null }
        val tv = inputs(p).apply { start() }
        assertEquals(emptyList<TvInput>(), tv.inputs.value)
    }

    @Test fun aFrameworkFailureMeansNoInputs() {
        val p = FakePlatform().apply { listFailure = RuntimeException("binder died") }
        val tv = inputs(p).apply { start() }
        assertEquals(emptyList<TvInput>(), tv.inputs.value)
    }

    @Test fun inputChangesRefreshTheList() {
        val p = FakePlatform().apply { raw = listOf(hdmi("tv/HW1", "HDMI 1")) }
        val tv = inputs(p).apply { start() }
        assertEquals(listOf("tv/HW1"), tv.inputs.value.map { it.id })
        p.raw = emptyList()
        p.onChange!!.invoke()
        assertEquals(emptyList<TvInput>(), tv.inputs.value)
    }

    @Test fun switchingMapsFailuresAndNeverThrows() {
        val p = FakePlatform()
        val tv = inputs(p)
        assertEquals(LaunchResult.Launched, tv.switchTo("tv/HW1"))
        assertEquals(listOf("tv/HW1"), p.viewed)
        p.viewFailure = ActivityNotFoundException("no player")
        assertTrue(tv.switchTo("tv/HW1") is LaunchResult.Failed)
        p.viewFailure = SecurityException("denied")
        assertTrue(tv.switchTo("tv/HW1") is LaunchResult.Failed)
        p.viewFailure = IllegalStateException()
        assertTrue(tv.switchTo("tv/HW1") is LaunchResult.Failed)
    }
}
