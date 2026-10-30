package com.hamzawy.radio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import androidx.exifinterface.media.ExifInterface
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.Executors

/** A background theme: one of the built-in pictures, or an image the user added. */
data class AppTheme(
    val id: String,
    val name: String,
    val builtinRes: Int,
    val file: String?,
    val accent: Int
) {
    val isBuiltin: Boolean get() = builtinRes != 0
}

/**
 * Keeps the list of themes, the selected one and the dim level.
 * Images the user adds are cropped to 16:9, shrunk to at most 1920x1080 and copied into the
 * app's private storage, so they keep working even if the original file or USB stick goes away.
 */
object ThemeStore {

    val DEFAULT_ACCENT: Int = 0xFF35D0BA.toInt()

    private const val PREFS = "themes"
    private const val KEY_CUSTOM = "custom"
    private const val KEY_SELECTED = "selected"
    private const val KEY_DIM = "dim"
    private const val KEY_COUNTER = "counter"

    private const val MAX_W = 1920
    private const val MAX_H = 1080
    private const val THUMB_W = 480
    private const val THUMB_H = 270

    /** How dark the layer on top of the picture is: light, medium, dark. */
    private val DIM_ALPHAS = floatArrayOf(0.20f, 0.42f, 0.65f)

    val builtins: List<AppTheme> = listOf(
        AppTheme("nile_night", "ليل النيل", R.drawable.theme_nile_night, null, 0xFF35D0BA.toInt()),
        AppTheme("pyramids", "غروب الأهرامات", R.drawable.theme_pyramids, null, 0xFFFF8A3D.toInt()),
        AppTheme("flag_colors", "ألوان العلم", R.drawable.theme_flag_colors, null, 0xFFE63946.toInt()),
        AppTheme("green_crescent", "الأخضر", R.drawable.theme_green_crescent, null, 0xFF2ECC71.toInt()),
        AppTheme("midnight", "منتصف الليل", R.drawable.theme_midnight, null, 0xFF7C83FF.toInt())
    )

    private val bgExecutor = Executors.newSingleThreadExecutor()
    private val thumbExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val thumbCache = LruCache<String, Bitmap>(32)

    // ---------------------------------------------------------------- list & selection

    fun all(ctx: Context): List<AppTheme> = builtins + customThemes(ctx)

    fun selected(ctx: Context): AppTheme {
        val id = prefs(ctx).getString(KEY_SELECTED, builtins[0].id)
        return all(ctx).firstOrNull { it.id == id } ?: builtins[0]
    }

    fun select(ctx: Context, id: String) {
        prefs(ctx).edit().putString(KEY_SELECTED, id).apply()
    }

    fun dimLevel(ctx: Context): Int =
        prefs(ctx).getInt(KEY_DIM, 1).coerceIn(0, DIM_ALPHAS.size - 1)

    fun setDimLevel(ctx: Context, level: Int) {
        prefs(ctx).edit().putInt(KEY_DIM, level.coerceIn(0, DIM_ALPHAS.size - 1)).apply()
    }

    fun dimAlpha(ctx: Context): Float = DIM_ALPHAS[dimLevel(ctx)]

