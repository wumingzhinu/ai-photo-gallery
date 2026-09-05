package com.example.aiagallery

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var scanButton: Button
    private lateinit var adapter: PhotoAdapter
    private val scope = CoroutineScope(Dispatchers.Main)
    private val BASE_DIR = "Pictures/AIGallery"

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results.values.all { it }) {
                startClassification()
            } else {
                showPermissionDenied(results)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        recycler = findViewById(R.id.recycler)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        scanButton = findViewById(R.id.scanButton)

        adapter = PhotoAdapter(this)
        recycler.layoutManager = GridLayoutManager(this, 3)
        recycler.adapter = adapter

        scanButton.setOnClickListener {
            val notGranted = requiredPermissions().filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (notGranted.isEmpty()) startClassification()
            else permissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    private fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.WRITE_MEDIA_IMAGES
            )
        } else if (Build.VERSION.SDK_INT >= 29) {
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        } else {
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        }
    }

    private fun showPermissionDenied(results: Map<String, Boolean>) {
        val denied = results.filter { !it.value }.keys
        status.visibility = View.VISIBLE
        status.text = "权限不足，无法移动照片。已拒绝: ${denied.joinToString(", ")}"
        scanButton.visibility = View.VISIBLE
    }

    private fun startClassification() {
        scanButton.visibility = View.GONE
        progress.visibility = View.VISIBLE
        status.visibility = View.VISIBLE
        status.text = "正在加载AI模型..."

        scope.launch {
            try {
                withContext(Dispatchers.IO) { Classifier.init(applicationContext) }
                status.text = "模型加载完成，正在扫描全部照片..."

                val result = withContext(Dispatchers.IO) { scanAndClassify() }

                adapter.submitList(result.items)
                progress.visibility = View.GONE

                val sb = StringBuilder()
                sb.append("扫描完成: 找到${result.total}张\n")
                sb.append("已分类${result.items.size}张\n")
                sb.append("已复制到${BASE_DIR}/\n")
                sb.append("共${result.categories.size}个分类\n")
                for ((cat, count) in result.categories) {
                    sb.append("  $cat: $count张\n")
                }
                if (result.errors.isNotEmpty()) {
                    sb.append("错误: ${result.errors.first()}")
                }
                status.text = sb.toString()
                scanButton.visibility = View.VISIBLE
            } catch (e: Exception) {
                progress.visibility = View.GONE
                status.text = "错误: ${e.message}\n${e.stackTraceToString().take(500)}"
                scanButton.visibility = View.VISIBLE
            }
        }
    }

    data class ScanResult(
        val total: Int,
        val items: List<PhotoItem>,
        val categories: Map<String, Int>,
        val errors: List<String>
    )

    private fun scanAndClassify(): ScanResult {
        val items = mutableListOf<PhotoItem>()
        val errors = mutableListOf<String>()
        val categories = mutableMapOf<String, Int>()

        val collection = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        val cursor = contentResolver.query(collection, projection, null, null, sortOrder)
            ?: return ScanResult(0, emptyList(), emptyMap(), listOf("查询返回null"))
        var total = 0
        cursor.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (c.moveToNext()) {
                total++
                val id = c.getLong(idCol)
                val uri = ContentUris.withAppendedId(collection, id)
                try {
                    val bmp = loadBitmap(uri) ?: continue
                    val classes = Classifier.classify(bmp)
                    bmp.recycle()
                    if (classes.isEmpty()) {
                        errors.add("第$total张:分类为空")
                        continue
                    }
                    val top = classes.first()
                    val category = sanitizeCategory(top.label)
                    items.add(PhotoItem(uri.toString(), top.label, top.confidence))
                    categories[category] = (categories[category] ?: 0) + 1
                    copyToCategory(uri, category)
                } catch (e: Exception) {
                    errors.add("第$total张:${e.message}")
                }
            }
        }
        return ScanResult(total, items, categories, errors)
    }

    private fun sanitizeCategory(label: String): String {
        return label.replace(Regex("[^a-zA-Z0-9\\u4e00-\\u9fa5_]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .take(20)
            .ifEmpty { "other" }
    }

    private fun copyToCategory(uri: Uri, category: String) {
        val displayName = "IMG_${System.currentTimeMillis()}_${Math.random().toString().take(4)}"
        val relPath = "$BASE_DIR/$category"

        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.RELATIVE_PATH, relPath)
                put(MediaStore.Images.Media.MIME_TYPE, "image/*")
            }
            val newUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return
            contentResolver.openInputStream(uri)?.use { input ->
                contentResolver.openOutputStream(newUri)?.use { output ->
                    input.copyTo(output)
                }
            }
        } else {
            val dir = File(android.os.Environment.getExternalStorageDirectory(), relPath)
            dir.mkdirs()
            val file = File(dir, displayName)
            contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            // Add to MediaStore
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DATA, file.absolutePath)
                put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/*")
            }
            contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        }
    }

    private fun loadBitmap(uri: Uri): android.graphics.Bitmap? {
        return try {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, bounds)
            }
            var sample = 1
            while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) sample *= 2
            contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null,
                    android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
            }
        } catch (e: Exception) { null }
    }

    override fun onDestroy() {
        super.onDestroy()
        Classifier.close()
    }
}