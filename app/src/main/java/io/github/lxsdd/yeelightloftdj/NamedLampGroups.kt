package io.github.lxsdd.yeelightloftdj

object NamedLampGroups {
    fun validName(name: String): Boolean =
        name.isNotBlank() && name.length <= 40 &&
            name.none { it.isISOControl() || it == ':' }

    fun migrateId(
        groups: Map<String, Set<String>>, oldId: String, newId: String,
    ): Map<String, Set<String>> = groups.mapValues { (_, ids) ->
        if (oldId in ids) (ids - oldId) + newId else ids
    }

    fun removeId(groups: Map<String, Set<String>>, id: String) =
        groups.mapValues { (_, ids) -> ids - id }
}
