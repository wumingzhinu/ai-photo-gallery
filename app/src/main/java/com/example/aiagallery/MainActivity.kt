package com.example.aiagallery

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
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
import java.io.InputStream

class MainActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var adapter: PhotoAdapter
    private val scope = CoroutineScope(Dispatchers.Main)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startClassification()
            else status.text = "需要相册权限才能分类照片"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        recycler = findViewById(R.id.recycler)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)

        adapter = PhotoAdapter(this)
        recycler.layoutManager = GridLayoutManager(this, 3)
        recycler.adapter = adapter

        checkAndRequestPermission()
    }

    private val requiredPermission: String
        get() = if (android.os.Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    private fun checkAndRequestPermission() {
        val hasPermission = ContextCompat.checkSelfPermission(
            this, requiredPermission
        ) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) startClassification()
        else permissionLauncher.launch(requiredPermission)
    }

    private fun startClassification() {
        Classifier.init(applicationContext)
        progress.visibility = android.view.View.VISIBLE
        status.text = "正在扫描并分类照片..."

        scope.launch {
            val items = withContext(Dispatchers.IO) { scanAndClassify() }
            progress.visibility = android.view.View.GONE
            adapter.submitList(items)
            status.text = "共 ${items.size} 张照片已分类"
        }
    }

    private fun scanAndClassify(): List<PhotoItem> {
        val result = mutableListOf<PhotoItem>()
        val collection = if (android.os.Build.VERSION.SDK_INT >= 29) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATA)
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        contentResolver.query(collection, projection, null, null, sortOrder)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val dataPath = cursor.getString(dataCol)
                if (dataPath == null) continue
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
        val input: InputStream = contentResolver.openInputStream(uri) ?: return null
        input.use { ins ->
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeStream(ins, null, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) sample *= 2
            val opts = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = sample
            }
            return android.graphics.BitmapFactory.decodeStream(contentResolver.openInputStream(uri), null, opts)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Classifier.close()
    }
}