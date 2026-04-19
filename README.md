# JailCommandGuard

Dieses Plugin blockiert für gejailte Spieler alle Commands außer einer konfigurierbaren Whitelist.
Es ist so geschrieben, dass es ohne Scheduler auskommt und `folia-supported: true` gesetzt ist.

## Voraussetzungen

- JDK 21
- VS Code mit `Extension Pack for Java`
- Maven
- Paper/Folia-kompatibler Server
- EssentialsX oder ein kompatibler Fork mit denselben API-Paketen

## Projekt in VS Code öffnen

1. Ordner `jailcommandguard` in VS Code öffnen.
2. Warten, bis die Java-Erweiterungen das Maven-Projekt importiert haben.
3. In `pom.xml` die Property `paper.api.version` auf deine Server-Version setzen.
4. Falls dein Essentials-Fork andere Maven-Koordinaten hat, in `pom.xml` die Dependency anpassen.

## Bauen

Im Terminal im Projektordner ausführen:

```bash
mvn clean package
```

Die fertige JAR liegt danach hier:

```text
target/jailcommandguard-1.0.0.jar
```

## Installation auf dem Server

1. JAR in den Ordner `plugins/` hochladen.
2. Server neu starten.
3. Die Datei `plugins/JailCommandGuard/config.yml` anpassen.
4. Danach im Spiel oder in der Konsole ausführen:

```text
/jcg reload
```

## Permissions

- `jailcommandguard.bypass` → Spieler ignoriert die Jail-Command-Sperre
- `jailcommandguard.reload` → darf `/jcg reload` ausführen

## Beispiel LuckPerms

Admins sollen alles dürfen:

```text
/lp group admin permission set jailcommandguard.bypass true
/lp group admin permission set jailcommandguard.reload true
```

## Hinweise

- Wenn du einen Command erlauben willst, trage auch seine Aliase in `allowed-commands` ein.
- Das Plugin blockiert die Ausführung zuverlässig. Das Verstecken in der Befehlsliste hängt davon ab, wann der Command-Tree vom Server neu gesendet wird.
