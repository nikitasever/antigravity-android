package com.google.antigravity.model

fun RemoteEnvFilter.matches(session: RemoteSessionItem): Boolean = when (this) {
    RemoteEnvFilter.ALL -> true
    RemoteEnvFilter.CLOUD -> session.hostType.equals("CLOUD", ignoreCase = true)
    RemoteEnvFilter.PC_CR05318 -> session.hostType.equals("PC", ignoreCase = true)
    RemoteEnvFilter.PC_WIN11 -> session.hostType.equals("PC", ignoreCase = true) &&
        session.host.equals(hostTag, ignoreCase = true)
    RemoteEnvFilter.PHONE_LOCAL -> session.hostType.equals("PHONE", ignoreCase = true)
}
