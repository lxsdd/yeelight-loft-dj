# RC12 – wirklich nutzbarer Color4-Live-DJ-Modus und Classic-Begleitung

Basis: e692503afd181f7faae8e336ef165a119ea263e5 / RC11 #63, optisch und über music_on=0 geprüfter Music-Rückkanal aus RC9-RC11. Nutzerbericht: rund 130 BPM, echte Beats nur teilweise erkannt, BPM-Anzeige leer; MIC+Auto-Gain am besten.

Scope: robuste BPM-Ermittlung auch bei ausgelassenen Onsets, keine vollständige Beat-Historievernichtung durch Auto-Gain; benutzerinitiierter länger laufender **Einzel-Color4**-Modus ausschließlich über den bestehenden nachgewiesenen set_music-Backchannel, explizit stoppbar, mit Lebenszyklus-/Audio-Stopp, ohne Quota-LAN-RGB-Fallback oder Freigabe mehrerer Lampen. Kompatible Begleit-App-Integration Yeelight Classic (separate unveränderte Original-App, expliziter Übergang); keine proprietären Herstellerbits im öffentlichen GitHub, keine Übernahme fremder Tokens, keine unsignierte Patch-Installation.

Vor Product-Commits: private Ownership-Registry mit GitHub-CAS und unabhängigen Readback. Danach Android Unit-Tests + Debug CI, exakter Quellenstand, erst anschließend manuell freigegebene stabile Signierung durch Repo-Eigentümer, kein automatisches Release/Merge/Geräteaktion.
