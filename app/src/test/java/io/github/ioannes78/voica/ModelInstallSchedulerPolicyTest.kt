package io.github.ioannes78.voica

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelInstallSchedulerPolicyTest {
    @Test
    fun manualInstallUsesForegroundWorkManagerBeforeAndroid14() {
        assertEquals(
            ModelInstallExecutorKind.WORK_MANAGER_DOWNLOAD,
            modelInstallDownloadExecutor(ModelInstallOrigin.MANUAL, Build.VERSION_CODES.TIRAMISU),
        )
    }

    @Test
    fun manualInstallUsesUidtOnAndroid14AndNewer() {
        assertEquals(
            ModelInstallExecutorKind.UIDT,
            modelInstallDownloadExecutor(ModelInstallOrigin.MANUAL, Build.VERSION_CODES.UPSIDE_DOWN_CAKE),
        )
        assertEquals(
            ModelInstallExecutorKind.UIDT,
            modelInstallDownloadExecutor(ModelInstallOrigin.MANUAL, 37),
        )
    }

    @Test
    fun automaticSmallModelUpdateNeverUsesUidt() {
        assertEquals(
            ModelInstallExecutorKind.WORK_MANAGER_DOWNLOAD,
            modelInstallDownloadExecutor(ModelInstallOrigin.AUTO_SMALL, Build.VERSION_CODES.UPSIDE_DOWN_CAKE),
        )
        assertEquals(
            ModelInstallExecutorKind.WORK_MANAGER_DOWNLOAD,
            modelInstallDownloadExecutor(ModelInstallOrigin.AUTO_SMALL, 37),
        )
    }
}
