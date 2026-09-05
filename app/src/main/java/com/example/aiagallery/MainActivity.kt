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

class MainActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var scanButton: Button
    private lateinit var adapter: PhotoAdapter
    private val scope = CoroutineScope(Dispatchers.Main)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val granted = results.values.any { it }
            if (granted) {
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
            val permissions = requiredPermissions()
            val notGranted = permissions.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (notGranted.isEmpty()) {
                startClassification()
            } else {
                permissionLauncher.launch(notGranted.toTypedArray())
            }
        }
    }

    private fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun showPermissionDenied() {
        status.visibility = View.VISIBLE
        status.text = "未获得相册权限，无法扫描照片。请在设置中允许相册访问后重试。"
        scanButton.visibility = View.VISIBLE
    }

    private fun startClassification() {
        scanButton.visibility = View.GONE
        progress.visibility = View.VISIBLE
        status.visibility = View.VISIBLE
        status.text = "正在加载AI模型..."

        scope.launch {
            try {
                val items = withContext(Dispatchers.IO) {
                    Classifier.init(applicationContext)
                    scanAndClassify()
                }

                adapter.submitList(items)
                progress.visibility = View.GONE
                if (items.isEmpty()) {
                    status.text = "未找到可分类的照片。请确认相册中有照片且已授权访问全部照片。"
                } else {
                    status.text = "已分类 ${items.size} 张照片"
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
        val collection = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        val cursor = contentResolver.query(collection, projection, null, null, sortOrder) ?: return result
        cursor.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val uri = ContentUris.withAppendedId(collection, id)
                try {
                    val bmp = loadBitmap(uri) ?: continue
                    val classes = Classifier.classify(bmp)
                    bmp.recycle()
                    if (classes.isNotEmpty()) {
                        val top = classes.first()
                        result.add(PhotoItem(uri.toString(), top.label, top.confidence))
                    }
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