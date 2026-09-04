package com.example.aiagallery

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import java.util.Locale

data class PhotoItem(
    val uri: String,
    val label: String,
    val confidence: Float
)

class PhotoAdapter(
    private val context: Context,
    private var items: List<PhotoItem> = emptyList()
) : RecyclerView.Adapter<PhotoAdapter.VH>() {

    fun submitList(newItems: List<PhotoItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val photo: ImageView = view.findViewById(R.id.photo)
        val label: TextView = view.findViewById(R.id.label)
        val confidence: TextView = view.findViewById(R.id.confidence)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(context).inflate(R.layout.item_classification, parent, false)
        return VH(view)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.photo.load(item.uri)
        holder.label.text = item.label
        holder.confidence.text = String.format(Locale.ROOT, "%.0f%%", item.confidence * 100)
    }
}