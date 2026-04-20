# Release Guide

Dieses Projekt nutzt automatisches Packaging und Release ueber GitHub Actions.

## Was Automatisch Passiert

- Jeder Push auf `main` baut das Plugin und laedt die JAR als Workflow-Artifact hoch.
- Jeder Pull Request auf `main` fuehrt ebenfalls den Build aus.
- Jeder Push eines Tags mit Prefix `v` (z. B. `v1.0.1`) erstellt ein GitHub Release und haengt die gebaute JAR an.

## Release-Regeln

- Die Plugin-Version steht in `build.gradle`:

```groovy
version = '1.0.1'
```

- Der Git-Tag muss exakt dazu passen, mit fuehrendem `v`:

```text
v1.0.1
```

- Die CI prueft das automatisch. Wenn Tag und `build.gradle`-Version nicht identisch sind, schlaegt der Workflow fehl.

## Release Erstellen

1. `version` in `build.gradle` aktualisieren.
2. Aenderung committen und nach `main` pushen.
3. Passenden Tag erstellen und pushen.

Beispiel:

```bash
git checkout main
git pull
git add build.gradle
git commit -m "chore: release 1.0.2"
git push

git tag v1.0.2
git push origin v1.0.2
```

## Wo Du Die Ergebnisse Findest

- Build-Artifact (bei jedem `main`-Push): GitHub Actions Run -> Artifact `jailcommandguard-jar`
- Offizielles Release (bei Tag-Push): GitHub Releases mit angehaengter JAR
