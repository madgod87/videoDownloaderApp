package com.research.mediaanalyzer.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.research.mediaanalyzer.R
import java.io.File

class LibraryAdapter(
    private var files: List<File>,
    private val onPlay: (File) -> Unit,
    private val onFolder: (File) -> Unit
) : RecyclerView.Adapter<LibraryAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val fileName: TextView = view.findViewById(R.id.itemFileName)
        val playBtn: Button = view.findViewById(R.id.itemPlayBtn)
        val folderBtn: Button = view.findViewById(R.id.itemFolderBtn)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_library, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file = files[position]
        holder.fileName.text = file.name
        holder.playBtn.setOnClickListener { onPlay(file) }
        holder.folderBtn.setOnClickListener { onFolder(file) }
    }

    override fun getItemCount() = files.size

    fun updateFiles(newFiles: List<File>) {
        files = newFiles
        notifyDataSetChanged()
    }
}
