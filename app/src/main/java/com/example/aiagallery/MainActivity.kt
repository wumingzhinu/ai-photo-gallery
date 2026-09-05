package com.example.aiagallery

import android.Manifest
import android.content.ContentUris
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

class MainActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var scanButton: Button
    private lateinit var adapter: PhotoAdapter
    private val scope = CoroutineScope(Dispatchers.Main)
    private val MAX_PHOTOS = 50

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results.values.any { it }) {
                startClassification()
            } else {
                showPermissionDenied()
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

    private fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun showPermissionDenied() {
        status.visibility = View.VISIBLE
        status.text = "未获得相册权限，请在设置中允许相册访问后重试。"
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
                status.text = "模型加载完成，正在扫描照片(最多${MAX_PHOTOS}张)..."

                val result = withContext(Dispatchers.IO) { scanAndClassify() }

                adapter.submitList(result.items)
                progress.visibility = View.GONE

                val sb = StringBuilder()
                sb.append("找到${result.total}张照片\n")
                sb.append("成功分类${result.items.size}张\n")
                if (result.errors.isNotEmpty()) {
                    sb.append("错误: ${result.errors.first()}\n")
                }
                if (result.items.isEmpty()) {
                    sb.append("请确认已授权「全部照片」权限")
                }
                status.text = sb.toString()
                scanButton.visibility = View.VISIBLE
            } catch (e: Exception) {
                progress.visibility = View.GONE
                status.text = "致命错误：${e.message}\n${e.stackTraceToString()}"
                scanButton.visibility = View.VISIBLE
            }
        }
    }

    data class ScanResult(val total: Int, val items: List<PhotoItem>, val errors: List<String>)

    private fun scanAndClassify(): ScanResult {
        val items = mutableListOf<PhotoItem>()
        val errors = mutableListOf<String>()

        val collection = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        val cursor = contentResolver.query(collection, projection, null, null, sortOrder)
            ?: return ScanResult(0, emptyList(), listOf("MediaStore查询返回null"))
        var total = 0
        cursor.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (c.moveToNext() && total < MAX_PHOTOS) {
                total++
                val id = c.getLong(idCol)
                val uri = ContentUris.withAppendedId(collection, id)
                try {
                    val bmp = loadBitmap(uri) ?: continue
                    val classes = Classifier.classify(bmp)
                    bmp.recycle()
                    if (classes.isEmpty()) {
                        errors.add("第${total}张:分类结果为空")
                    } else {
                        val top = classes.first()
                        items.add(PhotoItem(uri.toString(), top.label, top.confidence))
                    }
                } catch (e: Exception) {
                    errors.add("第${total}张:${e.message}")
                }
            }
        }
        return ScanResult(total, items, errors)
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