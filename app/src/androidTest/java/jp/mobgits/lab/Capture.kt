package jp.mobgits.lab

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

internal fun ComposeContentTestRule.saveScreenshot(name: String) {
    waitForIdle()
    val root = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("qa")!!
    root.mkdirs()
    File(root, name).outputStream().use { onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
}
