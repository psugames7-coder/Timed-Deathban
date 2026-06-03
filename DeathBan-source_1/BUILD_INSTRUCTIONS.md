# DeathBan Plugin — How to Build the .jar

You have SOURCE CODE. The server needs a compiled .jar. Pick ONE method below.

## Method 1: GitHub Actions (easiest, no software to install)
1. Create a free GitHub account.
2. Create a new repository (e.g. "DeathBan").
3. Upload ALL these files/folders to it (keep the folder structure):
   - pom.xml
   - src/ (the whole folder)
   - .github/ (the whole folder — this is the auto-builder)
4. GitHub will automatically build it. Go to the "Actions" tab,
   click the latest run, and download "DeathBan-plugin" from Artifacts.
5. Unzip it — inside is DeathBan-1.0.0.jar.
6. Drop that jar into your server's /plugins folder. Restart.

## Method 2: Build locally with Maven
Requires Java 21 + Maven installed.
1. Open a terminal in this folder (the one with pom.xml).
2. Run:  mvn clean package
3. The jar appears at:  target/DeathBan-1.0.0.jar
4. Drop it in /plugins. Restart.

## Method 3: Pay someone $5
Send the whole zip to any plugin dev (Fiverr / r/MinecraftHelp) and say
"compile this Maven project to a jar." They send back the jar.

## Notes
- pom.xml currently targets Paper API 1.21.4 (works on your 1.21.11 server).
- Edit it to 1.21.11-R0.1-SNAPSHOT only if you hit issues.
