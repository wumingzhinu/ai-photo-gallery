package com.example.aiagallery

import android.Manifest
import android.content.ContentResolver
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var scanButton: Button
    private lateinit var adapter: PhotoAdapter
    private val scope = CoroutineScope(Dispatchers.Main)

    // Android 13+ 用系统照片选择器 (无需任何存储权限)
    private val pickMedia =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) {
                val items = listOf(PhotoItem(uri.toString(), "已选择", 1.0f))
                adapter.submitList(items)
                status.visibility = View.VISIBLE
                status.text = "已选择1张照片，正在识别场景..."
                progress.visibility = View.VISIBLE
                scanButton.visibility = View.GONE
                scope.launch {
                    val result = withContext(Dispatchers.IO) { classifyUri(uri) }
                    progress.visibility = View.GONE
                    adapter.submitList(listOf(PhotoItem(uri.toString(), result.firstOrNull()?.label ?: "无法识别", result.firstOrNull()?.confidence ?: 0f)))
                    status.text = "识别结果: ${result.firstOrNull()?.label ?: "未知"}"
                    scanButton.visibility = View.VISIBLE
                }
            }
        }

    // Android 12 及以下 用存储权限
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startClassification()
            else {
                status.visibility = View.VISIBLE
                status.text = "未获得相册权限，无法扫描照片。请点击下方按钮授权。"
                scanButton.visibility = View.VISIBLE
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
            if (Build.VERSION.SDK_INT >= 33) {
                // Android 13+: 弹出系统相册选择器, 无需权限
                pickMedia.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            } else {
                // Android 12及以下: 申请存储权限
                if (hasPermission()) startClassification()
                else permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

    private fun classifyUri(uri: Uri): List<Classification> {
        Classifier.init(applicationContext)
        val bmp = loadBitmap(uri) ?: return emptyList()
        val result = Classifier.classify(bmp)
        bmp.recycle()
        return result
    }

    private fun startClassification() {
        scanButton.visibility = View.GONE
        progress.visibility = View.VISIBLE
        status.visibility = View.VISIBLE
        status.text = "正在扫描并分类照片，请稍候..."

        scope.launch {
            try {
                val items = withContext(Dispatchers.IO) { scanAndClassify() }
                adapter.submitList(items)
                progress.visibility = View.GONE
                status.text = if (items.isEmpty()) {
                    "未找到可分类的照片（请确认相册中有照片并已授权）"
                } else {
                    "已分类 ${items.size} 张照片"
                }
                scanButton.visibility = View.VISIBLE
            } catch (e: Exception) {
                progress.visibility = View.GONE
                status.text = "分类出错：${e.message}"
                scanButton.visibility = View.VISIBLE
            }
        }
    }

    private fun scanAndClassify(): List<PhotoItem> {
        Classifier.init(applicationContext)
        val result = mutableListOf<PhotoItem>()
        val collection = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        val resolver: ContentResolver = contentResolver
        val cursor: Cursor? = resolver.query(collection, projection, null, null, sortOrder)
        cursor?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val uri = ContentUris.withAppendedId(collection, id)
                try {
                    val bmp = loadBitmap(uri) ?: continue
                    val classes = Classifier.classify(bmp)
                    if (classes.isNotEmpty()) {
                        val top = classes.first()
                        result.add(PhotoItem(uri.toString(), top.label, top.confidence))
                    }
                    bmp.recycle()
                } catch (e: Exception) {
                    // skip unreadable images
                }
            }
        }
        return result
    }

    private fun loadBitmap(uri: Uri): android.graphics.Bitmap? {
        return try {
            contentResolver.openInputStream(uri)?.use { ins ->
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeStream(ins, null, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) sample *= 2
                val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                android.graphics.BitmapFactory.decodeStream(contentResolver.openInputStream(uri), null, opts)
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Classifier.close()
    }
}