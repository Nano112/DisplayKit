package io.schemat.displaykit.platform

interface TextInput {
    fun requestInput(player: PlayerRef, currentValue: String, callback: (String?) -> Unit)
}
