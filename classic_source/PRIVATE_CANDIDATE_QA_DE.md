# Classic: geschützte Kandidaten-QA ohne vorzeitigen Merge

Stand: 2026-11-12. **Quellcode- und Guard-Entwicklung; keine neue OEM-Laufzeitabnahme.**

## Zweck

Die bislang geschützte Classic-Diagnose ließ nur \`main\` als zu testende
Quelle zu. Jede neue Diagnosekorrektur musste daher vor einem realen Test
zunächst nach \`main\` gemergt werden. Ein Absturz in der privaten
Android-17-Emulation war dadurch ein vermeidbarer Merge-Fehlschlag.

\`.github/workflows/classic-private-emulator-probe.yml\` unterstützt nun
neben dem bisherigen \`main\`-Fall einen **vom Repo-Owner bewusst gepinnten
offenen Classic-PR**. Die eigentliche Workflow-Definition muss weiterhin
vom geschützten \`main\` kommen und bleibt dort owner-only.

### Auswahl des privaten Tests

GitHub Actions → **Classic PRIVATE offline modified-APK emulator probe
(no distribution)** → **Run workflow** auf Branch \`main\`.

- \`candidate_pr=0\`, \`candidate_sha\` leer: ursprüngliche
  \`main\`-Diagnose, unverändert owner-only.
- \`candidate_pr=<offene Classic-PR-Nummer>\`, \`candidate_sha=<exakter
  40-stelliger PR-HEAD-Commit>\`: die ausgewählte Quelle wird **nicht
  gemergt**. Kein leeres oder automatisch nachgeführtes HEAD zulässig.
- Der Runner verwirft veraltete SHA-Pins, verschobenes \`main\`,
  fremde/Fork-PRs, nicht-Classic-Zweige, Abweichungen in der Commit-Autorenschaft
  und Änderungen außerhalb \`classic_source/\`.
- Source-verify, Android verify-and-build und PUBLIC Android17 no-OEM boot
  müssen **jeweils auf genau demselben PR-SHA** erfolgreich gewesen sein;
  Job-Status \`skipped\` oder neuere rote Läufe sind keine Freigabe.
- Kein öffentliches CI-Artefakt ist zulässig. Selbst die Quellprüfung
  und das Auschecken der Testquelle erfolgen vor dem Zugriff auf den Vault.

Der eigentliche GitHub-Workflow wird nicht aus dem PR übernommen:
Zunächst wird **\`main@GITHUB_SHA\`** als vertrauenswürdige Prüfbasis
ausgecheckt und \`github_hosted/policy.py\` ausgeführt. Erst nach
\`github_hosted/private_candidate_gate.py\` wird der exakte,
unveränderliche Kandidaten-Commit ausgecheckt und direkt gegengeprüft.
Die festen Workflow-Schritte, der private Vault-Release-Pin, ephemeral
Testsignierung und Android-17-Emulatortest bleiben unverändert.
Erfolgreiche Source-Gates sind **keine** OEM- oder UI-Abnahme.

## Sicherheits- und Automatisierungsgrenze

Diese Änderung **aktiviert keinen automatischen privilegierten PR-Trigger**.
\`workflow_dispatch\` auf geschütztem \`main\`, GitHub-Actor \`lxsdd\`,
die bisherige \`classic-private-build\`-Umgebung, striktes Eigentümer-
und Klassenprüfungskonzept bleiben wirksam. Keine Fork-Ausführung,
kein \`pull_request_target\`, keine generelle \`workflow_run\`-Privilegierung.

Ein PR kann Laufzeit-Skripte ändern, die später neben einer privaten
Original-APK laufen. Das ist die **wesentliche Vertrauensgrenze**:
öffentlich grüne CI allein beweist weder die Sicherheit eines PR noch
die Vertraulichkeit von darin ausgeführtem Code. Daher ersetzt ein
PR-Trigger **nicht** die Eigentümerautorisierung oder eine tatsächliche
isolierende Sandbox. Automatische private Tests für beliebige PRs
wären eine unzulässige Erweiterung der Vault-Zugriffsrechte.

Die Runner benutzen nur kurzlebige TEST-Signaturen, veröffentlichen keine
OEM-Dateien, APKs, Benutzer-/Gerätedaten oder Actions-Artefakte und
räumen private temporäre Verzeichnisse auf. Kein zusätzlicher SDK-
Lizenzabschluss, kostenpflichtiger Runner, physisches Gerät oder
Lampen-/Firmware-/Netzwerkschreibzugriff.

**Nächste Qualitätsschranke:** Nach erfolgreicher öffentlicher PR-CI kann
der Eigentümer *einmalig* den privaten Kandidaten-Test auf dessen exaktem
SHA starten – ohne vorausgehenden Merge. Scheitert er, wird der
gleiche isolierte PR weiter korrigiert und neu auf dem neuen SHA
qualifiziert. Ein Merge ist erst nach echter, geschützter Laufzeit-/UI-
Abnahme gesondert zulässig.

Ein völlig unbeaufsichtigter Korrektur-/Emulator-/Merge-Zyklus verlangt
zusätzlich einen tatsächlich verfügbaren Cloud-Executor und eine
gesondert geprüfte, eng begrenzte, secretsichere Automatisierungsfreigabe.
Diese Quelländerung beansprucht das ausdrücklich **nicht**.
