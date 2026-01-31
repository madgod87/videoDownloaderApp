package com.fikfap.downloader

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import java.io.File

class VideoAdapter(
    private var files: List<File>,
    private val onAction: (Action, File) -> Unit,
    private val onSelectionChanged: (Int) -> Unit
) : RecyclerView.Adapter<VideoAdapter.ViewHolder>() {

    enum class Action { PLAY, SHARE, DELETE }

    private val selectedFiles = mutableSetOf<File>()
    var isSelectionMode = false
        set(value) {
            field = value
            if (!value) selectedFiles.clear()
            notifyDataSetChanged()
        }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumbnail: ImageView = view.findViewById(R.id.thumbnailView)
        val fileName: TextView = view.findViewById(R.id.fileNameText)
        val details: TextView = view.findViewById(R.id.fileSizeText)
        val checkbox: CheckBox = view.findViewById(R.id.selectionCheckbox)
        val btnPlay: ImageButton = view.findViewById(R.id.playOption)
        val btnShare: ImageButton = view.findViewById(R.id.shareOption)
        val btnDelete: ImageButton = view.findViewById(R.id.deleteOption)
        val options: View = view.findViewById(R.id.optionsLayout)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_library, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file = files[position]
        holder.fileName.text = file.name
        holder.details.text = "${file.length() / 1024 / 1024} MB | Artifact"
        
        // Load Thumbnail via Glide
        Glide.with(holder.thumbnail.context)
            .asBitmap()
            .load(file)
            .placeholder(android.R.drawable.ic_media_play)
            .into(holder.thumbnail)

        // Selection Mode UI
        holder.checkbox.visibility = if (isSelectionMode) View.VISIBLE else View.GONE
        holder.options.visibility = if (isSelectionMode) View.GONE else View.VISIBLE
        holder.checkbox.isChecked = selectedFiles.contains(file)

        holder.itemView.setOnClickListener {
            if (isSelectionMode) {
                toggleSelection(file)
            } else {
                onAction(Action.PLAY, file)
            }
        }

        holder.itemView.setOnLongClickListener {
            if (!isSelectionMode) {
                isSelectionMode = true
                toggleSelection(file)
                true
            } else false
        }

        holder.checkbox.setOnClickListener { toggleSelection(file) }
        
        holder.btnPlay.setOnClickListener { onAction(Action.PLAY, file) }
        holder.btnShare.setOnClickListener { onAction(Action.SHARE, file) }
        holder.btnDelete.setOnClickListener { onAction(Action.DELETE, file) }
    }

    private fun toggleSelection(file: File) {
        if (selectedFiles.contains(file)) selectedFiles.remove(file)
        else selectedFiles.add(file)
        notifyDataSetChanged()
        onSelectionChanged(selectedFiles.size)
    }

    override fun getItemCount() = files.size

    fun updateData(newFiles: List<File>) {
        files = newFiles
        notifyDataSetChanged()
    }

    fun getSelectedFiles() : List<File> = selectedFiles.toList()
}

