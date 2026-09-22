package com.bootforge.ui

import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.bootforge.core.Format
import com.bootforge.databinding.FragmentHomeBinding
import com.bootforge.vm.Options
import com.bootforge.vm.WorkViewModel
import com.google.android.material.snackbar.Snackbar
import java.util.Locale

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val vm: WorkViewModel by activityViewModels()
    private lateinit var infoAdapter: InfoAdapter

    private val pickImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        runCatching {
            requireContext().contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        vm.importImage(uri, nameOf(uri))
    }

    private val saveImage = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        uri ?: return@registerForActivityResult
        vm.export(uri)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, saved: Bundle?) {
        infoAdapter = InfoAdapter()
        binding.rvInfo.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = infoAdapter
            isNestedScrollingEnabled = false
        }

        binding.btnPick.setOnClickListener { pickImage.launch(arrayOf("*/*")) }
        binding.btnUnpack.setOnClickListener { vm.unpack() }
        binding.btnRepack.setOnClickListener { vm.repack(vm.options.value ?: Options()) }
        binding.btnExport.setOnClickListener {
            val out = vm.lastOutput.value
            if (out == null) {
                Snackbar.make(binding.root, "请先重新打包生成产物", Snackbar.LENGTH_SHORT).show()
            } else {
                saveImage.launch(out.name)
            }
        }
        binding.btnBackup.setOnClickListener {
            vm.backup(binding.etPartition.text.toString().trim().ifBlank { "boot" })
        }
        binding.btnFlash.setOnClickListener {
            vm.flash(binding.etPartition.text.toString().trim().ifBlank { "boot" })
        }

        binding.spinnerFormat.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_dropdown_item,
            Format.values().map { it.label }
        )
        binding.spinnerFormat.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                vm.updateOptions { it.copy(format = Format.values()[pos]) }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        binding.spinnerTarget.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (vm.hasImage()) vm.selectTarget(pos)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        binding.chkVerity.setOnCheckedChangeListener { _, checked ->
            vm.updateOptions { it.copy(keepVerity = checked) }
        }
        binding.chkForceEncrypt.setOnCheckedChangeListener { _, checked ->
            vm.updateOptions { it.copy(keepForceEncrypt = checked) }
        }
        binding.etCmdline.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                vm.updateOptions { it.copy(extraCmdline = s?.toString() ?: "") }
            }
        })

        vm.meta.observe(viewLifecycleOwner) { meta ->
            binding.tvMeta.text = if (meta == null) {
                "未选择镜像"
            } else {
                String.format(Locale.US, "%s · %s · header %s · %.2f MB",
                    meta.name, meta.kind, meta.version, meta.size / 1048576.0)
            }
        }
        vm.infoRows.observe(viewLifecycleOwner) { infoAdapter.submit(it) }
        vm.targets.observe(viewLifecycleOwner) { names ->
            binding.spinnerTarget.adapter = ArrayAdapter(
                requireContext(), android.R.layout.simple_spinner_dropdown_item, names
            )
            binding.rowTarget.visibility = if (names.size > 1) View.VISIBLE else View.GONE
        }
        vm.busy.observe(viewLifecycleOwner) { busy ->
            binding.progress.visibility = if (busy) View.VISIBLE else View.GONE
            listOf(binding.btnPick, binding.btnUnpack, binding.btnRepack, binding.btnExport,
                binding.btnBackup, binding.btnFlash).forEach { it.isEnabled = !busy }
        }
        vm.status.observe(viewLifecycleOwner) { binding.tvStatus.text = it }
        vm.message.observe(viewLifecycleOwner) { msg ->
            msg?.let {
                Snackbar.make(binding.root, it, Snackbar.LENGTH_LONG).show()
                vm.clearMessage()
            }
        }
        vm.lastOutput.observe(viewLifecycleOwner) { file ->
            binding.tvOutput.text = file?.let { "最近产物：${it.name}（${it.length()} 字节）" } ?: "暂无产物"
        }
    }

    private fun nameOf(uri: android.net.Uri): String {
        requireContext().contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return c.getString(idx)
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "boot.img"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
