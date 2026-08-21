package com.ccompile.lite

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.ccompile.lite.databinding.ItemListBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed class DisplayItem {
    data class Header(val title: String, val iconRes: Int) : DisplayItem()
    data class Entry(
        val file: File,
        val projectType: ProjectType? = null,
        val fromSection: Section = Section.FILES
    ) : DisplayItem()
}

enum class Section { PINNED, RECENT, FILES }

class MyAdapter(
    private val onItemClick: (File) -> Unit,
    private val onItemLongClick: (File) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_FILE = 1
    }

    private var displayItems: List<DisplayItem> = emptyList()

    var selectionMode = false
        private set
    private val selectedFiles = mutableSetOf<File>()
    var onSelectionChanged: ((Int) -> Unit)? = null

    class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.ivSectionIcon)
        val title: TextView = view.findViewById(R.id.tvSectionTitle)
    }

    class FileViewHolder(val binding: ItemListBinding) : RecyclerView.ViewHolder(binding.root)

    override fun getItemViewType(position: Int): Int {
        return when (displayItems[position]) {
            is DisplayItem.Header -> TYPE_HEADER
            else -> TYPE_FILE
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_HEADER) {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_section_header, parent, false)
            HeaderViewHolder(view)
        } else {
            val binding = ItemListBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            FileViewHolder(binding)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = displayItems[position]

        when (item) {
            is DisplayItem.Header -> {
                val hh = holder as HeaderViewHolder
                hh.title.text = item.title
                hh.icon.setImageResource(item.iconRes)
            }
            is DisplayItem.Entry -> {
                val fh = holder as FileViewHolder
                bindFileEntry(fh, item)
            }
        }
    }

    private fun bindFileEntry(holder: FileViewHolder, entry: DisplayItem.Entry) {
        val file = entry.file
        val context = holder.binding.root.context

        holder.binding.tvTitle.text = file.name

        val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.US)
        val dateStr = sdf.format(Date(file.lastModified()))

        if (file.isDirectory) {
            holder.binding.ivIcon.setImageResource(R.drawable.ic_folder)
            holder.binding.tvSubtitle.text = context.getString(R.string.subtitle_folder, dateStr)
        } else {
            holder.binding.ivIcon.setImageResource(getFileIcon(file.name))
            val sizeKb = file.length() / 1024
            holder.binding.tvSubtitle.text = context.getString(R.string.subtitle_file, sizeKb.toString(), dateStr)
        }

        holder.binding.itemRoot.alpha = if (file.name.startsWith(".")) 0.55f else 1.0f

        val pt = entry.projectType
        if (pt != null && file.isDirectory) {
            holder.binding.tvBadge.visibility = View.VISIBLE
            holder.binding.tvBadge.text = pt.label
            val bg = GradientDrawable().apply {
                cornerRadius = 12f * context.resources.displayMetrics.density
                setColor(pt.colorHex)
            }
            holder.binding.tvBadge.background = bg
        } else {
            holder.binding.tvBadge.visibility = View.GONE
        }

        if (selectionMode) {
            holder.binding.checkbox.visibility = View.VISIBLE
            holder.binding.checkbox.isChecked = selectedFiles.contains(file)
            holder.binding.checkbox.setOnClickListener {
                if (holder.binding.checkbox.isChecked) selectedFiles.add(file) else selectedFiles.remove(file)
                onSelectionChanged?.invoke(selectedFiles.size)
            }
            holder.binding.itemRoot.setOnClickListener {
                holder.binding.checkbox.isChecked = !holder.binding.checkbox.isChecked
                if (holder.binding.checkbox.isChecked) selectedFiles.add(file) else selectedFiles.remove(file)
                onSelectionChanged?.invoke(selectedFiles.size)
            }
            holder.binding.itemRoot.setOnLongClickListener { true }
        } else {
            holder.binding.checkbox.visibility = View.GONE
            holder.binding.itemRoot.setOnClickListener { onItemClick(file) }
            holder.binding.itemRoot.setOnLongClickListener { onItemLongClick(file); true }
        }
    }

    override fun getItemCount(): Int = displayItems.size

    fun updateData(
        pinned: List<File>,
        recent: List<File>,
        files: List<File>,
        pinnedSet: Set<String>
    ) {
        val items = mutableListOf<DisplayItem>()

        if (pinned.isNotEmpty()) {
            items.add(DisplayItem.Header("Pinned", R.drawable.ic_pin))
            for (f in pinned) {
                items.add(DisplayItem.Entry(f, ProjectDetector.detect(f), Section.PINNED))
            }
        }

        if (recent.isNotEmpty()) {
            items.add(DisplayItem.Header("Recent", R.drawable.ic_update))
            for (f in recent) {
                items.add(DisplayItem.Entry(f, ProjectDetector.detect(f), Section.RECENT))
            }
        }

        if (files.isNotEmpty()) {
            // Hanya tambahkan header "Files" jika sebelumnya ada section Pinned/Recent
            if (pinned.isNotEmpty() || recent.isNotEmpty()) {
                items.add(DisplayItem.Header("Files", R.drawable.ic_folder))
            }
            for (f in files) {
                val pt = if (f.isDirectory) ProjectDetector.detect(f) else null
                items.add(DisplayItem.Entry(f, pt, Section.FILES))
            }
        }

        displayItems = items
        if (selectionMode) {
            selectedFiles.retainAll(files.toSet() + pinned.toSet() + recent.toSet())
        }
        notifyDataSetChanged()
    }

    fun enterSelectionMode(initialFile: File? = null) {
        selectionMode = true
        selectedFiles.clear()
        if (initialFile != null) selectedFiles.add(initialFile)
        onSelectionChanged?.invoke(selectedFiles.size)
        notifyDataSetChanged()
    }

    fun exitSelectionMode() {
        selectionMode = false
        selectedFiles.clear()
        onSelectionChanged?.invoke(0)
        notifyDataSetChanged()
    }

    fun selectAll() {
        selectedFiles.clear()
        for (item in displayItems) {
            if (item is DisplayItem.Entry) selectedFiles.add(item.file)
        }
        onSelectionChanged?.invoke(selectedFiles.size)
        notifyDataSetChanged()
    }

    fun getSelectedFiles(): List<File> = selectedFiles.toList()
    fun getSelectedCount(): Int = selectedFiles.size

    private fun getFileIcon(fileName: String): Int {
        val name = fileName.lowercase()
        return when {
            name.endsWith(".cpp") || name.endsWith(".c") || name.endsWith(".h") -> R.drawable.ic_code
            name.endsWith(".java") || name.endsWith(".kt") -> R.drawable.ic_code
            name.endsWith(".apk") -> R.drawable.ic_apk
            name.endsWith(".tar") || name.endsWith(".gz") || name.endsWith(".xz") || name.endsWith(".zip") || name.endsWith(".7z") -> R.drawable.ic_file
            else -> R.drawable.ic_file
        }
    }
}