"""Check the real image and benign search fixture, with no GPU, AWS or LLM."""
import ctypes
import json
import os
from pathlib import Path
import shutil
import sqlite3
import ssl
import stat
import subprocess
import tempfile
import zlib


MIN_ZLIB = "1:1.3.dfsg+really1.3.1-1+deb13u1"
MIN_ZSTD = "1.5.7+dfsg-1+deb13u1"


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def check_zstd():
    # Backported fixes do not change ZSTD_versionString; inspect the Debian revision.
    version = subprocess.check_output(
        ["dpkg-query", "-W", "-f=${Version}", "libzstd1"], text=True).strip()
    require(subprocess.run(["dpkg", "--compare-versions", version, "ge", MIN_ZSTD]).returncode == 0,
            "Unpatched zstd package")
    lib = ctypes.CDLL("libzstd.so.1")
    lib.ZSTD_versionString.restype = ctypes.c_char_p
    lib.ZSTD_compressBound.argtypes = [ctypes.c_size_t]
    lib.ZSTD_compressBound.restype = ctypes.c_size_t
    lib.ZSTD_compress.argtypes = [ctypes.c_void_p, ctypes.c_size_t, ctypes.c_void_p,
                                 ctypes.c_size_t, ctypes.c_int]
    lib.ZSTD_compress.restype = ctypes.c_size_t
    lib.ZSTD_decompress.argtypes = [ctypes.c_void_p, ctypes.c_size_t, ctypes.c_void_p, ctypes.c_size_t]
    lib.ZSTD_decompress.restype = ctypes.c_size_t
    lib.ZSTD_isError.argtypes = [ctypes.c_size_t]
    lib.ZSTD_isError.restype = ctypes.c_uint
    maps = Path("/proc/self/maps").read_text()
    require("/usr/lib/x86_64-linux-gnu/libzstd.so." in maps, "Non-system zstd loaded")
    payload = ("HobbyTree 요리 검색\n" * 100).encode()
    compressed = ctypes.create_string_buffer(lib.ZSTD_compressBound(len(payload)))
    size = lib.ZSTD_compress(compressed, len(compressed), payload, len(payload), 3)
    require(not lib.ZSTD_isError(size) and size <= len(compressed), "zstd compression failed")
    restored = ctypes.create_string_buffer(len(payload))
    restored_size = lib.ZSTD_decompress(restored, len(restored), compressed, size)
    require(not lib.ZSTD_isError(restored_size) and restored_size == len(payload)
            and restored.raw == payload, "zstd roundtrip failed")
    return {"zstd_package": version, "zstd_runtime": lib.ZSTD_versionString().decode(),
            "zstd_roundtrip": "passed"}


def main():
    zstd_result = check_zstd()
    for package, minimum in (("libc6", "2.41-12+deb13u5"), ("libc-bin", "2.41-12+deb13u5"),
                             ("libssl3t64", "3.5.9-1~deb13u1"), ("openssl", "3.5.9-1~deb13u1"),
                             ("openssl-provider-legacy", "3.5.9-1~deb13u1")):
        installed = subprocess.check_output(["dpkg-query", "-W", "-f=${Version}", package], text=True)
        require(subprocess.run(["dpkg", "--compare-versions", installed, "ge", minimum]).returncode == 0,
                f"{package} {installed} below reviewed version {minimum}")
    # Exercise the normal Python TLS client setup offline after updating the OS libraries.
    context = ssl.create_default_context()
    require(context.check_hostname and context.verify_mode == ssl.CERT_REQUIRED and context.get_ca_certs(),
            "TLS trust store unavailable")
    version = subprocess.check_output(
        ["dpkg-query", "-W", "-f=${Version}", "zlib1g"], text=True).strip()
    require(subprocess.run(["dpkg", "--compare-versions", version, "ge", MIN_ZLIB]).returncode == 0,
            "Unpatched zlib package")
    # Load the same system SONAME that Python uses; also exercise its normal API.
    libz = ctypes.CDLL("libz.so.1")
    libz.zlibVersion.restype = ctypes.c_char_p
    require(libz.zlibVersion().decode() == zlib.ZLIB_RUNTIME_VERSION, "zlib runtime mismatch")
    payload = ("HobbyTree 요리 검색\n" * 100).encode()
    require(zlib.decompress(zlib.compress(payload)) == payload, "zlib roundtrip")
    maps = Path("/proc/self/maps").read_text()
    require("/usr/lib/x86_64-linux-gnu/libz.so." in maps, "Non-system zlib loaded")
    require(os.geteuid() == 10001, "Run as the application user")
    status = dict(line.split(":", 1) for line in Path("/proc/self/status").read_text().splitlines())
    require(int(status["CapEff"], 16) == 0 and int(status["CapBnd"], 16) == 0,
            "Capabilities must be dropped")
    require(status["NoNewPrivs"].strip() == "1", "no-new-privileges required")
    privileged = [str(path) for path in Path("/usr").rglob("*")
                  if not path.is_symlink() and path.is_file()
                  and path.stat().st_mode & (stat.S_ISUID | stat.S_ISGID)]
    require(not privileged, "Unexpected setuid/setgid executable")
    require(shutil.which("infocmp") is None, "Vulnerable infocmp tool present")
    require(not Path("/usr/lib/systemd/systemd-homed").exists(), "homed present")
    require(not any(Path("/usr").glob("**/Archive/Tar.pm")), "Perl Archive::Tar present")
    require(not Path("/var/run/docker.sock").exists(), "Docker socket mounted")

    # Actual pinned upstream schema, isolated scratch DB; never open model/state DBs.
    from app.retrieval import initialize_database, connect
    with tempfile.TemporaryDirectory(prefix="gpu-security-") as tmp:
        path = Path(tmp) / "fixture.sqlite"
        initialize_database(path)
        with connect(path) as db:
            schema = db.execute("SELECT sql FROM sqlite_master WHERE name='search_fts'").fetchone()[0]
            require("detail=none" not in schema.lower().replace(" ", ""), "FTS detail changed")
            require(db.execute("SELECT v FROM search_fts_config WHERE k='secure-delete'").fetchone() is None,
                    "FTS secure-delete enabled")
            db.execute("INSERT INTO search_fts(rowid,title,body) VALUES (1,?,?)", ("요리", "달걀 요리"))
            require(db.execute("SELECT rowid FROM search_fts WHERE search_fts MATCH ?", ('"요리"*',)).fetchall(),
                    "FTS prefix regression")
            db.execute("DELETE FROM search_fts WHERE rowid=?", (1,))
            require(db.execute("PRAGMA integrity_check").fetchone()[0] == "ok", "SQLite integrity")
    print(json.dumps({**zstd_result, "zlib_package": version, "zlib_runtime": zlib.ZLIB_RUNTIME_VERSION,
        "sqlite_runtime": sqlite3.sqlite_version, "runtime_guards": "passed",
        "fts_fixture": "passed", "paid_calls": 0, "hardware_test_passed": False,
        "residual_cves_review": "See SECURITY_REVIEW.md; this is not a zero-CVE claim"}))


if __name__ == "__main__":
    main()
