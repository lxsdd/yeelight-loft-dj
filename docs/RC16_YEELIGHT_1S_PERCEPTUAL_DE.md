# RC16 – Yeelight 1S Color: Helligkeit ist nicht AUS

User requirement: a higher threshold for noticeable music pulses on Yeelight 1S, because 1 % is still visibly ON and small changes appear weak. Handle model color4/color6 conservatively. The manufacturer's published white-mode luminous range is 40–800 lm for YLDP13YL, NOT a calibrated RGB brightness mapping. Yeelight LAN control documents brightness as 1–100; true OFF is a separate command, not a dimming level.

Implementation scope: a separate 1S perceptual response, strong audio-qualified peaks and calmer sustained background, no fake darkness, finite rate and measured audio only. Expose configurable pulse threshold in the normal Live DJ panel. Other models retain the generic response. Keep four sockets / N qualified device protection, original Classic usage and all music_on recovery journals. Manual brightness controls explicitly label 1% as ON.

Source-only stacked draft on verified RC15. CI tests and exact-head owner-only signed test gating. No device writes, firmware edits, release or merge.