    fun customThemes(ctx: Context): List<AppTheme> {
        val raw = prefs(ctx).getString(KEY_CUSTOM, null) ?: return emptyList()
        val out = ArrayList<AppTheme>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val file = o.getString("file")
                if (File(dir(ctx), file).exists()) {
                    out.add(AppTheme(o.getString("id"), o.optString("name"), 0, file, o.optInt("accent", DEFAULT_ACCENT)))
                }
            }
        } catch (e: Exception) {
            // Ignore a corrupt list.
        }
        return out
    }

    fun delete(ctx: Context, id: String) {
        val list = customThemes(ctx)
        val target = list.firstOrNull { it.id == id } ?: return
        target.file?.let { File(dir(ctx), it).delete() }
        File(dir(ctx), thumbName(id)).delete()
        thumbCache.remove(id)
        saveCustom(ctx, list.filter { it.id != id })
        if (prefs(ctx).getString(KEY_SELECTED, null) == id) select(ctx, builtins[0].id)
    }

    // ---------------------------------------------------------------- loading pictures

    /** Decodes the full background off the main thread and hands it to [callback] on the main thread. */
    fun loadBackgroundAsync(ctx: Context, theme: AppTheme, callback: (Bitmap?) -> Unit) {
        val app = ctx.applicationContext
        bgExecutor.execute {
            val bmp = loadFull(app, theme)
            mainHandler.post { callback(bmp) }
        }
    }

    fun loadThumbAsync(ctx: Context, theme: AppTheme, callback: (Bitmap?) -> Unit) {
        val cached = thumbCache.get(theme.id)
        if (cached != null) {
            callback(cached)
            return
        }
        val app = ctx.applicationContext
        thumbExecutor.execute {
            val bmp = loadThumb(app, theme)
            if (bmp != null) thumbCache.put(theme.id, bmp)
            mainHandler.post { callback(bmp) }
        }
    }

    private fun loadFull(ctx: Context, theme: AppTheme): Bitmap? {
        return try {
            if (theme.isBuiltin) {
                decodeResource(ctx, theme.builtinRes, MAX_W, MAX_H)
            } else {
                decodeFile(File(dir(ctx), theme.file ?: "").absolutePath, MAX_W, MAX_H)
            }
        } catch (e: Throwable) {
            null
        }
    }

    private fun loadThumb(ctx: Context, theme: AppTheme): Bitmap? {
        return try {
            if (theme.isBuiltin) {
                decodeResource(ctx, theme.builtinRes, THUMB_W, THUMB_H)
            } else {
                val t = File(dir(ctx), thumbName(theme.id))
                if (t.exists()) {
                    BitmapFactory.decodeFile(t.absolutePath)
                } else {
                    decodeFile(File(dir(ctx), theme.file ?: "").absolutePath, THUMB_W, THUMB_H)
                }
            }
        } catch (e: Throwable) {
            null
        }
    }

    // ---------------------------------------------------------------- adding a theme

    fun addFromUri(ctx: Context, uri: Uri): AppTheme? =
        importImage(ctx) { ctx.contentResolver.openInputStream(uri) }

    fun addFromBytes(ctx: Context, bytes: ByteArray): AppTheme? =
        importImage(ctx) { ByteArrayInputStream(bytes) }

    private fun importImage(ctx: Context, open: () -> InputStream?): AppTheme? {
        try {
            val bounds = BitmapFactory.Options()
            bounds.inJustDecodeBounds = true
            open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val opts = BitmapFactory.Options()
            opts.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_W, MAX_H)
            var bmp: Bitmap = open()?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null

            val rotation = readRotation(open)
            if (rotation != 0) {
                val m = Matrix()
                m.postRotate(rotation.toFloat())
                val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                if (rotated !== bmp) bmp.recycle()
                bmp = rotated
            }

            val finalBmp = cropTo16x9(bmp)
            if (finalBmp !== bmp) bmp.recycle()

            val id = "u" + System.currentTimeMillis()
            val fileName = id + ".jpg"
            val d = dir(ctx)
            FileOutputStream(File(d, fileName)).use { finalBmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }

            val thumb = Bitmap.createScaledBitmap(finalBmp, THUMB_W, THUMB_H, true)
            FileOutputStream(File(d, thumbName(id))).use { thumb.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            thumb.recycle()

            val accent = accentFrom(finalBmp)
            finalBmp.recycle()

            val p = prefs(ctx)
            val n = p.getInt(KEY_COUNTER, 0) + 1
            p.edit().putInt(KEY_COUNTER, n).apply()

            val theme = AppTheme(id, ctx.getString(R.string.custom_theme_name, n), 0, fileName, accent)
            val list = ArrayList<AppTheme>(customThemes(ctx))
            list.add(theme)
            saveCustom(ctx, list)
            return theme
        } catch (e: Throwable) {
            return null
        }
    }

    /** Picks the most vivid colour of the picture; it is used for focus rings and highlights. */
    fun accentFrom(src: Bitmap): Int {
        val small = Bitmap.createScaledBitmap(src, 48, 27, true)
        val hsv = FloatArray(3)
        val weight = FloatArray(12)
        for (y in 0 until small.height) {
            for (x in 0 until small.width) {
                Color.colorToHSV(small.getPixel(x, y), hsv)
                if (hsv[1] < 0.30f || hsv[2] < 0.30f) continue
                val bucket = (hsv[0] / 30f).toInt().coerceIn(0, 11)
                weight[bucket] += hsv[1] * hsv[2]
            }
        }
        if (small !== src) small.recycle()

        var best = -1
        var bestWeight = 0f
        for (i in 0 until 12) {
            if (weight[i] > bestWeight) {
                bestWeight = weight[i]
                best = i
            }
        }
        if (best < 0) return DEFAULT_ACCENT
        return Color.HSVToColor(floatArrayOf(best * 30f + 15f, 0.75f, 1f))
    }

    // ---------------------------------------------------------------- helpers

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun dir(ctx: Context): File {
        val d = File(ctx.filesDir, "themes")
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun thumbName(id: String): String = id + "_t.jpg"

    private fun saveCustom(ctx: Context, list: List<AppTheme>) {
        val arr = JSONArray()
        for (t in list) {
            val o = JSONObject()
            o.put("id", t.id)
            o.put("name", t.name)
            o.put("file", t.file ?: "")
            o.put("accent", t.accent)
            arr.put(o)
        }
        prefs(ctx).edit().putString(KEY_CUSTOM, arr.toString()).apply()
    }

    private fun sampleSize(srcW: Int, srcH: Int, reqW: Int, reqH: Int): Int {
        if (srcW <= 0 || srcH <= 0) return 1
        var s = 1
        while (srcW / (s * 2) >= reqW && srcH / (s * 2) >= reqH) s *= 2
        return s
    }

    private fun decodeResource(ctx: Context, res: Int, w: Int, h: Int): Bitmap? {
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        BitmapFactory.decodeResource(ctx.resources, res, bounds)
        val opts = BitmapFactory.Options()
        opts.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, w, h)
        return BitmapFactory.decodeResource(ctx.resources, res, opts)
    }

    private fun decodeFile(path: String, w: Int, h: Int): Bitmap? {
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        BitmapFactory.decodeFile(path, bounds)
        val opts = BitmapFactory.Options()
        opts.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, w, h)
        return BitmapFactory.decodeFile(path, opts)
    }

    private fun readRotation(open: () -> InputStream?): Int {
        return try {
            val stream = open() ?: return 0
            stream.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            }
        } catch (e: Throwable) {
            0
        }
    }

    /** Centre-crops to 16:9 (the TV shape) and limits the width to [MAX_W]. */
    private fun cropTo16x9(src: Bitmap): Bitmap {
        val target = 16f / 9f
        val ratio = src.width.toFloat() / src.height.toFloat()
        var cw = src.width
        var ch = src.height
        if (ratio > target) cw = (src.height * target).toInt() else ch = (src.width / target).toInt()
        if (cw < 1) cw = 1
        if (ch < 1) ch = 1
        val x = (src.width - cw) / 2
        val y = (src.height - ch) / 2
        val cropped = Bitmap.createBitmap(src, x, y, cw, ch)
        if (cw <= MAX_W) return cropped
        val scaledH = (ch * MAX_W.toFloat() / cw.toFloat()).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(cropped, MAX_W, scaledH, true)
        if (scaled !== cropped && cropped !== src) cropped.recycle()
        return scaled
    }
}
