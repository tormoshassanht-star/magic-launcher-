package com.hassan.launcher.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.hassan.launcher.data.IconCache
import com.hassan.launcher.databinding.ItemHiddenAppBinding
import com.hassan.launcher.databinding.SheetAppPickerBinding
import com.hassan.launcher.model.AppInfo

object AppPicker {
    fun show(
        context: Context,
        apps: List<AppInfo>,
        title: String,
        preselected: Collection<String>,
        max: Int,
        onDone: (List<String>) -> Unit,
    ) {
        val selected = LinkedHashSet(preselected)
        val all = apps.sortedBy { it.label.lowercase() }
        var shown = all
        val dialog = BottomSheetDialog(context)
        val b = SheetAppPickerBinding.inflate(LayoutInflater.from(context))
        b.pickerTitle.text = title

        fun updateCount() {
            b.pickerCount.text = "${selected.size} / $max"
        }

        val adapter = object : RecyclerView.Adapter<VH>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                VH(ItemHiddenAppBinding.inflate(LayoutInflater.from(parent.context), parent, false))

            override fun getItemCount() = shown.size

            override fun onBindViewHolder(holder: VH, position: Int) {
                val app = shown[position]
                holder.b.icon.setImageBitmap(IconCache.get(context, app))
                holder.b.label.text = app.label
                holder.b.checkbox.isChecked = app.key in selected
                holder.b.root.setOnClickListener {
                    if (!selected.remove(app.key)) {
                        if (selected.size >= max) {
                            Toast.makeText(context, "Up to $max apps", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        selected.add(app.key)
                    }
                    notifyItemChanged(position)
                    updateCount()
                }
            }
        }
        b.pickerList.layoutManager = LinearLayoutManager(context)
        b.pickerList.adapter = adapter
        b.pickerSearch.doAfterTextChanged {
            val q = it?.toString()?.trim()?.lowercase() ?: ""
            shown = if (q.isEmpty()) all else all.filter { a -> a.label.lowercase().contains(q) || a.packageName.lowercase().contains(q) }
            adapter.notifyDataSetChanged()
        }
        b.pickerDone.setOnClickListener {
            dialog.dismiss()
            onDone(selected.toList())
        }
        updateCount()
        dialog.setContentView(b.root)
        dialog.behavior.peekHeight = context.resources.displayMetrics.heightPixels * 3 / 4
        dialog.show()
    }

    private class VH(val b: ItemHiddenAppBinding) : RecyclerView.ViewHolder(b.root)
}
