package com.example.aiagallery

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

data class Classification(
    val label: String,
    val confidence: Float
)

object Classifier {

    private const val MODEL_PATH = "mobilenet_v2_1.0_224.tflite"
    private const val LABELS_PATH = "labels.txt"
    private const val INPUT_SIZE = 224

    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()
    private var isInitialized = false

    // Reused buffers to reduce GC pressure during batch processing
    private val input: Array<Array<Array<FloatArray>>> =
        Array(1) { Array(INPUT_SIZE) { Array(INPUT_SIZE) { FloatArray(3) } } }
    private val output: Array<FloatArray> = Array(1) { FloatArray(1000) }
    private var pixels: IntArray = IntArray(INPUT_SIZE * INPUT_SIZE)

fun init(context: Context) {
        if (isInitialized) return
        // Always delete old cached model to avoid loading corrupted files from previous versions
        val modelFile = File(context.cacheDir, "model.tflite")
        modelFile.delete()
        context.assets.open(MODEL_PATH).use { input ->
            modelFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        interpreter = Interpreter(
            modelFile,
            Interpreter.Options().apply { numThreads = 4 }
        )
        labels = context.assets.open(LABELS_PATH).use { input ->
            BufferedReader(InputStreamReader(input)).readLines()
        }
        output[0] = FloatArray(labels.size)
        isInitialized = true
    }

    fun classify(bitmap: Bitmap): List<Classification> {
        val interpreter = interpreter ?: return emptyList()
        val resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        try {
            resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
            for (i in 0 until INPUT_SIZE) {
                for (j in 0 until INPUT_SIZE) {
                    val pixel = pixels[i * INPUT_SIZE + j]
                    input[0][i][j][0] = ((pixel shr 16) and 0xFF) / 255.0f
                    input[0][i][j][1] = ((pixel shr 8) and 0xFF) / 255.0f
                    input[0][i][j][2] = (pixel and 0xFF) / 255.0f
                }
            }
            interpreter.run(input, output)
            return output[0]
                .mapIndexed { index, confidence ->
                    Classification(labels.getOrElse(index) { index.toString() }, confidence)
                }
                .sortedByDescending { it.confidence }
                .take(5)
        } finally {
            resized.recycle()
        }
    }

    fun close() {
        interpreter?.close()
        interpreter = null
        isInitialized = false
    }
}