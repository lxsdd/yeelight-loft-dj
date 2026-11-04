# Chroma v1/v2: Opaque-Token-Paritaet und Offline-Cohort (04.11.2026)

Forschungszweig: isolierter CHAT-Nachfolger, Draft-PR #53. Nur Quellcode und synthetische JVM-Daten; keine Netzwerkaktionen, privaten Firmwarebytes, echten IP-Adressen oder Token.

## Fehler und Korrektur

Die bisherige Chroma-Sitzung normalisierte ein von der simulierten OEM-Antwort erhaltenes 32-stelliges Hex-Token zwangsweise auf Kleinbuchstaben. Der unabhaengige Chroma-Fake-Geraetepruefer vergleicht Token dagegen als exakten Text. Bei gemischt-gross/klein geschriebenen gueltigen Hex-Token wurde dadurch ein urspruenglich akzeptierter Handshake mit einem anderen Token im Keepalive- oder Farbframe fortgesetzt. Ohne eindeutigen Herstellerbeleg fuer eine case-insensitive Token-Semantik darf eine solche Umwandlung nicht erfolgen.

Jetzt bleibt der empfangene Token wortgetreu erhalten. Ein Keepalive-Reply mit nur geaenderter Gross-/Kleinschreibung wird abgewiesen, ohne den ausstehenden gueltigen Reply zu verbrauchen. Dies ist eine konservative Host-Regel und kein Beweis fuer eine bestimmte Token-Kodierung der Color4-Firmware.

## Regressionen und Grenzen

Die neuen Kotlin-Regressionen pruefen beide getrennten OEM-Methodenfamilien v1/v2, gemischt geschriebene Token in Keepalive und `set_scene`, Case-Only-Replay/Nachrichtenveraenderung, nicht beworbenes RGB, korrelierte Ablehnung mit terminaler Token-Loeschung sowie 14 isolierte synthetische Lampen mit jeweils 1000 UI-Farbwerten und einem auszugebenden Frame.

Alle Pakete werden ausschliesslich in-memory erzeugt und geprueft. Weder aus einer positiven Fake-Antwort noch aus der OEM-Methodenparitaet folgt, dass Color4 2.1.7_0039 Chroma-UDP live unterstuetzt. Die reale LAN-Quota-Refill-/Dispatch-Ursache bleibt nicht bewiesen. Kein Live-Transport, kein Musikmodus-Fallback, keine Testlampe, kein Firmwareflash, keine Quota-Umgehungsfreigabe.
