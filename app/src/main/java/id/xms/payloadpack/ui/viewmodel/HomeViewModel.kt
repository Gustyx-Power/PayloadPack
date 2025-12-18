package id.xms.payloadpack.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.xms.payloadpack.core.ZipHelper
import id.xms.payloadpack.data.FileRepository
import id.xms.payloadpack.data.Project
import id.xms.payloadpack.data.SourceFile
import id.xms.payloadpack.native.NativeLib
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * State for a single extraction operation
 */
sealed class ExtractionState {
    data object Idle : ExtractionState()
    data class Extracting(
        val sourceFile: SourceFile,
        val progress: Float,
        val currentFile: String
    ) : ExtractionState()
    data class Parsing(val payloadPath: String) : ExtractionState()
    data class Success(val projectPath: String) : ExtractionState()
    data class Error(val message: String) : ExtractionState()
}

/**
 * Overall UI state for the Home screen
 */
data class HomeUiState(
    val isLoading: Boolean = true,
    val sources: List<SourceFile> = emptyList(),
    val projects: List<Project> = emptyList(),
    val selectedTabIndex: Int = 0,
    val extractionState: ExtractionState = ExtractionState.Idle,
    val showDeleteDialog: Boolean = false,
    val projectToDelete: Project? = null,
    val rustLoaded: Boolean = false,
    val errorMessage: String? = null
)

/**
 * HomeViewModel manages the workspace-based UI state.
 * Auto-scans for sources and projects when initialized.
 */
class HomeViewModel : ViewModel() {

    companion object {
        private const val TAG = "HomeViewModel"
    }

    private val repository = FileRepository()

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        Log.d(TAG, "HomeViewModel initialized")
        loadNativeLibrary()
        refreshAll()
    }

    /**
     * Load the Rust native library
     */
    private fun loadNativeLibrary() {
        val loaded = NativeLib.loadLibrary()
        Log.d(TAG, "Native library loaded: $loaded")
        _uiState.value = _uiState.value.copy(rustLoaded = loaded)
    }

    /**
     * Refresh both sources and projects
     */
    fun refreshAll() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            
            try {
                val sources = repository.scanSources()
                val projects = repository.scanProjects()
                
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    sources = sources,
                    projects = projects
                )
                
                Log.d(TAG, "Scan complete: ${sources.size} sources, ${projects.size} projects")
            } catch (e: Exception) {
                Log.e(TAG, "Scan failed: ${e.message}", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Scan failed: ${e.message}"
                )
            }
        }
    }

    /**
     * Refresh only sources
     */
    fun refreshSources() {
        viewModelScope.launch {
            try {
                val sources = repository.scanSources()
                _uiState.value = _uiState.value.copy(sources = sources)
            } catch (e: Exception) {
                Log.e(TAG, "Source scan failed: ${e.message}")
            }
        }
    }

    /**
     * Refresh only projects
     */
    fun refreshProjects() {
        viewModelScope.launch {
            try {
                val projects = repository.scanProjects()
                _uiState.value = _uiState.value.copy(projects = projects)
            } catch (e: Exception) {
                Log.e(TAG, "Project scan failed: ${e.message}")
            }
        }
    }

    /**
     * Change the selected tab
     */
    fun selectTab(index: Int) {
        _uiState.value = _uiState.value.copy(selectedTabIndex = index)
        
        // Refresh the relevant list when switching tabs
        when (index) {
            0 -> refreshSources()
            1 -> refreshProjects()
        }
    }

    /**
     * Start unpacking a source file
     */
    fun unpackSource(source: SourceFile) {
        Log.d(TAG, "Unpacking: ${source.name}")
        
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                extractionState = ExtractionState.Extracting(
                    sourceFile = source,
                    progress = 0f,
                    currentFile = "Starting..."
                )
            )

            try {
                if (source.isZip) {
                    // Extract ZIP file
                    val result = ZipHelper.extractRomZip(
                        zipPath = source.path,
                        extractAll = false,
                        onProgress = { current, total, file ->
                            val progress = if (total > 0) current.toFloat() / total else 0f
                            _uiState.value = _uiState.value.copy(
                                extractionState = ExtractionState.Extracting(
                                    sourceFile = source,
                                    progress = progress,
                                    currentFile = file
                                )
                            )
                        }
                    )

                    when (result) {
                        is ZipHelper.ExtractResult.Success -> {
                            Log.d(TAG, "Extraction successful: ${result.extractedDir}")
                            
                            if (result.payloadPath != null) {
                                // Parse the payload
                                _uiState.value = _uiState.value.copy(
                                    extractionState = ExtractionState.Parsing(result.payloadPath)
                                )
                                
                                // TODO: Parse payload here if needed
                                
                                _uiState.value = _uiState.value.copy(
                                    extractionState = ExtractionState.Success(result.extractedDir),
                                    selectedTabIndex = 1  // Switch to Projects tab
                                )
                                
                                // Refresh projects list
                                refreshProjects()
                            } else {
                                _uiState.value = _uiState.value.copy(
                                    extractionState = ExtractionState.Error(
                                        "No payload.bin found in ZIP"
                                    )
                                )
                            }
                        }
                        
                        is ZipHelper.ExtractResult.Error -> {
                            Log.e(TAG, "Extraction failed: ${result.message}")
                            _uiState.value = _uiState.value.copy(
                                extractionState = ExtractionState.Error(result.message)
                            )
                        }
                    }
                } else {
                    // Direct payload.bin - copy to work directory
                    val projectPath = repository.createProject(source.name)
                    
                    if (projectPath != null) {
                        // Copy the file
                        // TODO: Implement direct copy
                        _uiState.value = _uiState.value.copy(
                            extractionState = ExtractionState.Success(projectPath),
                            selectedTabIndex = 1
                        )
                        refreshProjects()
                    } else {
                        _uiState.value = _uiState.value.copy(
                            extractionState = ExtractionState.Error("Failed to create project")
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unpack failed: ${e.message}", e)
                _uiState.value = _uiState.value.copy(
                    extractionState = ExtractionState.Error("Unpack failed: ${e.message}")
                )
            }
        }
    }

    /**
     * Clear extraction state (dismiss progress/error)
     */
    fun clearExtractionState() {
        _uiState.value = _uiState.value.copy(
            extractionState = ExtractionState.Idle
        )
    }

    /**
     * Show delete confirmation dialog
     */
    fun showDeleteDialog(project: Project) {
        _uiState.value = _uiState.value.copy(
            showDeleteDialog = true,
            projectToDelete = project
        )
    }

    /**
     * Hide delete confirmation dialog
     */
    fun hideDeleteDialog() {
        _uiState.value = _uiState.value.copy(
            showDeleteDialog = false,
            projectToDelete = null
        )
    }

    /**
     * Confirm and execute project deletion
     */
    fun confirmDelete() {
        val project = _uiState.value.projectToDelete ?: return
        
        viewModelScope.launch {
            Log.d(TAG, "Deleting project: ${project.name}")
            
            val success = repository.deleteProject(project.path)
            
            if (success) {
                Log.d(TAG, "Project deleted successfully")
                refreshProjects()
            } else {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Failed to delete project"
                )
            }
            
            hideDeleteDialog()
        }
    }

    /**
     * Clear error message
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }
}
