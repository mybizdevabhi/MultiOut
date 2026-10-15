package com.livemic.karaoke.audio

enum class RoutingMode(val title: String, val subtitle: String) {
    HYBRID_AUTO(
        "Hybrid Auto (Samsung Dual Audio & Separate App Sound)",
        "Locks input to phone/wired mic; leaves output unbound (0) so Samsung Dual Audio & Separate App Sound route cleanly."
    ),
    MANUAL_PICKER(
        "Manual Device Picker",
        "Explicitly bind input and output stream IDs to specific hardware interfaces."
    ),
    SYSTEM_DEFAULT(
        "System Default",
        "Lets Android OS manage both input and output device routing."
    )
}
