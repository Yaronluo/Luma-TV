package io.lumatv.launcher

import androidx.compose.ui.input.key.*

/** A lazy focus request may still be pending when the next remote confirmation arrives. */
internal class DeferredConfirmation {
    private var pending: String? = null
    private var held = false

    fun handle(event: KeyEvent, target: String, focused: String, activate: (String, Boolean) -> Unit): Boolean {
        if (event.key !in listOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)) {
            if (event.type == KeyEventType.KeyDown && event.key in listOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown)) pending = null
            return false
        }
        if (event.type == KeyEventType.KeyDown) {
            if (pending != null) {
                held = held || event.nativeKeyEvent.repeatCount > 0
                return true
            }
            if (target.isNotEmpty() && target != focused) {
                pending = target
                held = event.nativeKeyEvent.repeatCount > 0
                return true
            }
        } else if (event.type == KeyEventType.KeyUp) {
            pending?.let { id ->
                pending = null
                activate(id, held)
                return true
            }
        }
        return false
    }
}
