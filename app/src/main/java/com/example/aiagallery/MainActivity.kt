package com.example.aiagallery

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.net.Uri
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

class MainActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var scanButton: Button
    private lateinit var adapter: PhotoAdapter
    private val scope = CoroutineScope(Dispatchers.Main)

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
            if (hasPermission()) startClassification()
            else permissionLauncher.launch(requiredPermission)
        }

        // 打开时自动尝试
        if (hasPermission()) startClassification()
    }

    private val requiredPermission: String
        get() = if (android.os.Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, requiredPermission) == PackageManager.PERMISSION_GRANTED

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
        val result = mutableListOf<PhotoItem>()
        val collection = if (android.os.Build.VERSION.SDK_INT >= 29) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        contentResolver.query(collection, projection, null, null, sortOrder)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
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