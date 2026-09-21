AutoBuilder (Fabric, Minecraft 1.21.1, client-side)

BUILD (needs Java 21):
  Windows:   gradlew.bat build
  Mac/Linux: ./gradlew build
Jar: build/libs/autobuilder-1.0.0.jar  -> put in .minecraft/mods/ with Fabric API 0.102.0+1.21.1

No Java? Push this folder to a GitHub repo; the Actions tab builds the jar (artifact "autobuilder-jar").

USE: schematics (.schem) go in .minecraft/schematics/. Look at the start block, press Right Shift.
Left-click "Auto Build" = start/stop. Right-click = set shop command + schematic name.
