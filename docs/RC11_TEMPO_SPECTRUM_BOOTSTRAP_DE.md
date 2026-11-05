# RC11 – Tempo-/Spektrum-A/B auf qualifiziertem RC10-Quellstand

Ausgangspunkt ba2804a83c988dd6753349dc44b75c7c1343d8a6: Android RC10 wurde laut Nutzerrückmeldung real getestet. MIC + automatische Analyseverstärkung ist bei dieser Gerätekonfiguration besser als VOICE_RECOGNITION/VOICE_COMMUNICATION. Die hohe Präsenz/Höhen-Anzeige bleibt leer; bei ca. 130 BPM scheint der kurze Color4-Test verzögert. Der bisherige Test begrenzt RGB ausdrücklich auf 1/Sekunde; das ist langsamer als ~462ms Beatabstand bei 130 BPM.

Getrennter CHAT-Draft für messwertbasierte Anzeigeverbesserungen und einen optionalen zweiten kurzen, stärker auf Beat-Pulse abgestimmten Music-Rückkanal-Test. RC10/RC9 bleiben unverändert; weder unlimitierte Farbshows noch LAN-RGB-Fallback, Firmware/Cloud-Aktionen oder Merge sind dadurch genehmigt. Vor weiteren Quelländerungen Registrierung im privaten Ownership-Controller per CAS+Readback.
