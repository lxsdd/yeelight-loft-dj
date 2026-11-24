package android.os
class Looper {
    companion object { @JvmStatic fun getMainLooper()=Looper() }
}
class Handler(val looper:Looper)
