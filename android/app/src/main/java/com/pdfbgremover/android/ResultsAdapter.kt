package com.pdfbgremover.android

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout

class ResultsAdapter(
    private val results: List<ProcessedPdf>,
    private val onShare: (ProcessedPdf) -> Unit
) : RecyclerView.Adapter<ResultsAdapter.ViewHolder>() {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val fileName: TextView = view.findViewById(R.id.tvFileName)
        val pageCount: TextView = view.findViewById(R.id.tvPageCount)
        val previewImage: ImageView = view.findViewById(R.id.ivPreview)
        val tabLayout: TabLayout = view.findViewById(R.id.tabLayout)
        val shareButton: MaterialButton = view.findViewById(R.id.btnShare)
        val detectedColor: View = view.findViewById(R.id.viewDetectedColor)

        var currentResult: ProcessedPdf? = null
        var showingOriginal = true

        init {
            tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    showingOriginal = tab.position == 0
                    updatePreview()
                }
                override fun onTabUnselected(tab: TabLayout.Tab?) {}
                override fun onTabReselected(tab: TabLayout.Tab?) {}
            })
        }

        fun updatePreview() {
            val result = currentResult ?: return
            val bitmaps = if (showingOriginal) result.originalThumbnails else result.processedThumbnails
            if (bitmaps.isNotEmpty()) {
                previewImage.setImageBitmap(bitmaps[0])
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_pdf_result, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val result = results[position]
        holder.currentResult = result
        holder.showingOriginal = true

        holder.fileName.text = result.fileName
        holder.pageCount.text = "${result.pageCount} page${if (result.pageCount != 1) "s" else ""}"
        holder.detectedColor.setBackgroundColor(result.detectedColor)

        // Select "Before" tab
        holder.tabLayout.getTabAt(0)?.select()
        holder.updatePreview()

        holder.shareButton.setOnClickListener { onShare(result) }
    }

    override fun getItemCount() = results.size
}
