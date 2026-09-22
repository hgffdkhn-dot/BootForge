package com.bootforge.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.bootforge.databinding.FragmentLogBinding
import com.bootforge.util.LogBus
import com.bootforge.vm.WorkViewModel

class LogFragment : Fragment() {

    private var _binding: FragmentLogBinding? = null
    private val binding get() = _binding!!
    private val vm: WorkViewModel by activityViewModels()
    private lateinit var adapter: LogAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentLogBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, saved: Bundle?) {
        adapter = LogAdapter()
        binding.rvLog.apply {
            layoutManager = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
            adapter = this@LogFragment.adapter
        }
        LogBus.lines.observe(viewLifecycleOwner) { lines ->
            adapter.submit(lines)
            binding.tvEmpty.visibility = if (lines.isEmpty()) View.VISIBLE else View.GONE
            if (lines.isNotEmpty()) binding.rvLog.scrollToPosition(lines.size - 1)
        }
        binding.btnClear.setOnClickListener { LogBus.clear() }
        binding.btnCopy.setOnClickListener {
            val text = LogBus.snapshot()
            val cm = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("bootforge", text))
            Toast.makeText(requireContext(), "日志已复制", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
