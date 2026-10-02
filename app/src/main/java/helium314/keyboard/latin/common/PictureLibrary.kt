// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.common

import android.content.Context
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import java.io.File

/**
 * Every background picture the user has loaded, in one folder shared by all keyboards: each keyboard's picker offers
 * them, and choosing one copies it to that keyboard's own picture (see KeyboardProfiles.profileFile), which it frames
 * independently. Removing one from the list leaves the keyboards that use it as they are.
 */
object PictureLibrary {
    fun dir(ctx: Context) = File(DeviceProtectedUtils.getFilesDir(ctx), "pictures")

    /** The pictures, the one added or used last first. */
    fun list(ctx: Context): List<File> =
        dir(ctx).listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList()

    /** [picture] joins the list (a picture already there only moves to the front). */
    fun add(ctx: Context, picture: File) {
        if (!picture.isFile) return
        runCatching {
            val dir = dir(ctx).apply { mkdirs() }
            val same = dir.listFiles()?.firstOrNull { it.isFile && it.length() == picture.length() && it.readBytes().contentEquals(picture.readBytes()) }
            if (same != null) same.setLastModified(System.currentTimeMillis())
            else picture.copyTo(File(dir, "picture_${System.currentTimeMillis()}"))
        }
    }

    fun remove(picture: File) { picture.delete() }

    /** Once: the pictures the keyboards had before the list existed join it. */
    fun migrate(ctx: Context, done: Boolean, setDone: () -> Unit) {
        if (done) return
        DeviceProtectedUtils.getFilesDir(ctx).listFiles { f -> f.name.startsWith("custom_background_image") && !f.name.endsWith(".framing") }
            ?.forEach { add(ctx, it) }
        setDone()
    }
}
