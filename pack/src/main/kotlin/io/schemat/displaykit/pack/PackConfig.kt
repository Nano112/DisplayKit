package io.schemat.displaykit.pack

data class PackConfig(
    val port: Int = 8080,
    val bindAddress: String = "localhost",
    val namespace: String = "displaykit",
    val packFormat: Int = 75,  // MC 1.21.11
    val autoSendOnJoin: Boolean = true,
    val packDescription: String = "DisplayKit Resource Pack"
)
