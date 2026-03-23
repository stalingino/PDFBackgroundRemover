package com.pdfbgremover.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.snackbar.Snackbar
import com.pdfbgremover.android.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private var resultsAdapter: ResultsAdapter? = null

    // File picker: multiple PDFs
    private val pickPdfs = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            val names = uris.map { getFileName(it) }
            viewModel.setSelectedFiles(uris, names)
            showSelectedFiles(names)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupControls()
        observeViewModel()
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
    }

    private fun setupControls() {
        binding.btnSelectFiles.setOnClickListener {
            pickPdfs.launch(arrayOf("application/pdf"))
        }

        binding.btnProcess.setOnClickListener {
            viewModel.processFiles()
        }

        binding.btnReset.setOnClickListener {
            viewModel.reset()
            resetUi()
        }

        binding.seekBarTolerance.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val value = progress / 100f
                viewModel.setTolerance(value)
                binding.tvToleranceValue.text = String.format("%.2f", value)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Set initial tolerance display
        val initialTolerance = viewModel.tolerance.value ?: 0.3f
        binding.seekBarTolerance.progress = (initialTolerance * 100).toInt()
        binding.tvToleranceValue.text = String.format("%.2f", initialTolerance)
    }

    private fun observeViewModel() {
        viewModel.uiState.observe(this) { state ->
            when (state) {
                is UiState.Idle -> {
                    binding.progressContainer.visibility = View.GONE
                    binding.resultsContainer.visibility = View.GONE
                    binding.btnProcess.isEnabled = true
                    binding.btnReset.visibility = View.GONE
                }
                is UiState.Processing -> {
                    binding.progressContainer.visibility = View.VISIBLE
                    binding.resultsContainer.visibility = View.GONE
                    binding.btnProcess.isEnabled = false

                    val p = state.progress
                    binding.tvProgressStatus.text = if (p.totalFiles > 1) {
                        "Processing file ${p.fileIndex + 1}/${p.totalFiles}: ${p.fileName}"
                    } else {
                        "Processing: ${p.fileName}"
                    }
                    if (p.totalPages > 0) {
                        binding.progressBar.max = p.totalPages
                        binding.progressBar.progress = p.page
                        binding.tvProgressPages.text = "Page ${p.page} of ${p.totalPages}"
                    }
                }
                is UiState.Done -> {
                    binding.progressContainer.visibility = View.GONE
                    binding.resultsContainer.visibility = View.VISIBLE
                    binding.btnProcess.isEnabled = true
                    binding.btnReset.visibility = View.VISIBLE
                    showResults(state.results)
                }
                is UiState.Error -> {
                    binding.progressContainer.visibility = View.GONE
                    binding.btnProcess.isEnabled = true
                    Snackbar.make(binding.root, "Error: ${state.message}", Snackbar.LENGTH_LONG).show()
                }
            }
        }

        viewModel.tolerance.observe(this) { value ->
            val progress = (value * 100).toInt()
            if (binding.seekBarTolerance.progress != progress) {
                binding.seekBarTolerance.progress = progress
            }
            binding.tvToleranceValue.text = String.format("%.2f", value)
        }
    }

    private fun showSelectedFiles(names: List<String>) {
        binding.tvSelectedFiles.text = when {
            names.isEmpty() -> ""
            names.size == 1 -> names[0]
            else -> "${names.size} files selected"
        }
        binding.tvSelectedFiles.visibility = if (names.isEmpty()) View.GONE else View.VISIBLE
        binding.btnProcess.isEnabled = names.isNotEmpty()
    }

    private fun showResults(results: List<ProcessedPdf>) {
        resultsAdapter = ResultsAdapter(results) { result ->
            sharePdf(result.outputFile)
        }
        binding.recyclerResults.adapter = resultsAdapter

        // Show "Share All" button only when multiple files
        if (results.size > 1) {
            binding.btnShareAll.visibility = View.VISIBLE
            binding.btnShareAll.setOnClickListener { shareAllPdfs(results.map { it.outputFile }) }
        } else {
            binding.btnShareAll.visibility = View.GONE
        }
    }

    private fun sharePdf(file: File) {
        val uri = FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share PDF"))
    }

    private fun shareAllPdfs(files: List<File>) {
        val uris = ArrayList(files.map { file ->
            FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        })
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "application/pdf"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share all PDFs"))
    }

    private fun handleIncomingIntent(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                intent.data?.let { uri ->
                    val name = getFileName(uri)
                    viewModel.setSelectedFiles(listOf(uri), listOf(name))
                    showSelectedFiles(listOf(name))
                }
            }
            Intent.ACTION_SEND -> {
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (uri != null) {
                    val name = getFileName(uri)
                    viewModel.setSelectedFiles(listOf(uri), listOf(name))
                    showSelectedFiles(listOf(name))
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                if (!uris.isNullOrEmpty()) {
                    val names = uris.map { getFileName(it) }
                    viewModel.setSelectedFiles(uris, names)
                    showSelectedFiles(names)
                }
            }
        }
    }

    private fun getFileName(uri: Uri): String {
        var name = "document.pdf"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                name = cursor.getString(nameIndex)
            }
        }
        return name
    }

    private fun resetUi() {
        binding.tvSelectedFiles.visibility = View.GONE
        binding.tvSelectedFiles.text = ""
        binding.btnProcess.isEnabled = false
        binding.progressContainer.visibility = View.GONE
        binding.resultsContainer.visibility = View.GONE
        binding.btnReset.visibility = View.GONE
        binding.btnShareAll.visibility = View.GONE
        resultsAdapter = null
    }
}
