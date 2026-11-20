package cz.tapdrop.app.transfer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class TransferUi(
    val active: Boolean = false,
    val upload: Boolean = true,
    val phase: String = "",
    val fileName: String = "",
    val fileIndex: Int = 0,
    val fileCount: Int = 0,
    val done: Long = 0,
    val total: Long = 0,
    val speed: Long = 0,          // B/s
    val etaSec: Long = -1,
) {
    val fraction: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
}

data class TransferResult(
    val ok: Boolean,
    val title: String,
    val detail: String = "",
    val time: Long = System.currentTimeMillis(),
)

/** Sdílený stav mezi službou (která přenáší) a UI (které ho zobrazuje). */
object TransferBus {
    private val _ui = MutableStateFlow(TransferUi())
    val ui: StateFlow<TransferUi> = _ui

    private val _result = MutableStateFlow<TransferResult?>(null)
    val result: StateFlow<TransferResult?> = _result

    fun update(u: TransferUi) { _ui.value = u }
    fun idle() { _ui.value = TransferUi() }
    fun setResult(r: TransferResult?) { _result.value = r }
}

/** Počítá rychlost a zbývající čas. */
class Speedometer(private val startDone: Long = 0) {
    private val start = System.nanoTime()
    private var lastT = start
    private var lastDone = startDone
    private var speed = 0.0

    fun sample(done: Long, total: Long): Pair<Long, Long> {
        val now = System.nanoTime()
        val dt = (now - lastT) / 1e9
        if (dt >= 0.4) {
            val inst = (done - lastDone) / dt
            speed = if (speed == 0.0) inst else speed * 0.6 + inst * 0.4
            lastT = now
            lastDone = done
        } else if (speed == 0.0) {
            val el = (now - start) / 1e9
            if (el > 0.2) speed = (done - startDone) / el
        }
        val eta = if (speed > 1) ((total - done) / speed).toLong() else -1L
        return speed.toLong() to eta
    }
}
