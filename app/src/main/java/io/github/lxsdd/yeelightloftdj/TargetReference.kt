package io.github.lxsdd.yeelightloftdj

/** Stable target references when device IDs or room names change. */
object TargetReference {
    fun deviceRenamed(target: String, from: String, to: String): String =
        if (target == "Lampe:$from") "Lampe:$to" else target

    fun roomRenamed(target: String, from: String, to: String): String =
        if (target == "Raum:$from") "Raum:$to" else target

    fun deviceRemoved(target: String, id: String): String =
        if (target == "Lampe:$id") "Test (1)" else target

    fun roomRemoved(target: String, room: String): String =
        if (target == "Raum:$room") "Test (1)" else target
}
