#!/usr/bin/env python3
"""Build against the exact locally installed, unobfuscated Minecraft 26.3 APIs."""
import json, os, pathlib, subprocess, zipfile
BASE = pathlib.Path(__file__).resolve().parent
MC = pathlib.Path(os.environ.get("MINECRAFT_DIR", "/home/hunter/.var/app/com.mojang.Minecraft/.minecraft"))
BUILD = BASE / "build"
CLASSES = BUILD / "classes"
CLASSES.mkdir(parents=True, exist_ok=True)
API = BUILD / "api"
API.mkdir(exist_ok=True)
with zipfile.ZipFile(MC / "mods/fabric-api-0.165.0+26.3.jar") as z:
    for n in z.namelist():
        if n.endswith(".jar"):
            (API / pathlib.Path(n).name).write_bytes(z.read(n))
cp = os.pathsep.join(map(str, [MC / "versions/26.3/26.3.jar", *MC.joinpath("libraries").rglob("*.jar"), *API.glob("*.jar")]))
sources = list(BASE.joinpath("src/main/java").rglob("*.java"))
subprocess.run(["javac", "--release", "25", "-proc:none", "-classpath", cp, "-d", str(CLASSES), *map(str, sources)], check=True)
testclasses = BUILD / "tests"
testclasses.mkdir(exist_ok=True)
subprocess.run(["javac", "--release", "25", "-classpath", str(CLASSES), "-d", str(testclasses), *map(str, BASE.joinpath("src/test/java").rglob("*.java"))], check=True)
subprocess.run(["java", "-ea", "-cp", os.pathsep.join(map(str, [CLASSES, testclasses])), "dev.fieldnotes.guard.GuardTest"], check=True)
jar = BUILD / "fieldnotes-afk-guard-0.1.0+26.3.jar"
with zipfile.ZipFile(jar, "w", zipfile.ZIP_DEFLATED) as z:
    for root in [CLASSES, BASE / "src/main/resources"]:
        for f in root.rglob("*"):
            if f.is_file(): z.write(f, f.relative_to(root))
print(jar)
