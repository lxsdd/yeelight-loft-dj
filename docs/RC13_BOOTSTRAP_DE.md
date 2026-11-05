# RC13 – Standby und Mehrlampen-Live-DJ

Quelle: RC12 c6eb038ddf7ef226db237c15939c16c247a3b273, hardwareseitig mehrminütiger Einzel-Color4-Lauf vom Nutzer bestätigt. Die Musiksteuerung endet derzeit beim Ausschalten des Bildschirms, da MainActivity.onStop() das Mikrofon stoppt. Auch nach mehreren Minuten blieb Yeelight Classic separat funktionsfähig.

## Auftrag

- Bewusst gestarteten DJ-Modus beim Displaysperren mit einem Android-Mikrofon-Vordergrunddienst samt Stopp-Benachrichtigung weiterbetreiben, niemals heimlich autostarten.
- Mehr als eine ausdrücklich ausgewählte, eindeutig erkannte Color4 über **individuelle** set_music-Rückkanäle taktgleich steuern. Single-Color4-Baseline erhalten; kein normaler quota-begrenzter set_rgb-Fallback.
- Pro Lampe vollständigen, individuellen Preflight, Quarantänecheck, persistentes Music-Journal, sicheren Start und Stop, unabhängig bestätigten music_on=0-Readback und fail-closed-Fehlerpfad verwenden. Keine Wiederholungen oder Firmware-/Quota-Umgehung.
- Ziel der späteren Classic-Integration: Funktionen in Yeelight Classic selbst; App-Verknüpfung allein zählt nicht als Integration. Original-App bis dahin unverändert lassen.

**Nur Bootstrap für isolierten CHAT-Draft.** Vor Produktquelländerungen private zentrale Ownership-Registry mit gültigem PR-HEAD, exklusivem Claim und unabhängiger Kontrolle aktualisieren. Keine Geräte-, Firmware- oder APK-Aktion durch diesen Commit.
