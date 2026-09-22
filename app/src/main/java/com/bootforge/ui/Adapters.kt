package com.bootforge.ui

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bootforge.databinding.ItemInfoBinding
import com.bootforge.databinding.ItemInjectBinding
import com.bootforge.databinding.ItemLogBinding
import com.bootforge.databinding.ItemRamdiskBinding
import com.bootforge.vm.InjectItem
import com.bootforge.vm.RamdiskRow

class InfoAdapter : RecyclerView.Adapter<InfoAdapter.Holder>() {

    private val rows = ArrayList<Pair<String, String>>()

    fun submit(list: List<Pair<String, String>>) {
        rows.clear()
        rows.addAll(list)
        notifyDataSetChanged()
    }

    class Holder(val binding: ItemInfoBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemInfoBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.binding.tvLabel.text = rows[position].first
        holder.binding.tvValue.text = rows[position].second
    }

    override fun getItemCount(): Int = rows.size
}

class InjectAdapter(
    private val onRemove: (InjectItem) -> Unit,
    private val onChanged: (InjectItem, String, String) -> Unit
) : RecyclerView.Adapter<InjectAdapter.Holder>() {

    private val items = ArrayList<InjectItem>()

    fun submit(list: List<InjectItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class Holder(val binding: ItemInjectBinding) : RecyclerView.ViewHolder(binding.root) {
        var watcher: TextWatcher? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemInjectBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.watcher?.let {
            holder.binding.etPath.removeTextChangedListener(it)
            holder.binding.etMode.removeTextChangedListener(it)
        }
        holder.binding.tvName.text = item.name
        holder.binding.etPath.setText(item.target)
        holder.binding.etMode.setText(item.mode)
        holder.binding.btnRemove.setOnClickListener { onRemove(item) }

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) = Unit
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                onChanged(item, holder.binding.etPath.text.toString(), holder.binding.etMode.text.toString())
            }
        }
        holder.watcher = watcher
        holder.binding.etPath.addTextChangedListener(watcher)
        holder.binding.etMode.addTextChangedListener(watcher)
    }

    override fun getItemCount(): Int = items.size
}

class RamdiskAdapter(private val onDelete: (RamdiskRow) -> Unit) :
    RecyclerView.Adapter<RamdiskAdapter.Holder>() {

    private val entries = ArrayList<RamdiskRow>()

    fun submit(list: List<RamdiskRow>) {
        entries.clear()
        entries.addAll(list)
        notifyDataSetChanged()
    }

    class Holder(val binding: ItemRamdiskBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemRamdiskBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val e = entries[position]
        holder.binding.tvName.text = e.name
        holder.binding.tvMeta.text = "${e.perms} · ${e.size} 字节"
        holder.binding.btnDelete.setOnClickListener { onDelete(e) }
    }

    override fun getItemCount(): Int = entries.size
}

class LogAdapter : RecyclerView.Adapter<LogAdapter.Holder>() {

    private val lines = ArrayList<String>()

    fun submit(list: List<String>) {
        lines.clear()
        lines.addAll(list)
        notifyDataSetChanged()
    }

    class Holder(val binding: ItemLogBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemLogBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.binding.tvLine.text = lines[position]
    }

    override fun getItemCount(): Int = lines.size
}
