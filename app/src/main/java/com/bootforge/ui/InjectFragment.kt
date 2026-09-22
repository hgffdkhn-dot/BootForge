package com.bootforge.ui

import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.bootforge.core.Format
import com.bootforge.databinding.FragmentInjectBinding
import com.bootforge.vm.InjectItem
import com.bootforge.vm.RamdiskRow
import com.bootforge.vm.Options
import com.bootforge.vm.WorkViewModel
import com.google.android.material.snackbar.Snackbar

class InjectFragment : Fragment() {

    private var _binding: FragmentInjectBinding? = null
    private val binding get() = _binding!!
    private val vm: WorkViewModel by activityViewModels()

    private val items = ArrayList<InjectItem>()
    private lateinit var injectAdapter: InjectAdapter
    private lateinit var ramdiskAdapter: RamdiskAdapter
    private var allEntries: List<RamdiskRow> = emptyList()
    private var nextId = 1L

    /** 列表默认折叠、展开后分页渲染，避免上万条目一次性 layout 造成跳转卡顿。 */
    private var expanded = false
    private var shown = PAGE_SIZE

    private companion object {
        const val PAGE_SIZE = 50
    }

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { uri ->
            items.add(InjectItem(nextId++, nameOf(uri), uri, target = nameOf(uri)))
        }
        injectAdapter.submit(items)
        binding.tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentInjectBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, saved: Bundle?) {
        injectAdapter = InjectAdapter(
            onRemove = { item ->
                items.remove(item)
                injectAdapter.submit(items)
                binding.tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            },
            onChanged = { item, target, mode ->
                item.target = target
                item.mode = mode
            }
        )
        binding.rvInject.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = injectAdapter
            isNestedScrollingEnabled = false
        }
        injectAdapter.submit(items)

        ramdiskAdapter = RamdiskAdapter { entry -> vm.removeRamdiskFile(entry.name) }
        binding.rvRamdisk.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = ramdiskAdapter
            isNestedScrollingEnabled = false
        }

        binding.btnAddFiles.setOnClickListener { pickFiles.launch(arrayOf("*/*")) }
        binding.btnClear.setOnClickListener {
            items.clear()
            injectAdapter.submit(items)
            binding.tvEmpty.visibility = View.VISIBLE
        }
        binding.btnInject.setOnClickListener {
            if (items.isEmpty()) {
                Snackbar.make(binding.root, "请先添加要注入的文件", Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            vm.inject(items.toList(), vm.options.value ?: Options())
        }

        binding.spinnerFormat.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_dropdown_item,
            Format.values().map { it.label }
        )
        binding.spinnerFormat.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                vm.updateOptions { it.copy(format = Format.values()[pos]) }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        binding.etFilter.setOnEditorActionListener { _, _, _ -> applyFilter(); true }
        binding.btnFilter.setOnClickListener {
            shown = PAGE_SIZE
            applyFilter()
        }
        binding.btnToggleList.setOnClickListener { toggleList() }
        binding.btnLoadMore.setOnClickListener {
            shown += PAGE_SIZE
            applyFilter()
        }

        vm.ramdiskRows.observe(viewLifecycleOwner) { entries ->
            allEntries = entries
            applyFilter()
        }
        vm.busy.observe(viewLifecycleOwner) { busy ->
            binding.progress.visibility = if (busy) View.VISIBLE else View.GONE
            binding.btnInject.isEnabled = !busy
            binding.btnAddFiles.isEnabled = !busy
        }
        vm.message.observe(viewLifecycleOwner) { msg ->
            msg?.let {
                Snackbar.make(binding.root, it, Snackbar.LENGTH_LONG).show()
                vm.clearMessage()
            }
        }
        vm.status.observe(viewLifecycleOwner) { binding.tvStatus.text = it }
    }

    /** 折叠状态下完全不提交数据，保证页面切换瞬间完成。 */
    private fun applyFilter() {
        val total = vm.ramdiskRowCount()
        val query = binding.etFilter.text.toString().trim()
        val matched: List<RamdiskRow> = if (query.isEmpty()) {
            allEntries
        } else {
            allEntries.filter { it.name.contains(query, true) }
        }
        binding.tvRamdiskCount.text = buildString {
            append("ramdisk 文件：")
            append(matched.size)
            append(" / ")
            append(total)
        }
        // 已导入镜像但列表为空：说明 ramdisk 过大被跳过解析，给出解释
        binding.tvNotice.visibility =
            if (allEntries.isEmpty() && total == 0 && vm.hasImage()) View.VISIBLE else View.GONE

        if (!expanded) {
            binding.rvRamdisk.visibility = View.GONE
            binding.btnLoadMore.visibility = View.GONE
            binding.btnToggleList.contentDescription = getString(R.string.expand_list)
            return
        }

        binding.rvRamdisk.visibility = View.VISIBLE
        val page = matched.take(shown)
        ramdiskAdapter.submit(page)
        val rest = matched.size - page.size
        binding.btnLoadMore.apply {
            visibility = View.VISIBLE
            text = if (rest > 0) getString(R.string.load_more, rest) else getString(R.string.no_more)
            isEnabled = rest > 0
        }
        binding.btnToggleList.contentDescription = getString(R.string.collapse_list)
    }

    private fun toggleList() {
        expanded = !expanded
        if (expanded) shown = PAGE_SIZE
        applyFilter()
    }

    private fun nameOf(uri: android.net.Uri): String {
        requireContext().contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return c.getString(idx)
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
