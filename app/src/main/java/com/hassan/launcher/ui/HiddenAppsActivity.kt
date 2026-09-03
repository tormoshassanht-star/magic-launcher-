package com.hassan.launcher.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hassan.launcher.data.AppRepository
import com.hassan.launcher.data.IconCache
import com.hassan.launcher.data.Prefs
import com.hassan.launcher.databinding.ActivityListBinding
import com.hassan.launcher.databinding.ItemHiddenAppBinding
import com.hassan.launcher.model.AppInfo

class HiddenAppsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private val hidden = LinkedHashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val b = ActivityListBinding.inflate(layoutInflater)
        setContentView(b.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        prefs = Prefs(this)
        AppRepository.init(this)
        IconCache.configure(this, prefs.iconShape, prefs.iconSize)
        hidden.addAll(prefs.hidden)
        val apps = AppRepository.apps.value.sortedBy { it.label.lowercase() }
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = Adapter(apps)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private inner class Adapter(private val apps: List<AppInfo>) : RecyclerView.Adapter<Adapter.VH>() {
        inner class VH(val b: ItemHiddenAppBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemHiddenAppBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = apps.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val app = apps[position]
            holder.b.icon.setImageBitmap(IconCache.get(this@HiddenAppsActivity, app))
            holder.b.label.text = app.label
            holder.b.checkbox.isChecked = app.key in hidden
            holder.b.root.setOnClickListener {
                if (!hidden.remove(app.key)) hidden.add(app.key)
                prefs.hidden = hidden
                notifyItemChanged(position)
            }
        }
    }
}
