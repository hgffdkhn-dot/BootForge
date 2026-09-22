package com.bootforge.util

import androidx.lifecycle.MutableLiveData
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tiny in-memory log sink shared by the whole app.
 */
object LogBus {

    private val buffer = ArrayList<String>()
    val lines = MutableLiveData<List<String>>(emptyList())

    @Synchronized
    fun add(message: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        buffer.add("[$stamp] $message")
        if (buffer.size > 800) buffer.removeAt(0)
        lines.postValue(buffer.toList())
    }

    @Synchronized
    fun clear() {
        buffer.clear()
        lines.postValue(emptyList())
    }

    @Synchronized
    fun snapshot(): String = buffer.joinToString("\n")
}
