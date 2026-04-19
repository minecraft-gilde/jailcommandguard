# JailCommandGuard

Dieses Plugin blockiert fuer gejailte Spieler alle Commands ausser einer konfigurierbaren Whitelist.
Es ist so geschrieben, dass es ohne Scheduler auskommt und `folia-supported: true` gesetzt ist.

## Voraussetzungen

- JDK 21
- VS Code mit `Extension Pack for Java`
- Gradle Wrapper (`gradlew` / `gradlew.bat`)
- Paper/Folia-kompatibler Server
- EssentialsX oder ein kompatibler Fork mit denselben API-Paketen

## Projekt in VS Code oeffnen

1. Ordner `jailcommandguard` in VS Code oeffnen.
2. Warten, bis die Java-Erweiterungen das Gradle-Projekt importiert haben.
3. In `build.gradle` die Variable `paperApiVersion` auf deine Server-Version setzen.
4. Falls dein Essentials-Fork andere Maven-Koordinaten hat, in `build.gradle` die Dependency anpassen.

## Bauen

Im Terminal im Projektordner ausfuehren:

```bash
./gradlew build
```

Unter Windows:

```bat
gradlew.bat build
```

Die fertige JAR liegt danach hier:

```text
build/libs/jailcommandguard-1.0.0.jar
```

## Installation auf dem Server

1. JAR in den Ordner `plugins/` hochladen.
2. Server neu starten.
3. Die Datei `plugins/JailCommandGuard/config.yml` anpassen.
4. Danach im Spiel oder in der Konsole ausfuehren:

```text
/jcg reload
```

## Permissions

- `jailcommandguard.bypass` -> Spieler ignoriert die Jail-Command-Sperre
- `jailcommandguard.reload` -> darf `/jcg reload` ausfuehren

## Beispiel LuckPerms

Admins sollen alles duerfen:

```text
/lp group admin permission set jailcommandguard.bypass true
/lp group admin permission set jailcommandguard.reload true
```

## Hinweise

- Wenn du einen Command erlauben willst, trage auch seine Aliase in `allowed-commands` ein.
- Das Plugin blockiert die Ausfuehrung zuverlaessig. Das Verstecken in der Befehlsliste haengt davon ab, wann der Command-Tree vom Server neu gesendet wird.

