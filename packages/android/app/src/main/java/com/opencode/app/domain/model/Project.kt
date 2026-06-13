package com.opencode.app.domain.model

data class Project(
    val id: String,
    val worktree: String,
    val name: String,
    val vcs: String? = null,
    val iconUrl: String? = null,
    val iconColor: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    val displayName get() = name.ifBlank { worktree.substringAfterLast("/") }
}
