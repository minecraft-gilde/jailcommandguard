# JailCommandGuard

JailCommandGuard blockiert für gejailte Spieler alle Befehle außer einer konfigurierbaren Whitelist.  
Zusätzlich kann das Plugin regelmäßig die verbleibende Jail-Zeit im Chat anzeigen.

## Schnellstart (Server-Admins)

1. Plugin-JAR in den Ordner `plugins/` kopieren.
2. Server starten oder neu starten.
3. `plugins/JailCommandGuard/config.yml` anpassen.
4. Konfiguration mit `/jcg reload` neu laden.

## Features

- Blockiert Befehle für gejailte Spieler auf Basis einer Whitelist.
- Optionales Verstecken nicht erlaubter Befehle in Command-Liste/Tab-Completion.
- Periodische Jail-Zeit-Erinnerung mit Platzhaltern.
- Folia-Unterstützung (`folia-supported: true`).

## Kompatibilität

| Komponente | Status |
| --- | --- |
| Paper API | `api-version: 1.20` |
| Folia | Unterstützt |
| Essentials | Benötigt (EssentialsX oder kompatibler Fork mit `net.ess3.api`) |
| Java | JDK 21 |

## Befehle

| Befehl | Alias | Beschreibung | Permission |
| --- | --- | --- | --- |
| `/jailcommandguard reload` | `/jcg reload` | Lädt die Plugin-Konfiguration neu | `jailcommandguard.reload` |

## Permissions

| Permission | Standard | Wirkung |
| --- | --- | --- |
| `jailcommandguard.bypass` | `op` | Spieler ignoriert die Jail-Command-Sperre |
| `jailcommandguard.reload` | `op` | Erlaubt `/jailcommandguard reload` |

## Konfiguration (`config.yml`)

| Pfad | Standardwert | Beschreibung |
| --- | --- | --- |
| `allowed-commands` | `msg, tell, w, r, reply, help` | Erlaubte Befehle für gejailte Spieler |
| `blocked-message` | `&cDu kannst diesen Befehl im Gefängnis nicht benutzen.` | Nachricht bei blockiertem Befehl |
| `hide-disallowed-commands` | `true` | Entfernt nicht erlaubte Befehle aus Command-Liste/Tab-Completion |
| `jail-time-reminder.enabled` | `true` | Aktiviert periodische Restzeit-Nachrichten |
| `jail-time-reminder.interval-minutes` | `1` | Intervall der Restzeit-Nachricht in Minuten (`<1` wird intern zu `1`) |
| `jail-time-reminder.message` | `&eDu musst noch &6%formatted_time% &eim Gefängnis bleiben.` | Chat-Nachricht mit Platzhaltern |
| `debug-respawn.enabled` | `false` | Zusätzliche Logs für Death/Respawn/Jail-Teleports |

Verfügbare Platzhalter für `jail-time-reminder.message`:

- `%minutes%` → verbleibende Minuten (aufgerundet)
- `%formatted_time%` → formatierte Zeit (z. B. `5 Minuten`)

## Beispiel LuckPerms

```text
/lp group admin permission set jailcommandguard.bypass true
/lp group admin permission set jailcommandguard.reload true
```

## Troubleshooting

- Plugin deaktiviert sich beim Start mit Essentials-Fehler:  
  Prüfe, ob EssentialsX (oder ein API-kompatibler Fork) installiert und korrekt geladen ist.
- Erlaubter Befehl wird trotzdem blockiert:  
  Trage auch Aliase ein, z. B. `msg`, `tell`, `w`.
- Befehle werden trotz `hide-disallowed-commands: true` noch angezeigt:  
  Führe `/jcg reload` aus und lasse den Spieler ggf. neu einloggen, damit der Command-Tree neu gesendet wird.
- Keine Restzeit-Nachricht sichtbar:  
  Prüfe `jail-time-reminder.enabled`, Intervall, Nachrichtentext und ob der Spieler `jailcommandguard.bypass` hat.

## Entwicklung

### Dokumentation

- Release-Guide: [docs/release.md](docs/release.md)

### Voraussetzungen

- JDK 21
- VS Code mit `Extension Pack for Java`
- Gradle Wrapper (`gradlew` / `gradlew.bat`)

### Projekt in VS Code öffnen

1. Ordner `jailcommandguard` in VS Code öffnen.
2. Warten, bis die Java-Erweiterungen das Gradle-Projekt importiert haben.
3. In `build.gradle` die Variable `paperApiVersion` auf deine Server-Version setzen.
4. Falls dein Essentials-Fork andere Maven-Koordinaten nutzt, in `build.gradle` die Dependency anpassen.

### Build

```bash
./gradlew build
```

Unter Windows:

```bat
gradlew.bat build
```

Die fertige JAR liegt danach unter:

```text
build/libs/jailcommandguard-<version>.jar
```
