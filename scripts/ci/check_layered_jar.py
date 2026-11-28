"""Verify the Spring Boot 3.5 extracted runtime against its original Maven JAR."""
import hashlib
from pathlib import Path
import sys
import zipfile


def digest(data):
    return hashlib.sha256(data).hexdigest()


def manifest(archive):
    lines = archive.read("META-INF/MANIFEST.MF").decode("utf-8").splitlines()
    attrs = {}
    key = None
    for line in lines:
        if not line:
            break
        if line.startswith(" "):
            attrs[key] += line[1:]
        else:
            key, value = line.split(": ", 1)
            attrs[key] = value
    return attrs


original, runtime = Path(sys.argv[1]), Path(sys.argv[2])
with zipfile.ZipFile(original) as source, zipfile.ZipFile(runtime / "app.jar") as app:
    original_manifest = manifest(source)
    runtime_manifest = manifest(app)
    assert runtime_manifest["Main-Class"] == original_manifest["Start-Class"]
    classpath = source.read("BOOT-INF/classpath.idx").decode("utf-8").splitlines()
    ordered_libs = [line.strip()[3:-1] for line in classpath if line.strip()]
    expected_paths = ["lib/" + name.removeprefix("BOOT-INF/lib/") for name in ordered_libs]
    assert runtime_manifest["Class-Path"].split() == expected_paths
    assert len(expected_paths) == len(set(expected_paths))
    assert {p.relative_to(runtime).as_posix() for p in (runtime / "lib").glob("*.jar")} == set(expected_paths)
    for name, path in zip(ordered_libs, expected_paths):
        assert digest(source.read(name)) == digest((runtime / path).read_bytes()), path
    classes = [name for name in source.namelist() if name.startswith("BOOT-INF/classes/") and not name.endswith("/")]
    assert classes
    for name in classes:
        relative = name.removeprefix("BOOT-INF/classes/")
        assert digest(source.read(name)) == digest(app.read(relative)), relative
    print(f"Verified {len(classes)} application files and {len(expected_paths)} dependency JARs, including classpath order.")
    print(f"Original JAR: {original.stat().st_size} bytes; application layer JAR: {(runtime / 'app.jar').stat().st_size} bytes.")
