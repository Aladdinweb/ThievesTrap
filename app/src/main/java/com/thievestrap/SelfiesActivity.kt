package com.thievestrap

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class SelfiesActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LocaleHelper.applyLocale(this)
        setContentView(R.layout.activity_selfies)
        // Gallery: Delete All button
        try {
            // Fix 1: use PUBLIC directory — matches where SelfieService.savePhoto() writes
            val dir = java.io.File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_PICTURES), "ThievesTrap")
            dir.mkdirs()  // ensure it exists
            findViewById<Button>(R.id.btn_delete_all).setOnClickListener {
                val files = dir.listFiles()?.filter { it.extension.lowercase() == "jpg" } ?: emptyList()
                if (files.isEmpty()) {
                    android.widget.Toast.makeText(this, "No intruder photos found", android.widget.Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Delete All Intruder Photos")
                    .setMessage("Permanently delete ${files.size} photo(s) from the gallery?")
                    .setPositiveButton("Delete All") { _, _ ->
                        var deleted = 0
                        files.forEach { if (it.delete()) deleted++ }
                        // Also notify MediaStore
                        try {
                            val mediaScanIntent = android.content.Intent(
                                android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
                            mediaScanIntent.data = android.net.Uri.fromFile(dir)
                            sendBroadcast(mediaScanIntent)
                        } catch (e: Exception) {}
                        android.widget.Toast.makeText(this, "$deleted photo(s) deleted", android.widget.Toast.LENGTH_SHORT).show()
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

        val dir = File(Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_PICTURES), "ThievesTrap")

        if (!dir.exists() || dir.listFiles().isNullOrEmpty()) {
            tvEmpty.visibility = android.view.View.VISIBLE
            return
        }

        val files = dir.listFiles()
            ?.filter { it.name.endsWith(".jpg") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

        if (files.isEmpty()) {
            tvEmpty.visibility = android.view.View.VISIBLE
            return
        }

        tvEmpty.visibility = android.view.View.GONE

        files.forEach { file ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, 16)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
            }

            // Thumbnail
            val img = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(120, 120).apply {
                    setMargins(0, 0, 16, 0)
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
                try {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                    val bmp = BitmapFactory.decodeFile(file.absolutePath, opts)
                    setImageBitmap(bmp)
                } catch (e: Exception) {
                    setImageResource(android.R.drawable.ic_menu_camera)
                }
                setOnClickListener { openImage(file) }
            }

            // Info
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(Date(file.lastModified()))

            info.addView(TextView(this).apply {
                text = "📸 Intruder photo"
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
                setOnClickListener { openImage(file) }
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
                setOnClickListener { shareImage(file) }
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

            // Divider
            container.addView(android.view.View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                    setMargins(0, 0, 0, 16)
                }
                setBackgroundColor(0xFF1A1A1A.toInt())
            })
        }
    }

    private fun shareImage(file: File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "${packageName}.fileprovider", file)
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                putExtra(android.content.Intent.EXTRA_SUBJECT, "Intruder Alert - Thieves Trap")
                putExtra(android.content.Intent.EXTRA_TEXT,
                    "Intruder photo captured on ${file.name}")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(android.content.Intent.createChooser(intent, "Share via"))
        } catch (e: Exception) {
            Toast.makeText(this, "Share failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openImage(file: File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "image/jpeg")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            // Fallback: try gallery intent
            try {
                val uri = android.net.Uri.parse(file.absolutePath)
                startActivity(Intent(Intent.ACTION_VIEW, uri).apply {
                    type = "image/*"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
            } catch (e2: Exception) {
                Toast.makeText(this, "Photo saved at: ${file.name}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
