package com.softwaremine.dps

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.softwaremine.dps.data.model.ModelCatalog
import com.softwaremine.dps.di.AiContainer
import com.softwaremine.dps.domain.model.ModelInstallState
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * THROWAWAY diagnostic, not permanent coverage.
 *
 * Downloads the real, catalog-verified GGUF model via the app's own
 * production ModelManager.install() flow, over the device's currently
 * connected network (Wi-Fi, confirmed before running this) — needed so the
 * Calendar Delete/Update Classification investigation can exercise the real
 * on-device model rather than a scripted one.
 */
@RunWith(AndroidJUnit4::class)
class ModelDownloadDiagnosticTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val descriptor = ModelCatalog.DEFAULT

    @Test
    fun downloadDefaultModel() = runBlocking {
        val container = AiContainer(context)
        var last: ModelInstallState = ModelInstallState.NotInstalled

        container.modelManager.install(descriptor).collect { state ->
            last = state
            when (state) {
                is ModelInstallState.Downloading ->
                    println("MODEL_DIAG downloading ${state.bytesDownloaded}/${state.totalBytes}")
                is ModelInstallState.Verifying -> println("MODEL_DIAG verifying")
                is ModelInstallState.Installed -> println("MODEL_DIAG installed ${state.model}")
                is ModelInstallState.Failed -> println("MODEL_DIAG failed ${state.error}")
                ModelInstallState.NotInstalled -> println("MODEL_DIAG not installed")
            }
        }

        assertTrue("Expected Installed, got $last", last is ModelInstallState.Installed)
    }
}
