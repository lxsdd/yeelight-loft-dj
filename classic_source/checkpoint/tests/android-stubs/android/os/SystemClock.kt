package android.os
object SystemClock { @JvmStatic fun elapsedRealtime(): Long = System.nanoTime() / 1000000 }
