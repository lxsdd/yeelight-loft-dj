# Color4 Dual offline development and per-band spectrum display

User confirmed Couchtisch and Flur independently passed single-bulb Music-Mode tests, restore and subsequent Yeelight Classic control. Real dual lighting remains untested and requires separate approval.

The dual protocol is prepared with a compile-time LIVE_TEST_APPROVED=false gate. Eligibility requires exactly two independent color4 IPs, supported set_music/set_rgb, no quarantine and no unfinished recovery. Its shared budget is 40 combined RGB attempts in 10 seconds with at least 500 ms between frames. Both reverse music streams must be active for any frame. Never fallback to conventional LAN color writes.

AudioSpectrumDisplay is a visualization-only per-band adaptive normalizer with silence gating; raw bands remain used for measured bass beats, accents and transport color selection. No simulated acoustic content.

No signing, live lamp traffic, firmware updates, merges, or activation of dual tests is authorized in this source development block.
