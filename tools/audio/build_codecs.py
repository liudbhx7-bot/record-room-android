#!/usr/bin/env python3
"""Build the local lossless reader from verified FFmpeg sources and Android NDK r28."""
import argparse
import hashlib
import os
from pathlib import Path
import platform
import subprocess
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
RELEASE = "ffmpeg-8.1.3"
SHA256 = "7138d28c96d9d3e3af4ee3d8cad72741f8ffb40da90c1112235dea3ecd3178a3"
ABIS = {"arm64-v8a": ("aarch64", "aarch64-linux-android"), "armeabi-v7a": ("arm", "armv7a-linux-androideabi")}

def build(ndk, cache, archive=None):
    host = {"Darwin": "darwin-x86_64", "Linux": "linux-x86_64"}[platform.system()]
    tools = ndk / "toolchains/llvm/prebuilt" / host / "bin"
    cache.mkdir(parents=True, exist_ok=True)
    source = cache / RELEASE
    archive = archive or cache / (RELEASE + ".tar.xz")
    if not archive.exists():
        partial = archive.with_suffix(".download")
        urllib.request.urlretrieve("https://ffmpeg.org/releases/" + archive.name, partial)
        if hashlib.sha256(partial.read_bytes()).hexdigest() != SHA256:
            raise ValueError("FFmpeg download checksum mismatch")
        partial.replace(archive)
    if hashlib.sha256(archive.read_bytes()).hexdigest() != SHA256:
        raise ValueError("FFmpeg source checksum mismatch")
    if not (source / "configure").is_file():
        with tarfile.open(archive) as tar:
            tar.extractall(cache)
    out = cache / "jni"
    for abi, (arch, triple) in ABIS.items():
        cc = tools / (triple + "26-clang")
        work = cache / abi
        work.mkdir(exist_ok=True)
        options = [
            "--enable-cross-compile", "--target-os=android", "--arch=" + arch, "--cc=" + str(cc),
            "--ar=" + str(tools / "llvm-ar"), "--ranlib=" + str(tools / "llvm-ranlib"),
            "--nm=" + str(tools / "llvm-nm"), "--strip=" + str(tools / "llvm-strip"),
            "--disable-everything", "--disable-programs", "--disable-doc", "--disable-debug",
            "--disable-autodetect", "--disable-network", "--disable-avdevice", "--disable-avfilter",
            "--disable-swresample", "--disable-swscale", "--enable-pic", "--enable-small",
            "--enable-demuxer=ape,wv,aiff,mov", "--enable-decoder=ape,alac,wavpack,pcm_s8,pcm_u8,pcm_s16be,pcm_s24be,pcm_s32be,pcm_s16le,pcm_s24le,pcm_s32le,pcm_f32be,pcm_f64be",
        ]
        stamp = work / "recipe.txt"
        recipe = "\n".join(options + [SHA256])
        if not stamp.exists() or stamp.read_text() != recipe:
            subprocess.run([str(source / "configure"), *options], cwd=work, check=True)
            stamp.write_text(recipe)
        libs = ["libavformat/libavformat.a","libavcodec/libavcodec.a","libavutil/libavutil.a"]
        subprocess.run(["make", "-j" + str(min(os.cpu_count() or 4, 12)), *libs], cwd=work, check=True)
        target = out / abi / "librecord_formats.so"
        target.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run([cc, "-shared", "-fPIC", "-O2", "-fvisibility=hidden", "-D_FILE_OFFSET_BITS=64",
                        "-I" + str(source), "-I" + str(work), ROOT / "app/src/main/cpp/local_formats.c", *libs,
                        "-lm", "-lz", "-Wl,--exclude-libs,ALL", "-Wl,-z,max-page-size=16384",
                        "-Wl,-z,defs", "-Wl,-soname,librecord_formats.so", "-o", target], cwd=work, check=True)
        subprocess.run([tools / "llvm-strip", "--strip-unneeded", target], check=True)
    return out

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndk", type=Path, required=True)
    parser.add_argument("--cache", type=Path, default=ROOT / "work/audio-codecs")
    parser.add_argument("--archive", type=Path)
    args = parser.parse_args()
    print(build(args.ndk.resolve(), args.cache.resolve(), args.archive))
