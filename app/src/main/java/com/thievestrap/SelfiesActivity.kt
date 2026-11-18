package com.thievestrap

import android.content.Intent
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * v2.9.24 fix: this screen only ever listed `.jpg` files (two separate filters), so a video clip received
 * from a family phone or captured remotely was saved correctly but never showed up here -- "received videos
 * do not automatically appear in the app's internal gallery". Both filters now accept `.jpg` and `.mp4`.
 */
class SelfiesActivity : AppCompatActivity() {

    private fun isVideo(f: File) = f.extension.lowercase() == "mp4"
    private fun mimeOf(f: File) = if (isVideo(f)) "video/mp4" else "image/jpeg"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LocaleHelper.applyLocale(this)
        setContentView(R.layout.activity_selfies)
        // Gallery: Delete All button
        try {
            val dir = java.io.File(
                getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES), "ThievesTrap")
            dir.mkdirs()  // ensure it exists
            findViewById<Button>(R.id.btn_delete_all).setOnClickListener {
                val files = dir.listFiles()?.filter { it.extension.lowercase() == "jpg" || it.extension.lowercase() == "mp4" } ?: emptyList()
                if (files.isEmpty()) {
                    android.widget.Toast.makeText(this, "No photos or clips found", android.widget.Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Delete All")
                    .setMessage("Permanently delete ${files.size} item(s) from the gallery?")
                    .setPositiveButton("Delete All") { _, _ ->
                        var deleted = 0
                        files.forEach { if (it.delete()) deleted++ }
                        try {
                            val mediaScanIntent = android.content.Intent(
                                android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
                            mediaScanIntent.data = android.net.Uri.fromFile(dir)
                            sendBroadcast(mediaScanIntent)
                        } catch (e: Exception) {}
                        android.widget.Toast.makeText(this, "$deleted item(s) deleted", android.widget.Toast.LENGTH_SHORT).show()
                        loadSelfies()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        } catch (e: Exception) {}

        loadSelfies()
        findViewById<Button>(R.id.btn_back_selfies).setOnClickListener { finish() }
    }

    private fun loadSelfies() {
        val container = findViewById<LinearLayout>(R.id.selfies_container)
        val tvEmpty = findViewById<TextView>(R.id.tv_no_selfies)
        container.removeAllViews()

        val dir = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "ThievesTrap")

        if (!dir.exists() || dir.listFiles().isNullOrEmpty()) {
            tvEmpty.visibility = android.view.View.VISIBLE
            return
        }

        val files = dir.listFiles()
            ?.filter { it.name.endsWith(".jpg") || it.name.endsWith(".mp4") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

        if (files.isEmpty()) {
            tvEmpty.visibility = android.view.View.VISIBLE
            return
        }

        tvEmpty.visibility = android.view.View.GONE

        files.forEach { file ->
            val video = isVideo(file)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, 16)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
            }

            val img = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(120, 120).apply {
                    setMargins(0, 0, 16, 0)
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
                try {
                    if (video) {
                        @Suppress("DEPRECATION")
                        val bmp = ThumbnailUtils.createVideoThumbnail(file.absolutePath, MediaStore.Video.Thumbnails.MINI_KIND)
                        if (bmp != null) setImageBitmap(bmp) else setImageResource(android.R.drawable.ic_media_play)
                    } else {
                        val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                        val bmp = BitmapFactory.decodeFile(file.absolutePath, opts)
                        setImageBitmap(bmp)
                    }
                } catch (e: Exception) {
                    setImageResource(if (video) android.R.drawable.ic_media_play else android.R.drawable.ic_menu_camera)
                }
                setOnClickListener { openMedia(file) }
            }

            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(Date(file.lastModified()))

            info.addView(TextView(this).apply {
                text = if (video) "\uD83C\uDFA5 Video clip" else "\uD83D\uDCF8 Intruder photo"
                textSize = 13f
                setTextColor(0xFFFFFFFF.toInt())
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            info.addView(TextView(this).apply {
                text = date
                textSize = 11f
                setTextColor(0xFF888888.toInt())
            })
            info.addView(TextView(this).apply {
                text = file.name
                textSize = 10f
                setTextColor(0xFF444444.toInt())
            })

            val openBtn = Button(this).apply {
                text = "View"
                textSize = 11f
                setTextColor(0xFFFFFFFF.toInt())
                backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF1A1A1A.toInt())
                setOnClickListener { openMedia(file) }
            }

            val delBtn = Button(this).apply {
                text = "Delete"
                textSize = 11f
                setTextColor(0xFFCC0000.toInt())
                backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF1A0000.toInt())
                setOnClickListener {
                    file.delete()
                    loadSelfies()
                }
            }

            val shareBtn = Button(this).apply {
                text = "Share"
                textSize = 11f
                setTextColor(0xFF4A90D9.toInt())
                backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF001A2E.toInt())
                setOnClickListener { shareMedia(file) }
            }

            val btnRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(openBtn)
                addView(shareBtn)
                addView(delBtn)
            }
            info.addView(btnRow)
            row.addView(img)
            row.addView(info)
            container.addView(row)

            container.addView(android.view.View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                    setMargins(0, 0, 0, 16)
                }
                setBackgroundColor(0xFF1A1A1A.toInt())
            })
        }
    }

    private fun shareMedia(file: File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "${packageName}.fileprovider", file)
            val mime = mimeOf(file)
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = mime
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                putExtra(android.content.Intent.EXTRA_SUBJECT, "Thieves Trap Alert")
                putExtra(android.content.Intent.EXTRA_TEXT,
                    (if (isVideo(file)) "Video clip captured on " else "Intruder photo captured on ") + file.name)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(android.content.Intent.createChooser(intent, "Share via"))
        } catch (e: Exception) {
            Toast.makeText(this, "Share failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openMedia(file: File) {
        val mime = mimeOf(file)
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            try {
                val uri = android.net.Uri.parse(file.absolutePath)
                startActivity(Intent(Intent.ACTION_VIEW, uri).apply {
                    type = if (isVideo(file)) "video/*" else "image/*"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
            } catch (e2: Exception) {
                Toast.makeText(this, "Saved at: ${file.name}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
