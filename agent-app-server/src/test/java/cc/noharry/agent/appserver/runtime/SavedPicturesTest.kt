package cc.noharry.agent.appserver.runtime

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.protocol.view.GENERATED_IMAGES_DIR
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The server's store of pictures the screens show: a conversation's folder, read back by reference, deleted with it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SavedPicturesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun picturesLiveInTheirSessionFolderAndGoWithIt() {
        val kept = File(sessionPicturesDir(context, "keep-me"), "img-a-bcde.jpg").also { it.parentFile?.mkdirs(); it.writeText("x") }
        val dropped = File(sessionPicturesDir(context, "drop-me"), "img-b-cdef.jpg").also { it.parentFile?.mkdirs(); it.writeText("x") }
        val reference = "$GENERATED_IMAGES_DIR/${dropped.parentFile!!.name}/${dropped.name}"
        assertEquals(dropped.canonicalFile, generatedImageFile(context, reference))

        assertTrue(deleteSessionGeneratedImages(context, "drop-me"))
        assertFalse(dropped.exists())
        assertTrue(kept.exists())
        assertNull(generatedImageFile(context, reference))
        assertTrue(deleteSessionGeneratedImages(context, "never-drew"))
    }

    @Test
    fun referencesOutsideASessionFolderAreRejected() {
        File(context.filesDir, "secret.txt").writeText("x")
        File(context.filesDir, "$GENERATED_IMAGES_DIR/loose.jpg").also { it.parentFile?.mkdirs(); it.writeText("x") }
        assertNull(generatedImageFile(context, "$GENERATED_IMAGES_DIR/abc/../../secret.txt"))
        assertNull(generatedImageFile(context, "$GENERATED_IMAGES_DIR/loose.jpg"))
    }
}
