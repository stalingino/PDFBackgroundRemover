package com.pdfbgremover.android

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

sealed class UiState {
    object Idle : UiState()
    data class Processing(val progress: ProcessingProgress) : UiState()
    data class Done(val results: List<ProcessedPdf>) : UiState()
    data class Error(val message: String) : UiState()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableLiveData<UiState>(UiState.Idle)
    val uiState: LiveData<UiState> = _uiState

    private val _tolerance = MutableLiveData(0.3f)
    val tolerance: LiveData<Float> = _tolerance

    private var _selectedUris = mutableListOf<Uri>()
    private var _selectedNames = mutableListOf<String>()

    fun setTolerance(value: Float) {
        _tolerance.value = value
    }

    fun setSelectedFiles(uris: List<Uri>, names: List<String>) {
        _selectedUris.clear()
        _selectedUris.addAll(uris)
        _selectedNames.clear()
        _selectedNames.addAll(names)
    }

    fun processFiles() {
        if (_selectedUris.isEmpty()) return

        val context = getApplication<Application>()
        val uris = _selectedUris.toList()
        val names = _selectedNames.toList()
        val tol = _tolerance.value ?: 0.3f

        viewModelScope.launch {
            _uiState.value = UiState.Processing(
                ProcessingProgress(0, uris.size, 0, 0, names.firstOrNull() ?: "")
            )
            try {
                val results = PdfProcessor.processMultiplePdfs(
                    context = context,
                    uris = uris,
                    fileNames = names,
                    tolerance = tol,
                    onProgress = { progress ->
                        _uiState.postValue(UiState.Processing(progress))
                    }
                )
                _uiState.value = UiState.Done(results)
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun reset() {
        _selectedUris.clear()
        _selectedNames.clear()
        _uiState.value = UiState.Idle
    }
}
