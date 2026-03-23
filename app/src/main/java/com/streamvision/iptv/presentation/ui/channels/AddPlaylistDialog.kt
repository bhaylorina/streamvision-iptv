package com.streamvision.iptv.presentation.ui.channels

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.streamvision.iptv.R

class AddPlaylistDialog(
    private val onConfirm: (name: String, url: String) -> Unit
) : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_add_playlist, null)

        val tilName = view.findViewById<TextInputLayout>(R.id.til_playlist_name)
        val etName = view.findViewById<TextInputEditText>(R.id.et_playlist_name)
        val tilUrl = view.findViewById<TextInputLayout>(R.id.til_playlist_url)
        val etUrl = view.findViewById<TextInputEditText>(R.id.et_playlist_url)

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.add_playlist)
            .setView(view)
            .setPositiveButton(R.string.add) { _, _ ->
                val name = etName.text?.toString()?.trim() ?: ""
                val url = etUrl.text?.toString()?.trim() ?: ""

                var valid = true
                if (name.isEmpty()) {
                    tilName.error = getString(R.string.error_name_required)
                    valid = false
                } else {
                    tilName.error = null
                }
                if (url.isEmpty()) {
                    tilUrl.error = getString(R.string.error_url_required)
                    valid = false
                } else {
                    tilUrl.error = null
                }

                if (valid) onConfirm(name, url)
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
    }
}
