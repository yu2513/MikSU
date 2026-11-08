package io.milksu.manager.data.system

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.util.TypedValue
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

object ManagerIcon {
    private const val MAX_INPUT = 16 * 1024 * 1024
    private const val EDGE = 432

    fun default(context: Context): Bitmap =
        Bitmap.createBitmap(EDGE, EDGE, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(240, 242, 244))
            requireNotNull(context.getDrawable(android.R.drawable.ic_menu_manage)).mutate().apply {
                setTint(Color.rgb(76, 85, 96))
                setBounds(102, 102, EDGE - 102, EDGE - 102)
                draw(canvas)
            }
        }

    fun decode(context: Context, uri: Uri): Bitmap {
        require(uri.scheme == "content") { "Select an image from the image picker" }
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_INPUT) { "The selected image is too large" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: throw IllegalArgumentException("The selected image cannot be read")
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            val width = info.size.width
            val height = info.size.height
            require(width > 0 && height > 0 && width.toLong() * height <= 64_000_000L &&
                width <= 16_384 && height <= 16_384) { "The selected image dimensions are too large" }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val scale = minOf(1.0, 1024.0 / maxOf(width, height))
            decoder.setTargetSize(maxOf(1, (width * scale).toInt()), maxOf(1, (height * scale).toInt()))
        }
        try {
            val side = minOf(bitmap.width, bitmap.height)
            val left = (bitmap.width - side) / 2
            val top = (bitmap.height - side) / 2
            return Bitmap.createBitmap(EDGE, EDGE, Bitmap.Config.ARGB_8888).also {
                Canvas(it).drawBitmap(bitmap, Rect(left, top, left + side, top + side),
                    Rect(0, 0, EDGE, EDGE), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            }
        } finally {
            bitmap.recycle()
        }
    }

    fun png(context: Context, uri: Uri?): ByteArray {
        val bitmap = uri?.let { decode(context, it) } ?: default(context)
        return try {
            ByteArrayOutputStream().also {
                require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    fun entry(context: Context, application: ApplicationInfo): String {
        val resources = context.packageManager.getResourcesForApplication(application)
        val resourcePackage = resources.getResourcePackageName(application.icon)
        val foreground = resources.getIdentifier("ic_launcher_foreground", "drawable", resourcePackage)
        require(foreground != 0) { "The Manager icon resource is missing" }
        val value = TypedValue()
        resources.getValue(foreground, value, true)
        return value.string?.toString()?.takeIf {
            it.startsWith("res/") && !it.endsWith(".xml") && it.split('/').none { part -> part == ".." }
        } ?: throw IllegalArgumentException("The Manager icon is not a bitmap resource")
    }
}
