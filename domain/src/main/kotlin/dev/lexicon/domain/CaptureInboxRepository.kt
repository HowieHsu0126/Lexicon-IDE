package dev.lexicon.domain

import kotlinx.coroutines.flow.Flow

/** List organization only. Archived captures remain available to cards, analysis and explicit jobs. */
interface CaptureInboxRepository {
    /** Missing or unreadable metadata never hides an existing capture. */
    val archivedCaptureIds: Flow<Set<String>>

    /** Returns false when the capture no longer exists. Does not save, delete or edit its material. */
    suspend fun setArchived(captureId: String, archived: Boolean): Boolean
}
