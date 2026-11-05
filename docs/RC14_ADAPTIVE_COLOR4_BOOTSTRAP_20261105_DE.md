# RC14 | Adaptive Color4 Music visuals + notification navigation

Owner requested after hardware confirmation of RC13 four Color4 channels, screen-off continuation, Classic reusability, and notification STOP. Source-only stacked draft based on RC13 `8dcf2aa528a376f14ab139ffbe528ca8d901c200`.

Scope: notification content tap returns to the existing DJ UI without restarting capture; retain proven beat mode and provide explicit opt-in adaptive sound-to-light mode with broad 360-degree hue coverage, smooth RGB changes, bounded brightness from measured RMS, real beats and six spectral bands, 1–4 eligible Color4 independent music reverse sockets, no normal LAN fallback. Persist no hidden account data. Keep per-bulb music_on journals, stop/readback, quarantine and recovery guards unchanged.

Engineering: unit tests + Android compile CI; no hardware interaction or automatic task modifications. No unsigned public release, merge, or signing outside owner-only exact-source CI gate. Manufacturer Classic app modification remains a separate scope.
