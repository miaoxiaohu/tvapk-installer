package com.miaoxiaohu.tvinstall

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Observable state of the upload service. The activity and the service share one process,
 * so a static bridge is simpler and more reliable than a broadcast.
 */
object ServerState {

    const val STOPPED = 0
    const val STARTING = 1
    const val RUNNING = 2
    const val FAILED = 3

    @Volatile
    var status: Int = STOPPED
        private set

    @Volatile
    var port: Int = 0
        private set

    @Volatile
    var error: String? = null
        private set

    @Volatile
    var startedAt: Long = 0L
        private set

    @Volatile
    var uploadedCount: Long = 0L
        private set

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun update(newStatus: Int, newPort: Int, newError: String?) {
        status = newStatus
        port = newPort
        error = newError
        if (newStatus == RUNNING) startedAt = System.currentTimeMillis()
        notifyListeners()
    }

    fun noteUpload() {
        uploadedCount++
    }

    fun reset() {
        uploadedCount = 0
    }

    fun notifyListeners() {
        main.post {
            for (listener in listeners) {
                runCatching { listener() }
            }
        }
    }
}