package com.fikfap.downloader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.fikfap.downloader.databinding.ActivityLibraryBinding
import java.io.File

class LibraryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLibraryBinding
    private lateinit var adapter: VideoAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLibraryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        loadArtifacts()
    }

    private fun setupUI() {
        adapter = VideoAdapter(emptyList(), { action, file ->
            when (action) {
                VideoAdapter.Action.PLAY -> openVideoFile(file)
                VideoAdapter.Action.SHARE -> shareFiles(listOf(file))
                VideoAdapter.Action.DELETE -> deleteFile(file)
            }
        }, { count ->
            updateSelectionMode(count)
        })

        binding.libraryRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.libraryRecyclerView.adapter = adapter

        binding.cancelSelectionButton.setOnClickListener {
            adapter.isSelectionMode = false
            binding.multiSelectActionCard.visibility = View.GONE
        }

        binding.shareSelectionButton.setOnClickListener {
            shareFiles(adapter.getSelectedFiles())
            adapter.isSelectionMode = false
            binding.multiSelectActionCard.visibility = View.GONE
        }
        
        binding.toolbar.setNavigationIcon(android.R.drawable.ic_menu_revert)
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun updateSelectionMode(count: Int) {
        if (count > 0) {
            binding.multiSelectActionCard.visibility = View.VISIBLE
            binding.selectionCountText.text = "$count artifacts selected"
        } else {
            binding.multiSelectActionCard.visibility = View.GONE
        }
    }

    private fun loadArtifacts() {
        val storageDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        val files = storageDir?.listFiles { file -> file.extension == "mp4" }?.toList() ?: emptyList()
        adapter.updateData(files.sortedByDescending { it.lastModified() })
        binding.emptyStateText.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openVideoFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.provider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No video player found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareFiles(files: List<File>) {
        if (files.isEmpty()) return
        
        val uris = ArrayList<Uri>()
        files.forEach { file ->
            uris.add(FileProvider.getUriForFile(this, "$packageName.provider", file))
        }

        val intent = Intent().apply {
            action = if (uris.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND
            type = "video/mp4"
            if (uris.size > 1) {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            } else {
                putExtra(Intent.EXTRA_STREAM, uris[0])
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share Research Artifacts"))
    }

    private fun deleteFile(file: File) {
        if (file.delete()) {
            Toast.makeText(this, "Artifact purged", Toast.LENGTH_SHORT).show()
            loadArtifacts()
        }
    }
}
