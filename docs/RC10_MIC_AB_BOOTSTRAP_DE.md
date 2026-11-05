# RC10 Mikrofon-AB-Test (isolierter CHAT-Entwicklungszweig)

Ausgangspunkt ist der Android-hardwarevalidierte RC9-Quellstand ea8fe70b41b9227796b9ad0b1867030066258c06; RC9 selbst bleibt unverändert. Der Chat hat die einmalige sichtbare Color4-Reaktion, music_on=0 und nachfolgenden Yeelight-Classic-Betrieb auf Android bestätigt. Das beweist noch keine Langzeit-Stabilität.

Neuer Scope: Android-MIC-/VOICE_RECOGNITION-/VOICE_COMMUNICATION-Profile und UNPROCESSED nur bei bestätigter Betriebssystemfähigkeit, Rohpegel/Clipping vor Software-Gain, nachvollziehbarer begrenzter Auto-Gain zur Audioanalyse. Kein Eingriff in den Color4-Rückkanal, Quota-Gate oder Testfreigabe und kein automatischer Hardwaretest. Vor Quelländerung private Ownership CAS+Readback erforderlich. Quellcode-PR-CI vor signierbarer RC10, keinerlei Merge oder Release.
