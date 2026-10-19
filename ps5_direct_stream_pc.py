#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Direct Stream for PlayStation 5 — PC & Multi-Console Edition (v3.0.0)
Repository: https://github.com/yoyo-ui/direct-stream-ps5

New in v3.0.0:
1. Smart Game & File Format Detector + Automatic PS5 Installation Path Router:
   - Automatically identifies PS5/PS4 Packages (.pkg), ShadowMount Disc Images
     (.ffpfsc, .exfat, .ufs, .iso), Unpacked Game Folders (PPSA/CUSA, sce_sys/param.json,
     eboot.bin), Multi-Part Game Archives (.rar, .zip, .7z, .part01.rar, .001),
     Payloads (.elf, .bin), Plugins (.prx, .sprx), Cheats (.json, .shn, .mc4),
     and General Files/Media.
   - Inspects HTTP headers, magic bytes (\x7fCNT / ZIP / RAR / 7z), and ZIP central
     directories to determine exact installation paths (/data/pkg, /data/ShadowMount,
     /data/homebrew, /data/payloads, /data/etaHEN/plugins, etc.).
2. Multi-Console Connection Manager & Simultaneous Broadcast Engine:
   - Connect unlimited PS5 consoles at the same time (with fast parallel LAN scanner).
   - Stream/download once from HTTP/HTTPS or local disk into a shared RAM Ring Buffer
     and broadcast simultaneously in parallel to ALL selected PS5 consoles.
   - Independent per-console progress tracking, speed telemetry, .ps5part safe partials,
     and fault isolation (if one console disconnects, other consoles continue unaffected).
3. General-Purpose FTP File & Folder Transfer + Remote Console File Manager:
   - Transfer ANY file or recursive directory tree over FTP to any console path
     (not restricted to games).
   - Browse console directories (/data, /mnt/ext0, /mnt/usb0, /user, etc.), upload files
     or folders, download files to PC, create folders, rename, and delete items.
4. Cross-Platform PC Edition (Web Dashboard + Native Tkinter Desktop GUI + CLI):
   - Pure Python 3.9+ standard library (zero mandatory pip dependencies).
   - Includes both a modern Bilingual (Arabic RTL / English LTR) Web Studio Dashboard
     and a native Desktop GUI window (--gui).
5. GitHub Releases Auto-Updater:
   - Automatically checks https://api.github.com/repos/yoyo-ui/direct-stream-ps5/releases/latest
   - Downloads and applies updates atomically with automatic backup and rollback safety.
"""

from __future__ import annotations

import argparse
import collections
import concurrent.futures
import contextlib
import dataclasses
import datetime
import ftplib
import hashlib
import html
import http.client
import http.server
import io
import ipaddress
import json
import os
import pathlib
import platform
import posixpath
import queue
import re
import shutil
import socket
import ssl
import struct
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import webbrowser
import zipfile
from typing import Any, Callable, Dict, List, Optional, Tuple

APP_NAME = "Direct Stream for PlayStation 5 — PC & Multi-Console Edition"
APP_VERSION = "3.0.0"
IS_FROZEN_EXE = bool(getattr(sys, "frozen", False))
CURRENT_EXECUTABLE = pathlib.Path(sys.executable).resolve() if IS_FROZEN_EXE else pathlib.Path(__file__).resolve()
DEFAULT_GITHUB_REPO = "yoyo-ui/direct-stream-ps5"
FALLBACK_GITHUB_REPO = "ChillQuant/direct-stream-ps5"
CONFIG_DIR = pathlib.Path.home() / ".direct_stream_ps5"
CONSOLES_FILE = CONFIG_DIR / "consoles_v3.json"
JOBS_FILE = CONFIG_DIR / "jobs_v3.json"
SETTINGS_FILE = CONFIG_DIR / "settings_v3.json"

# ============================================================================
# 0. BRIDGE TO EXISTING REPOSITORY MODULES (resolver.py / transfer_core.py / zip_streamer.py)
# ============================================================================
LEGACY_RESOLVER = None
LEGACY_TRANSFER_CORE = None
LEGACY_ZIP_STREAMER = None

with contextlib.suppress(Exception):
    import resolver as _legacy_resolver  # type: ignore
    LEGACY_RESOLVER = _legacy_resolver

with contextlib.suppress(Exception):
    import transfer_core as _legacy_tc  # type: ignore
    LEGACY_TRANSFER_CORE = _legacy_tc

with contextlib.suppress(Exception):
    import zip_streamer as _legacy_zs  # type: ignore
    LEGACY_ZIP_STREAMER = _legacy_zs


def resolve_url_with_legacy_engine(url: str) -> str:
    """Uses the existing repo's resolver.py (MediaFire, PixelDrain, GDrive, Archive.org, TorBox, AkiraBox, etc.) if available."""
    if LEGACY_RESOLVER is not None:
        for fn_name in ("resolve_direct_url", "resolve_url", "resolve_link", "resolve"):
            fn = getattr(LEGACY_RESOLVER, fn_name, None)
            if callable(fn):
                with contextlib.suppress(Exception):
                    res = fn(url)
                    if isinstance(res, str) and res.startswith(("http://", "https://")):
                        return res
                    if isinstance(res, dict) and res.get("url"):
                        return str(res["url"])
                    if hasattr(res, "url") and getattr(res, "url"):
                        return str(getattr(res, "url"))
    return url


# ============================================================================
# 1. SMART GAME & FILE FORMAT DETECTOR + AUTO INSTALLATION PATH ROUTER
# ============================================================================

@dataclasses.dataclass
class FormatDetectionResult:
    filename: str
    category: str
    format_label_en: str
    format_label_ar: str
    recommended_path: str
    alternative_paths: List[str]
    extraction_strategy: str  # DIRECT_STREAM | EXTRACT_ON_PS5 | EXTRACT_IN_RAM | RAW_FTP_UPLOAD
    is_game_content: bool
    title_id: Optional[str] = None
    is_multipart: bool = False
    part_index: Optional[int] = None
    confidence: str = "HIGH"
    install_guide_en: str = ""
    install_guide_ar: str = ""
    detected_magic: Optional[str] = None

    def to_dict(self) -> Dict[str, Any]:
        return dataclasses.asdict(self)


class SmartFormatDetector:
    """
    Detects PS5/PS4 game formats, disc images, archives, payloads, plugins, and general files
    from filenames, URLs, HTTP headers, magic header bytes, or directory/archive trees,
    and resolves the optimal PS5 installation path automatically.
    """

    TITLE_ID_RE = re.compile(r"(?:^|[^A-Za-z0-9])(PPSA\d{5}|CUSA\d{5}|NPXS\d{5})(?![0-9])", re.IGNORECASE)
    MULTIPART_PATTERNS = [
        re.compile(r"\.part0*(\d+)\.(rar|zip|7z|pkg)$", re.IGNORECASE),
        re.compile(r"\.r(\d{2,3})$", re.IGNORECASE),
        re.compile(r"\.z(\d{2,3})$", re.IGNORECASE),
        re.compile(r"\.(\d{3})$"),
        re.compile(r"_(\d+)\.pkg$", re.IGNORECASE),
    ]

    # Magic byte signatures
    MAGIC_PS_PKG = b"\x7fCNT"
    MAGIC_PS5_PFS = b"PFSC"
    MAGIC_ELF = b"\x7fELF"
    MAGIC_ZIP = b"PK\x03\x04"
    MAGIC_RAR4 = b"Rar!\x1a\x07\x00"
    MAGIC_RAR5 = b"Rar!\x1a\x07\x01\x00"
    MAGIC_7Z = b"7z\xbc\xaf\x27\x1c"
    MAGIC_EXFAT = b"EXFAT   "

    STORAGE_ROOTS = {
        "internal": "/data",
        "m2_ssd": "/mnt/ext0",
        "usb0": "/mnt/usb0",
        "usb1": "/mnt/usb1",
    }

    @classmethod
    def extract_title_id(cls, text: str) -> Optional[str]:
        m = cls.TITLE_ID_RE.search(text or "")
        return m.group(1).upper() if m else None

    @classmethod
    def check_multipart(cls, filename: str) -> Tuple[bool, Optional[int]]:
        for pat in cls.MULTIPART_PATTERNS:
            m = pat.search(filename)
            if m:
                try:
                    return True, int(m.group(1))
                except ValueError:
                    return True, None
        return False, None

    @classmethod
    def detect(
        cls,
        source_name_or_url: str,
        magic_bytes: Optional[bytes] = None,
        inner_files: Optional[List[str]] = None,
        preferred_storage: str = "internal",
        force_general_ftp: bool = False,
        custom_ftp_path: Optional[str] = None,
    ) -> FormatDetectionResult:
        # Clean URL query/fragment if URL passed
        parsed_path = urllib.parse.unquote(urllib.parse.urlparse(source_name_or_url).path or source_name_or_url)
        filename = pathlib.PurePosixPath(parsed_path.replace("\\", "/")).name or "transfer.bin"
        lower_name = filename.lower()
        title_id = cls.extract_title_id(source_name_or_url) or cls.extract_title_id(filename)
        is_multi, part_idx = cls.check_multipart(filename)
        base_root = cls.STORAGE_ROOTS.get(preferred_storage, "/data")

        # Inspect magic bytes if available
        detected_magic = None
        if magic_bytes:
            if magic_bytes.startswith(cls.MAGIC_PS_PKG):
                detected_magic = "PS_PKG_HEADER (\\x7FCNT)"
            elif magic_bytes.startswith(cls.MAGIC_ELF):
                detected_magic = "ELF_BINARY (\\x7FELF)"
            elif magic_bytes.startswith(cls.MAGIC_ZIP):
                detected_magic = "ZIP_ARCHIVE"
            elif magic_bytes.startswith(cls.MAGIC_RAR5) or magic_bytes.startswith(cls.MAGIC_RAR4):
                detected_magic = "RAR_ARCHIVE"
            elif magic_bytes.startswith(cls.MAGIC_7Z):
                detected_magic = "7Z_ARCHIVE"
            elif cls.MAGIC_PS5_PFS in magic_bytes[:64]:
                detected_magic = "FFPFSC_IMAGE"
            elif cls.MAGIC_EXFAT in magic_bytes[:64]:
                detected_magic = "EXFAT_IMAGE"

        # If user explicitly chooses General FTP transfer with custom path
        if force_general_ftp:
            target = custom_ftp_path or f"{base_root}/ftp_transfers"
            return FormatDetectionResult(
                filename=filename,
                category="GENERAL_FTP",
                format_label_en="General FTP File / Folder Transfer",
                format_label_ar="نقل ملف أو فولدر عام عبر FTP",
                recommended_path=target,
                alternative_paths=["/data", "/data/homebrew", "/mnt/usb0", "/mnt/ext0", "/user/home"],
                extraction_strategy="RAW_FTP_UPLOAD",
                is_game_content=False,
                title_id=title_id,
                is_multipart=is_multi,
                part_index=part_idx,
                detected_magic=detected_magic,
                install_guide_en=f"Direct FTP transfer to {target} without game-specific processing.",
                install_guide_ar=f"سيتم نقل الملف مباشرة عبر الـ FTP إلى المسار {target} كما هو بدون تعديل.",
            )

        # 1. Check Unpacked Game Folder structure (inner_files or local directory)
        if inner_files:
            lower_inner = [f.lower().replace("\\", "/") for f in inner_files]
            has_param_json = any(f.endswith("sce_sys/param.json") or f == "param.json" for f in lower_inner)
            has_param_sfo = any(f.endswith("sce_sys/param.sfo") or f == "param.sfo" for f in lower_inner)
            has_eboot = any(f.endswith("eboot.bin") for f in lower_inner)
            inner_pkg = next((f for f in lower_inner if f.endswith(".pkg")), None)
            inner_disc = next(
                (f for f in lower_inner if f.endswith((".ffpfsc", ".exfat", ".ufs", ".iso"))),
                None,
            )

            if not title_id:
                for f in inner_files:
                    tid = cls.extract_title_id(f)
                    if tid:
                        title_id = tid
                        break

            # If it's a folder dump (not an archive file extension)
            if not lower_name.endswith((".zip", ".rar", ".7z", ".001")) and (has_param_json or has_eboot or has_param_sfo):
                folder_name = title_id if title_id else pathlib.PurePosixPath(filename).stem
                rec_path = f"{base_root}/homebrew/{folder_name}"
                return FormatDetectionResult(
                    filename=filename,
                    category="PS5_FOLDER_DUMP",
                    format_label_en=f"Unpacked PS5 Game Dump Folder ({title_id or folder_name})",
                    format_label_ar=f"فولدر لعبة PS5 مفكوك جاهز للتشغيل ({title_id or folder_name})",
                    recommended_path=rec_path,
                    alternative_paths=[
                        f"/data/homebrew/{folder_name}",
                        f"/mnt/ext0/homebrew/{folder_name}",
                        f"/data/etaHEN/games/{folder_name}",
                        f"/mnt/usb0/homebrew/{folder_name}",
                    ],
                    extraction_strategy="RAW_FTP_UPLOAD",
                    is_game_content=True,
                    title_id=title_id,
                    detected_magic=detected_magic,
                    install_guide_en="Unpacked PS5 Folder Dump (sce_sys/param.json + eboot.bin). Transfer to /data/homebrew/<GameFolder> and launch via itemzflow or etaHEN.",
                    install_guide_ar="لعبة بصيغة فولدر مفكوك (تحتوي على sce_sys/param.json و eboot.bin). يتم نقلها إلى /data/homebrew وتشغيلها مباشرة من خلال itemzflow أو etaHEN.",
                )

            # Archive containing a disc image
            if inner_disc:
                return FormatDetectionResult(
                    filename=filename,
                    category="ARCHIVE_WITH_DISC_IMAGE",
                    format_label_en="Archive Containing ShadowMount Disc Image (.ffpfsc/.exfat)",
                    format_label_ar="ملف مضغوط يحتوي على نسخة ديسك ShadowMount (.ffpfsc/.exfat)",
                    recommended_path=f"{base_root}/ShadowMount",
                    alternative_paths=["/data/ShadowMount", "/mnt/ext0/ShadowMount", "/mnt/usb0/ShadowMount"],
                    extraction_strategy="EXTRACT_IN_RAM",
                    is_game_content=True,
                    title_id=title_id,
                    is_multipart=is_multi,
                    part_index=part_idx,
                    detected_magic=detected_magic,
                    install_guide_en="Archive holds a ShadowMount disc image. Stream & extract directly into /data/ShadowMount.",
                    install_guide_ar="الملف المضغوط بداخله نسخة ديسك ShadowMount. سيتم فك الضغط أثناء البث ووضعها تلقائياً في /data/ShadowMount.",
                )

            # Archive containing a .pkg file
            if inner_pkg:
                return FormatDetectionResult(
                    filename=filename,
                    category="ARCHIVE_WITH_PKG",
                    format_label_en="Archive Containing Package File (.pkg)",
                    format_label_ar="ملف مضغوط يحتوي على حزمة تثبيت (.pkg)",
                    recommended_path=f"{base_root}/pkg",
                    alternative_paths=["/data/pkg", "/mnt/usb0/pkg", "/mnt/ext0/pkg"],
                    extraction_strategy="EXTRACT_IN_RAM",
                    is_game_content=True,
                    title_id=title_id,
                    is_multipart=is_multi,
                    part_index=part_idx,
                    detected_magic=detected_magic,
                    install_guide_en="Archive holds a .pkg file. Stream & extract directly into /data/pkg for Package Installer.",
                    install_guide_ar="الملف المضغوط بداخله ملف .pkg. سيتم فك الضغط أثناء البث ووضعه تلقائياً في /data/pkg للتثبيت من Package Installer.",
                )

        # 2. Direct Package Files (.pkg or magic \x7FCNT)
        if lower_name.endswith(".pkg") or detected_magic == "PS_PKG_HEADER (\\x7FCNT)":
            is_ps4 = bool(title_id and title_id.startswith("CUSA"))
            pkg_label_en = "PS4 FakePKG (.pkg)" if is_ps4 else "PS5 / PS4 Package (.pkg)"
            pkg_label_ar = f"ملف تثبيت حزمة {'PS4' if is_ps4 else 'PS5/PS4'} (.pkg)"
            if is_multi:
                pkg_label_en += " [Multi-Part Split PKG]"
                pkg_label_ar += " [مقسم لعدة أجزاء - سيتم دمجه تلقائياً]"
            return FormatDetectionResult(
                filename=filename,
                category="PACKAGE_PKG",
                format_label_en=pkg_label_en,
                format_label_ar=pkg_label_ar,
                recommended_path=f"{base_root}/pkg",
                alternative_paths=["/data/pkg", "/mnt/usb0", "/mnt/usb0/pkg", "/mnt/ext0/pkg"],
                extraction_strategy="DIRECT_STREAM",
                is_game_content=True,
                title_id=title_id,
                is_multipart=is_multi,
                part_index=part_idx,
                detected_magic=detected_magic,
                install_guide_en="Streams directly to /data/pkg. Once complete, go to PS5 Debug Settings -> Package Installer (or etaHEN Toolbox) to install.",
                install_guide_ar="يتم بث ملف الـ PKG مباشرة إلى مسار /data/pkg على البلايستيشن 5، وبعد الانتهاء يمكنك تسطيبه فوراً من Debug Settings -> Package Installer أو etaHEN.",
            )

        # 3. ShadowMount Disc Images (.ffpfsc, .exfat, .ufs, .iso)
        if lower_name.endswith((".ffpfsc", ".exfat", ".ufs", ".iso")) or detected_magic in ("FFPFSC_IMAGE", "EXFAT_IMAGE"):
            ext = pathlib.PurePosixPath(lower_name).suffix or ".ffpfsc"
            return FormatDetectionResult(
                filename=filename,
                category="DISC_IMAGE_SHADOWMOUNT",
                format_label_en=f"PS5 ShadowMount Disc Image ({ext})",
                format_label_ar=f"صورة قرص لعبة PS5 تعمل عبر ShadowMount ({ext})",
                recommended_path=f"{base_root}/ShadowMount",
                alternative_paths=["/data/ShadowMount", "/mnt/ext0/ShadowMount", "/mnt/usb0/ShadowMount", "/data/homebrew"],
                extraction_strategy="DIRECT_STREAM",
                is_game_content=True,
                title_id=title_id,
                is_multipart=is_multi,
                part_index=part_idx,
                detected_magic=detected_magic,
                install_guide_en="Streams directly to /data/ShadowMount. ShadowMount / itemzflow automatically mounts this image without copying.",
                install_guide_ar="صيغة صورة قرص خاصة بألعاب PS5. يتم وضعها تلقائياً في مسار /data/ShadowMount ليتم تشغيلها مباشرة عبر ShadowMount بدون الحاجة لفك الضغط.",
            )

        # 4. Compressed Game Archives (.rar, .zip, .7z, split archives)
        is_archive = (
            lower_name.endswith((".rar", ".zip", ".7z", ".tar", ".gz", ".zar"))
            or detected_magic in ("ZIP_ARCHIVE", "RAR_ARCHIVE", "7Z_ARCHIVE")
            or (is_multi and any(lower_name.endswith(ext) for ext in (".001", ".r00", ".z01")))
        )
        if is_archive:
            # Check if filename hints at an inner .ffpfsc or .pkg
            if ".ffpfsc" in lower_name or ".exfat" in lower_name or "shadowmount" in lower_name:
                return FormatDetectionResult(
                    filename=filename,
                    category="ARCHIVE_WITH_DISC_IMAGE",
                    format_label_en="Compressed ShadowMount Game Archive",
                    format_label_ar="أرشيف مضغوط للعبة بصيغة ShadowMount",
                    recommended_path=f"{base_root}/ShadowMount",
                    alternative_paths=["/data/ShadowMount", "/mnt/ext0/ShadowMount", "/data/homebrew"],
                    extraction_strategy="EXTRACT_IN_RAM",
                    is_game_content=True,
                    title_id=title_id,
                    is_multipart=is_multi,
                    part_index=part_idx,
                    detected_magic=detected_magic,
                    install_guide_en="Compressed ShadowMount disc dump. Recommended: Extract on-the-fly or via PS5 payload into /data/ShadowMount.",
                    install_guide_ar="ملف مضغوط للعبة ShadowMount. يتم توجيهه تلقائياً إلى /data/ShadowMount مع فك الضغط.",
                )
            if ".pkg" in lower_name or "fpkg" in lower_name:
                return FormatDetectionResult(
                    filename=filename,
                    category="ARCHIVE_WITH_PKG",
                    format_label_en="Compressed PKG Game Archive",
                    format_label_ar="أرشيف مضغوط يحتوي على ملف تثبيت PKG",
                    recommended_path=f"{base_root}/pkg",
                    alternative_paths=["/data/pkg", "/mnt/usb0/pkg", "/mnt/ext0/pkg"],
                    extraction_strategy="EXTRACT_IN_RAM",
                    is_game_content=True,
                    title_id=title_id,
                    is_multipart=is_multi,
                    part_index=part_idx,
                    detected_magic=detected_magic,
                    install_guide_en="Compressed PKG archive. Extracts directly to /data/pkg for immediate installation.",
                    install_guide_ar="أرشيف مضغوط للعبة PKG. يتم فك ضغطه وتوجيهه تلقائياً إلى /data/pkg للتسطيب.",
                )

            # Default for RAR/ZIP/7Z PS5 game dumps (folder dump inside archive)
            target_sub = f"/{title_id}" if title_id else ""
            return FormatDetectionResult(
                filename=filename,
                category="GAME_ARCHIVE_FOLDER_DUMP",
                format_label_en="Compressed PS5 Game Dump Archive (.rar / .zip / .7z)",
                format_label_ar="ملف لعبة PS5 مضغوط (.rar / .zip / .7z)",
                recommended_path=f"{base_root}/homebrew{target_sub}",
                alternative_paths=[
                    "/data/homebrew",
                    "/mnt/ext0/homebrew",
                    "/data/etaHEN/games",
                    "/data/unrar",
                    "/mnt/usb0/homebrew",
                ],
                extraction_strategy="EXTRACT_ON_PS5",
                is_game_content=True,
                title_id=title_id,
                is_multipart=is_multi,
                part_index=part_idx,
                detected_magic=detected_magic,
                install_guide_en="Game folder archive. Extracts directly into /data/homebrew (using PS5 unrar payload on port 9021 or host stream unpacker) ready for itemzflow.",
                install_guide_ar="أرشيف لعبة PS5. يتم توجيهه وفك ضغطه تلقائياً داخل /data/homebrew ليعمل مباشرة على itemzflow بدون استهلاك مساحة على جهازك.",
            )

        # 5. Console Payloads (.elf, .bin)
        if lower_name.endswith((".elf", ".bin")) or detected_magic == "ELF_BINARY (\\x7FELF)":
            return FormatDetectionResult(
                filename=filename,
                category="CONSOLE_PAYLOAD",
                format_label_en="PS5 Executable Payload (.elf / .bin)",
                format_label_ar="ملف بايلود خاص بالبلايستيشن 5 (.elf / .bin)",
                recommended_path=f"{base_root}/payloads",
                alternative_paths=["/data/payloads", "/data/etaHEN/payloads", "/data/homebrew"],
                extraction_strategy="RAW_FTP_UPLOAD",
                is_game_content=False,
                detected_magic=detected_magic,
                install_guide_en="PS5 Payload binary. Stored in /data/payloads (or can be injected directly to ELF loader port 9021).",
                install_guide_ar="ملف Payload (.elf/.bin). يتم حفظه تلقائياً في /data/payloads أو إرساله مباشرة لمنفذ البايلود 9021.",
            )

        # 6. Console Plugins (.prx, .sprx)
        if lower_name.endswith((".prx", ".sprx")):
            return FormatDetectionResult(
                filename=filename,
                category="CONSOLE_PLUGIN",
                format_label_en="PS5 etaHEN / GoldHEN Plugin (.prx / .sprx)",
                format_label_ar="إضافة Plugin لنظام etaHEN (.prx / .sprx)",
                recommended_path="/data/etaHEN/plugins",
                alternative_paths=["/data/etaHEN/plugins", "/data/GoldHEN/plugins", "/data/homebrew"],
                extraction_strategy="RAW_FTP_UPLOAD",
                is_game_content=False,
                detected_magic=detected_magic,
                install_guide_en="Console plugin. Automatically routed to /data/etaHEN/plugins.",
                install_guide_ar="ملف إضافة Plugin. يتم وضعه تلقائياً في مسار /data/etaHEN/plugins.",
            )

        # 7. Cheats & Patches (.shn, .mc4, .json)
        if lower_name.endswith((".shn", ".mc4")) or ("cheat" in lower_name and lower_name.endswith(".json")):
            return FormatDetectionResult(
                filename=filename,
                category="CHEAT_PATCH",
                format_label_en="PS5 Game Cheat / Trainer File (.shn / .mc4 / .json)",
                format_label_ar="ملف شفرات وتعديلات ألعاب (Cheats / Patches)",
                recommended_path="/data/etaHEN/cheats",
                alternative_paths=["/data/etaHEN/cheats", "/data/GoldHEN/cheats", "/data"],
                extraction_strategy="RAW_FTP_UPLOAD",
                is_game_content=False,
                title_id=title_id,
                detected_magic=detected_magic,
                install_guide_en="Cheat file routed to /data/etaHEN/cheats.",
                install_guide_ar="ملف شفرات ألعاب يتم توجيهه تلقائياً إلى /data/etaHEN/cheats.",
            )

        # 8. Media & General Files (Videos, Images, Configs, Backups, Anything else)
        if lower_name.endswith((".mp4", ".mkv", ".avi", ".mov", ".mp3", ".flac", ".wav", ".jpg", ".jpeg", ".png", ".webp")):
            return FormatDetectionResult(
                filename=filename,
                category="MEDIA_FILE",
                format_label_en="Media File (Video / Audio / Image)",
                format_label_ar="ملف وسائط (فيديو / صوت / صورة)",
                recommended_path=custom_ftp_path or f"{base_root}/media",
                alternative_paths=["/data/media", "/mnt/usb0", "/user/home", "/data"],
                extraction_strategy="RAW_FTP_UPLOAD",
                is_game_content=False,
                detected_magic=detected_magic,
                install_guide_en="Media file transferred via standard FTP to /data/media (or any custom folder you select).",
                install_guide_ar="ملف ميديا عادي يتم نقله عبر الـ FTP إلى /data/media أو أي مسار تختاره.",
            )

        # Fallback: General File over FTP
        return FormatDetectionResult(
            filename=filename,
            category="GENERAL_FTP_FILE",
            format_label_en="General File (Standard FTP Transfer)",
            format_label_ar="ملف عام (نقل مباشر عبر الـ FTP)",
            recommended_path=custom_ftp_path or f"{base_root}/ftp_transfers",
            alternative_paths=["/data/ftp_transfers", "/data", "/data/homebrew", "/mnt/usb0", "/mnt/ext0"],
            extraction_strategy="RAW_FTP_UPLOAD",
            is_game_content=False,
            title_id=title_id,
            detected_magic=detected_magic,
            install_guide_en="Standard FTP file transfer. You can send any file or folder to any directory on your PS5.",
            install_guide_ar="ملف عام غير مقيد بصيغة لعبة. يتم نقله عبر الـ FTP لأي فولدر تحدده على جهاز الـ PS5.",
        )

    @classmethod
    def inspect_local_path(cls, path_str: str, preferred_storage: str = "internal") -> FormatDetectionResult:
        p = pathlib.Path(path_str)
        if not p.exists():
            return cls.detect(path_str, preferred_storage=preferred_storage)

        if p.is_dir():
            inner_files = []
            for root, _dirs, files in os.walk(p):
                for f in files:
                    rel = os.path.relpath(os.path.join(root, f), p).replace("\\", "/")
                    inner_files.append(rel)
                if len(inner_files) > 300:
                    break
            return cls.detect(p.name, inner_files=inner_files, preferred_storage=preferred_storage)

        magic = None
        inner_files = None
        try:
            with p.open("rb") as fh:
                magic = fh.read(64)
            if zipfile.is_zipfile(p):
                with zipfile.ZipFile(p, "r") as zf:
                    inner_files = zf.namelist()[:200]
        except Exception:
            pass
        return cls.detect(p.name, magic_bytes=magic, inner_files=inner_files, preferred_storage=preferred_storage)


# ============================================================================
# 2. MULTI-CONSOLE DEVICE MANAGER & LAN AUTO-DISCOVERY
# ============================================================================

@dataclasses.dataclass
class ConsoleProfile:
    id: str
    name: str
    host: str
    ftp_port: int = 2121
    payload_port: int = 9021
    rpi_port: int = 12800
    username: str = "anonymous"
    password: str = ""
    preferred_storage: str = "internal"  # internal (/data) | m2_ssd (/mnt/ext0) | usb0 (/mnt/usb0)
    enabled: bool = True
    last_status: str = "UNKNOWN"  # ONLINE | OFFLINE | UNKNOWN
    last_banner: str = ""
    free_space_gb: Optional[float] = None

    def to_dict(self) -> Dict[str, Any]:
        return dataclasses.asdict(self)

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "ConsoleProfile":
        valid_fields = {f.name for f in dataclasses.fields(cls)}
        filtered = {k: v for k, v in data.items() if k in valid_fields}
        if not filtered.get("id"):
            filtered["id"] = f"ps5-{uuid.uuid4().hex[:8]}"
        return cls(**filtered)


class MultiConsoleManager:
    """
    Manages multiple PS5 consoles simultaneously, persists profiles to disk,
    tests connections in parallel, and scans the local LAN to auto-discover PS5 FTP servers.
    """

    def __init__(self, storage_file: pathlib.Path = CONSOLES_FILE) -> None:
        self.storage_file = storage_file
        self._lock = threading.RLock()
        self._consoles: Dict[str, ConsoleProfile] = {}
        self.load()

    def load(self) -> None:
        with self._lock:
            self.storage_file.parent.mkdir(parents=True, exist_ok=True)
            if self.storage_file.exists():
                try:
                    raw = json.loads(self.storage_file.read_text(encoding="utf-8"))
                    for item in raw.get("consoles", []):
                        prof = ConsoleProfile.from_dict(item)
                        self._consoles[prof.id] = prof
                except Exception:
                    pass
            if not self._consoles:
                default = ConsoleProfile(
                    id="ps5-primary",
                    name="PS5 Living Room (الجهاز الرئيسي)",
                    host="192.168.1.188",
                    ftp_port=2121,
                    payload_port=9021,
                    enabled=True,
                )
                self._consoles[default.id] = default
                self.save()

    def save(self) -> None:
        with self._lock:
            self.storage_file.parent.mkdir(parents=True, exist_ok=True)
            payload = {"consoles": [c.to_dict() for c in self._consoles.values()]}
            self.storage_file.write_text(json.dumps(payload, indent=2, ensure_ascii=False), encoding="utf-8")

    def list_consoles(self) -> List[ConsoleProfile]:
        with self._lock:
            return list(self._consoles.values())

    def get_enabled_consoles(self, target_ids: Optional[List[str]] = None) -> List[ConsoleProfile]:
        with self._lock:
            if target_ids:
                id_set = set(target_ids)
                return [c for c in self._consoles.values() if c.id in id_set]
            return [c for c in self._consoles.values() if c.enabled]

    def get_console(self, console_id: str) -> Optional[ConsoleProfile]:
        with self._lock:
            return self._consoles.get(console_id)

    def add_or_update_console(self, profile: ConsoleProfile) -> ConsoleProfile:
        with self._lock:
            self._consoles[profile.id] = profile
            self.save()
            return profile

    def remove_console(self, console_id: str) -> bool:
        with self._lock:
            if console_id in self._consoles:
                del self._consoles[console_id]
                self.save()
                return True
            return False

    def probe_console(self, console_id: str, timeout: float = 4.0) -> Dict[str, Any]:
        prof = self.get_console(console_id)
        if not prof:
            return {"ok": False, "error": "Console profile not found"}
        ftp = ftplib.FTP()
        try:
            banner = ftp.connect(prof.host, prof.ftp_port, timeout=timeout)
            ftp.login(prof.username or "anonymous", prof.password or "")
            ftp.voidcmd("TYPE I")
            pwd = ftp.pwd()
            prof.last_status = "ONLINE"
            prof.last_banner = banner or "FTP Ready"
            self.save()
            return {
                "ok": True,
                "console_id": prof.id,
                "name": prof.name,
                "host": prof.host,
                "port": prof.ftp_port,
                "banner": banner,
                "pwd": pwd,
                "status": "ONLINE",
            }
        except Exception as exc:
            prof.last_status = "OFFLINE"
            self.save()
            return {
                "ok": False,
                "console_id": prof.id,
                "name": prof.name,
                "host": prof.host,
                "port": prof.ftp_port,
                "error": str(exc),
                "status": "OFFLINE",
            }
        finally:
            with contextlib.suppress(Exception):
                ftp.close()

    def probe_all(self, timeout: float = 3.0) -> List[Dict[str, Any]]:
        consoles = self.list_consoles()
        if not consoles:
            return []
        with concurrent.futures.ThreadPoolExecutor(max_workers=min(16, len(consoles))) as pool:
            futures = [pool.submit(self.probe_console, c.id, timeout) for c in consoles]
            return [f.result() for f in futures]

    @staticmethod
    def _detect_local_subnet_prefix() -> str:
        with contextlib.suppress(Exception):
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.settimeout(0.5)
            s.connect(("8.8.8.8", 80))
            local_ip = s.getsockname()[0]
            s.close()
            parts = local_ip.split(".")
            if len(parts) == 4 and not local_ip.startswith("127."):
                return ".".join(parts[:3])
        return "192.168.1"

    def scan_lan_for_ps5(
        self,
        subnet_prefix: Optional[str] = None,
        ftp_port: int = 2121,
        timeout: float = 0.35,
        auto_add: bool = True,
    ) -> List[Dict[str, Any]]:
        prefix = subnet_prefix or self._detect_local_subnet_prefix()
        discovered: List[Dict[str, Any]] = []

        def check_ip(ip: str) -> Optional[Dict[str, Any]]:
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            sock.settimeout(timeout)
            try:
                if sock.connect_ex((ip, ftp_port)) == 0:
                    banner_bytes = b""
                    with contextlib.suppress(Exception):
                        banner_bytes = sock.recv(256)
                    banner = banner_bytes.decode("utf-8", errors="replace").strip()
                    return {"host": ip, "ftp_port": ftp_port, "banner": banner or "220 PS5 FTP Server"}
            except Exception:
                return None
            finally:
                with contextlib.suppress(Exception):
                    sock.close()
            return None

        ips = [f"{prefix}.{i}" for i in range(1, 255)]
        with concurrent.futures.ThreadPoolExecutor(max_workers=64) as pool:
            for res in pool.map(check_ip, ips):
                if res:
                    discovered.append(res)

        if auto_add and discovered:
            with self._lock:
                existing_hosts = {(c.host, c.ftp_port) for c in self._consoles.values()}
                for idx, item in enumerate(discovered, start=1):
                    key = (item["host"], item["ftp_port"])
                    if key not in existing_hosts:
                        cid = f"ps5-{item['host'].replace('.', '-')}"
                        prof = ConsoleProfile(
                            id=cid,
                            name=f"PS5 ({item['host']})",
                            host=item["host"],
                            ftp_port=item["ftp_port"],
                            last_status="ONLINE",
                            last_banner=item["banner"],
                            enabled=True,
                        )
                        self._consoles[cid] = prof
                self.save()
        return discovered


# ============================================================================
# 3. GENERAL-PURPOSE FTP FILE MANAGER & RECURSIVE DIRECTORY TRANSFER
# ============================================================================

class GeneralFtpManager:
    """
    Full-featured FTP client for PS5 consoles supporting:
    - Directory browsing (MLSD / LIST parser)
    - Ensuring remote directories exist recursively (mkdir -p)
    - Uploading ANY file or recursive folder tree to one or multiple PS5 consoles simultaneously
    - Downloading files from PS5 to local PC
    - Renaming, moving, and deleting remote files/folders
    """

    LIST_LINE_RE = re.compile(
        r"^([d\-lpscb])[rwxstST\-]{9}\s+\d+\s+\S+\s+\S+\s+(\d+)\s+"
        r"([A-Za-z]{3}\s+\d+\s+(?:\d{2}:\d{2}|\d{4}))\s+(.+)$"
    )

    @staticmethod
    def connect_ftp(console: ConsoleProfile, timeout: float = 15.0) -> ftplib.FTP:
        ftp = ftplib.FTP()
        ftp.connect(console.host, console.ftp_port, timeout=timeout)
        ftp.login(console.username or "anonymous", console.password or "")
        ftp.voidcmd("TYPE I")
        return ftp

    @classmethod
    def ensure_remote_dir(cls, ftp: ftplib.FTP, remote_dir: str) -> None:
        norm = posixpath.normpath(remote_dir.strip() or "/")
        if norm in ("/", "."):
            return
        parts = [p for p in norm.split("/") if p]
        curr = ""
        for part in parts:
            curr = f"{curr}/{part}"
            try:
                ftp.cwd(curr)
            except ftplib.error_perm:
                with contextlib.suppress(ftplib.error_perm):
                    ftp.mkd(curr)
                ftp.cwd(curr)

    @classmethod
    def list_directory(cls, console: ConsoleProfile, remote_path: str = "/") -> List[Dict[str, Any]]:
        ftp = cls.connect_ftp(console)
        entries: List[Dict[str, Any]] = []
        try:
            norm_path = posixpath.normpath(remote_path or "/")
            # Try MLSD first
            try:
                for name, facts in ftp.mlsd(norm_path):
                    if name in (".", ".."):
                        continue
                    ftype = facts.get("type", "file").lower()
                    is_dir = ftype in ("dir", "cdir", "pdir")
                    size = int(facts.get("size", 0) or 0)
                    entries.append({
                        "name": name,
                        "path": posixpath.join(norm_path, name),
                        "is_dir": is_dir,
                        "size": size,
                        "modified": facts.get("modify", ""),
                    })
                entries.sort(key=lambda x: (not x["is_dir"], x["name"].lower()))
                return entries
            except Exception:
                pass

            # Fallback to standard LIST
            lines: List[str] = []
            ftp.cwd(norm_path)
            ftp.retrlines("LIST", lines.append)
            for line in lines:
                m = cls.LIST_LINE_RE.match(line.strip())
                if m:
                    kind, size_str, mod_str, name = m.groups()
                    if name in (".", ".."):
                        continue
                    is_dir = kind == "d"
                    entries.append({
                        "name": name,
                        "path": posixpath.join(norm_path, name),
                        "is_dir": is_dir,
                        "size": int(size_str),
                        "modified": mod_str,
                    })
                else:
                    parts = line.split(None, 8)
                    if len(parts) >= 9:
                        name = parts[8]
                        if name not in (".", ".."):
                            is_dir = parts[0].startswith("d")
                            size = int(parts[4]) if parts[4].isdigit() else 0
                            entries.append({
                                "name": name,
                                "path": posixpath.join(norm_path, name),
                                "is_dir": is_dir,
                                "size": size,
                                "modified": f"{parts[5]} {parts[6]} {parts[7]}",
                            })
            entries.sort(key=lambda x: (not x["is_dir"], x["name"].lower()))
            return entries
        finally:
            with contextlib.suppress(Exception):
                ftp.close()

    @classmethod
    def mkdir(cls, console: ConsoleProfile, remote_path: str) -> Dict[str, Any]:
        ftp = cls.connect_ftp(console)
        try:
            cls.ensure_remote_dir(ftp, remote_path)
            return {"ok": True, "path": remote_path}
        finally:
            with contextlib.suppress(Exception):
                ftp.close()

    @classmethod
    def rename(cls, console: ConsoleProfile, old_path: str, new_path: str) -> Dict[str, Any]:
        ftp = cls.connect_ftp(console)
        try:
            ftp.rename(old_path, new_path)
            return {"ok": True, "old_path": old_path, "new_path": new_path}
        finally:
            with contextlib.suppress(Exception):
                ftp.close()

    @classmethod
    def delete_Recursive(cls, ftp: ftplib.FTP, target_path: str, is_dir: bool) -> None:
        if not is_dir:
            ftp.delete(target_path)
            return
        # List children and delete recursively
        lines: List[str] = []
        ftp.cwd(target_path)
        ftp.retrlines("LIST", lines.append)
        for line in lines:
            parts = line.split(None, 8)
            if len(parts) >= 9:
                name = parts[8]
                if name in (".", ".."):
                    continue
                child_path = posixpath.join(target_path, name)
                if parts[0].startswith("d"):
                    cls.delete_Recursive(ftp, child_path, True)
                else:
                    ftp.delete(child_path)
        ftp.rmd(target_path)

    @classmethod
    def delete_item(cls, console: ConsoleProfile, remote_path: str, is_dir: bool = False) -> Dict[str, Any]:
        ftp = cls.connect_ftp(console)
        try:
            cls.delete_Recursive(ftp, remote_path, is_dir)
            return {"ok": True, "deleted": remote_path}
        finally:
            with contextlib.suppress(Exception):
                ftp.close()

    @classmethod
    def download_file_to_local(cls, console: ConsoleProfile, remote_path: str, local_dest: pathlib.Path) -> pathlib.Path:
        ftp = cls.connect_ftp(console)
        try:
            local_dest.parent.mkdir(parents=True, exist_ok=True)
            with local_dest.open("wb") as fh:
                ftp.retrbinary(f"RETR {remote_path}", fh.write, blocksize=262144)
            return local_dest
        finally:
            with contextlib.suppress(Exception):
                ftp.close()


# ============================================================================
# 4. SIMULTANEOUS MULTI-CONSOLE BROADCAST ENGINE (1 SOURCE -> N PS5 CONSOLES)
# ============================================================================

@dataclasses.dataclass
class ConsoleTargetProgress:
    console_id: str
    console_name: str
    host: str
    ftp_port: int
    remote_final_path: str
    remote_part_path: str
    bytes_Transferred: int = 0
    total_bytes: int = 0
    speed_bps: float = 0.0
    status: str = "QUEUED"  # QUEUED | CONNECTING | STREAMING | VERIFYING | COMPLETED | FAILED | PAUSED
    error: Optional[str] = None

    def to_dict(self) -> Dict[str, Any]:
        pct = (self.bytes_Transferred / self.total_bytes * 100.0) if self.total_bytes > 0 else 0.0
        return {
            **dataclasses.asdict(self),
            "progress_pct": round(min(100.0, pct), 2),
            "speed_mbps": round(self.speed_bps / (1024 * 1024), 2),
        }


@dataclasses.dataclass
class BroadcastTransferJob:
    job_id: str
    source_type: str  # URL | LOCAL_FILE | LOCAL_FOLDER
    source_uri: str
    filename: str
    format_info: Dict[str, Any]
    destination_dir: str
    overwrite: bool
    status: str = "QUEUED"  # QUEUED | RUNNING | COMPLETED | PARTIAL_SUCCESS | FAILED | CANCELLED
    created_at: str = dataclasses.field(default_factory=lambda: datetime.datetime.now(datetime.timezone.utc).isoformat())
    total_bytes: int = 0
    source_bytes_read: int = 0
    targets: Dict[str, ConsoleTargetProgress] = dataclasses.field(default_factory=dict)
    error: Optional[str] = None

    def to_dict(self) -> Dict[str, Any]:
        return {
            "job_id": self.job_id,
            "source_type": self.source_type,
            "source_uri": self.source_uri,
            "filename": self.filename,
            "format_info": self.format_info,
            "destination_dir": self.destination_dir,
            "overwrite": self.overwrite,
            "status": self.status,
            "created_at": self.created_at,
            "total_bytes": self.total_bytes,
            "source_bytes_read": self.source_bytes_read,
            "targets": {cid: t.to_dict() for cid, t in self.targets.items()},
            "error": self.error,
        }


class MultiConsoleBroadcastEngine:
    """
    Streams an HTTP/HTTPS download or local file/folder ONCE into a bounded RAM fan-out queues
    and writes simultaneously to N PlayStation 5 FTP servers in parallel.
    Each console gets atomic .ps5part staging, size verification, and fault isolation.
    """

    def __init__(self, console_manager: MultiConsoleManager, chunk_size: int = 1024 * 1024) -> None:
        self.console_manager = console_manager
        self.chunk_size = chunk_size
        self._jobs: Dict[str, BroadcastTransferJob] = {}
        self._cancel_flags: Dict[str, threading.Event] = {}
        self._lock = threading.RLock()

    def list_jobs(self) -> List[Dict[str, Any]]:
        with self._lock:
            return [j.to_dict() for j in reversed(list(self._jobs.values()))]

    def get_job(self, job_id: str) -> Optional[Dict[str, Any]]:
        with self._lock:
            j = self._jobs.get(job_id)
            return j.to_dict() if j else None

    def cancel_job(self, job_id: str) -> bool:
        with self._lock:
            ev = self._cancel_flags.get(job_id)
            if ev:
                ev.set()
                if job_id in self._jobs:
                    self._jobs[job_id].status = "CANCELLED"
                return True
            return False

    def create_and_start_job(
        self,
        source_uri: str,
        source_type: str = "AUTO",
        target_console_ids: Optional[List[str]] = None,
        custom_destination_dir: Optional[str] = None,
        force_general_ftp: bool = False,
        overwrite: bool = True,
        run_async: bool = True,
    ) -> BroadcastTransferJob:
        consoles = self.console_manager.get_enabled_consoles(target_console_ids)
        if not consoles:
            raise ValueError("No enabled PS5 consoles selected for transfer.")

        # Determine source type
        if source_type == "AUTO":
            if source_uri.lower().startswith(("http://", "https://")):
                source_type = "URL"
            else:
                p = pathlib.Path(source_uri)
                source_type = "LOCAL_FOLDER" if p.is_dir() else "LOCAL_FILE"

        # Detect format & destination automatically
        preferred_storage = consoles[0].preferred_storage
        if source_type in ("LOCAL_FILE", "LOCAL_FOLDER"):
            fmt = SmartFormatDetector.inspect_local_path(source_uri, preferred_storage=preferred_storage)
            if force_general_ftp:
                fmt = SmartFormatDetector.detect(
                    source_uri,
                    preferred_storage=preferred_storage,
                    force_general_ftp=True,
                    custom_ftp_path=custom_destination_dir,
                )
        else:
            fmt = SmartFormatDetector.detect(
                source_uri,
                preferred_storage=preferred_storage,
                force_general_ftp=force_general_ftp,
                custom_ftp_path=custom_destination_dir,
            )

        dest_dir = custom_destination_dir.strip() if custom_destination_dir else fmt.recommended_path
        job_id = f"job-{uuid.uuid4().hex[:8]}"
        filename = fmt.filename

        targets: Dict[str, ConsoleTargetProgress] = {}
        for c in consoles:
            final_path = posixpath.join(dest_dir, filename)
            part_path = f"{final_path}.{job_id}.ps5part"
            targets[c.id] = ConsoleTargetProgress(
                console_id=c.id,
                console_name=c.name,
                host=c.host,
                ftp_port=c.ftp_port,
                remote_final_path=final_path,
                remote_part_path=part_path,
            )

        job = BroadcastTransferJob(
            job_id=job_id,
            source_type=source_type,
            source_uri=source_uri,
            filename=filename,
            format_info=fmt.to_dict(),
            destination_dir=dest_dir,
            overwrite=overwrite,
            targets=targets,
        )

        cancel_ev = threading.Event()
        with self._lock:
            self._jobs[job_id] = job
            self._cancel_flags[job_id] = cancel_ev

        if run_async:
            t = threading.Thread(target=self._execute_job, args=(job, consoles, cancel_ev), daemon=True)
            t.start()
        else:
            self._execute_job(job, consoles, cancel_ev)

        return job

    def _execute_job(
        self,
        job: BroadcastTransferJob,
        consoles: List[ConsoleProfile],
        cancel_ev: threading.Event,
    ) -> None:
        job.status = "RUNNING"
        try:
            if job.source_type == "LOCAL_FOLDER":
                self._execute_folder_broadcast(job, consoles, cancel_ev)
            elif job.source_type == "LOCAL_FILE":
                self._execute_single_stream_broadcast(job, consoles, cancel_ev, is_url=False)
            else:
                self._execute_single_stream_broadcast(job, consoles, cancel_ev, is_url=True)
        except Exception as exc:
            job.status = "FAILED"
            job.error = str(exc)

    def _open_source_stream(self, job: BroadcastTransferJob, is_url: bool) -> Tuple[Any, int]:
        if not is_url:
            p = pathlib.Path(job.source_uri)
            if not p.is_file():
                raise FileNotFoundError(f"Local file not found: {job.source_uri}")
            total_size = p.stat().st_size
            return p.open("rb"), total_size
        else:
            resolved_uri = resolve_url_with_legacy_engine(job.source_uri)
            req = urllib.request.Request(
                resolved_uri,
                headers={"User-Agent": f"DirectStreamPS5/{APP_VERSION} (MultiConsole; +https://github.com/yoyo-ui/direct-stream-ps5)"},
            )
            resp = urllib.request.urlopen(req, timeout=30)
            cl = resp.headers.get("Content-Length")
            total_size = int(cl) if cl and cl.isdigit() else 0
            return resp, total_size

    def _execute_single_stream_broadcast(
        self,
        job: BroadcastTransferJob,
        consoles: List[ConsoleProfile],
        cancel_ev: threading.Event,
        is_url: bool,
    ) -> None:
        stream, total_bytes = self._open_source_stream(job, is_url)
        job.total_bytes = total_bytes
        for t in job.targets.values():
            t.total_bytes = total_bytes

        # Create bounded per-console queues so 1 read feeds N console FTP writers in parallel
        # Max 32 chunks (~32 MiB per active console) in RAM ring buffer
        console_queues: Dict[str, "queue.Queue[Optional[bytes]]"] = {
            c.id: queue.Queue(maxsize=32) for c in consoles
        }
        active_ids = set(console_queues.keys())
        active_lock = threading.Lock()

        def console_writer_worker(console: ConsoleProfile) -> None:
            tprog = job.targets[console.id]
            q = console_queues[console.id]
            tprog.status = "CONNECTING"
            ftp: Optional[ftplib.FTP] = None
            data_sock: Optional[socket.socket] = None
            start_ts = time.monotonic()
            try:
                ftp = GeneralFtpManager.connect_ftp(console, timeout=15.0)
                GeneralFtpManager.ensure_remote_dir(ftp, job.destination_dir)

                if not job.overwrite:
                    with contextlib.suppress(Exception):
                        existing_size = ftp.size(tprog.remote_final_path)
                        if existing_size is not None and existing_size >= 0:
                            raise RuntimeError(f"Destination file already exists: {tprog.remote_final_path}")

                tprog.status = "STREAMING"
                data_sock = ftp.transfercmd(f"STOR {tprog.remote_part_path}")

                while not cancel_ev.is_set():
                    chunk = q.get()
                    if chunk is None:
                        break
                    data_sock.sendall(chunk)
                    tprog.bytes_Transferred += len(chunk)
                    elapsed = max(0.001, time.monotonic() - start_ts)
                    tprog.speed_bps = tprog.bytes_Transferred / elapsed

                data_sock.close()
                data_sock = None
                ftp.voidresp()

                if cancel_ev.is_set():
                    tprog.status = "CANCELLED"
                    with contextlib.suppress(Exception):
                        ftp.delete(tprog.remote_part_path)
                    return

                # Verify remote size & finalize atomic rename (.ps5part -> final)
                tprog.status = "VERIFYING"
                if total_bytes > 0:
                    with contextlib.suppress(Exception):
                        remote_sz = ftp.size(tprog.remote_part_path)
                        if remote_sz is not None and int(remote_sz) != total_bytes:
                            raise RuntimeError(f"Size mismatch on {console.name}: expected {total_bytes}, got {remote_sz}")

                if job.overwrite:
                    with contextlib.suppress(Exception):
                        ftp.delete(tprog.remote_final_path)
                ftp.rename(tprog.remote_part_path, tprog.remote_final_path)
                tprog.status = "COMPLETED"

            except Exception as exc:
                tprog.status = "FAILED"
                tprog.error = str(exc)
                with active_lock:
                    active_ids.discard(console.id)
                # Drain remaining items in queue so producer never blocks on a dead console
                while True:
                    try:
                        q.get_nowait()
                    except queue.Empty:
                        break
            finally:
                if data_sock is not None:
                    with contextlib.suppress(Exception):
                        data_sock.close()
                if ftp is not None:
                    with contextlib.suppress(Exception):
                        ftp.close()

        threads = [
            threading.Thread(target=console_writer_worker, args=(c,), daemon=True)
            for c in consoles
        ]
        for t in threads:
            t.start()

        try:
            first_chunk = True
            while not cancel_ev.is_set():
                with active_lock:
                    if not active_ids:
                        raise RuntimeError("All target PS5 consoles disconnected or failed.")
                chunk = stream.read(self.chunk_size)
                if not chunk:
                    break
                if first_chunk:
                    first_chunk = False
                    # Refine magic byte detection on the first chunk if URL stream
                    if is_url:
                        refined = SmartFormatDetector.detect(
                            job.source_uri,
                            magic_bytes=chunk[:64],
                            preferred_storage=consoles[0].preferred_storage,
                        )
                        job.format_info = refined.to_dict()
                job.source_bytes_read += len(chunk)
                for cid in list(active_ids):
                    while not cancel_ev.is_set():
                        with active_lock:
                            if cid not in active_ids:
                                break
                        try:
                            console_queues[cid].put(chunk, timeout=0.5)
                            break
                        except queue.Full:
                            continue
        finally:
            with contextlib.suppress(Exception):
                stream.close()
            for cid, q in console_queues.items():
                with contextlib.suppress(Exception):
                    q.put(None, timeout=2.0)
            for t in threads:
                t.join(timeout=30.0)

        # Evaluate overall job completion across all consoles
        completed_count = sum(1 for t in job.targets.values() if t.status == "COMPLETED")
        if cancel_ev.is_set():
            job.status = "CANCELLED"
        elif completed_count == len(job.targets):
            job.status = "COMPLETED"
        elif completed_count > 0:
            job.status = "PARTIAL_SUCCESS"
        else:
            job.status = "FAILED"
            errors = [f"{t.console_name}: {t.error}" for t in job.targets.values() if t.error]
            job.error = "; ".join(errors) if errors else "Transfer failed on all consoles."

    def _execute_folder_broadcast(
        self,
        job: BroadcastTransferJob,
        consoles: List[ConsoleProfile],
        cancel_ev: threading.Event,
    ) -> None:
        root_dir = pathlib.Path(job.source_uri)
        if not root_dir.is_dir():
            raise FileNotFoundError(f"Folder not found: {job.source_uri}")

        file_list: List[Tuple[pathlib.Path, str, int]] = []
        total_bytes = 0
        for dirpath, _dirnames, filenames in os.walk(root_dir):
            for fname in filenames:
                fpath = pathlib.Path(dirpath) / fname
                rel = os.path.relpath(fpath, root_dir).replace("\\", "/")
                sz = fpath.stat().st_size
                file_list.append((fpath, rel, sz))
                total_bytes += sz

        job.total_bytes = total_bytes
        for t in job.targets.values():
            t.total_bytes = total_bytes

        def upload_tree_to_console(console: ConsoleProfile) -> None:
            tprog = job.targets[console.id]
            tprog.status = "CONNECTING"
            start_ts = time.monotonic()
            ftp: Optional[ftplib.FTP] = None
            try:
                ftp = GeneralFtpManager.connect_ftp(console, timeout=15.0)
                tprog.status = "STREAMING"
                base_remote = job.destination_dir
                if not base_remote.endswith(f"/{root_dir.name}"):
                    base_remote = posixpath.join(base_remote, root_dir.name)
                GeneralFtpManager.ensure_remote_dir(ftp, base_remote)

                for local_file, rel_path, _sz in file_list:
                    if cancel_ev.is_set():
                        tprog.status = "CANCELLED"
                        return
                    remote_file_path = posixpath.join(base_remote, rel_path)
                    remote_parent = posixpath.dirname(remote_file_path)
                    GeneralFtpManager.ensure_remote_dir(ftp, remote_parent)

                    with local_file.open("rb") as fh:
                        sock = ftp.transfercmd(f"STOR {remote_file_path}")
                        try:
                            while not cancel_ev.is_set():
                                buf = fh.read(self.chunk_size)
                                if not buf:
                                    break
                                sock.sendall(buf)
                                tprog.bytes_Transferred += len(buf)
                                elapsed = max(0.001, time.monotonic() - start_ts)
                                tprog.speed_bps = tprog.bytes_Transferred / elapsed
                        finally:
                            sock.close()
                        ftp.voidresp()

                tprog.status = "COMPLETED"
            except Exception as exc:
                tprog.status = "FAILED"
                tprog.error = str(exc)
            finally:
                if ftp is not None:
                    with contextlib.suppress(Exception):
                        ftp.close()

        with concurrent.futures.ThreadPoolExecutor(max_workers=max(1, len(consoles))) as pool:
            futures = [pool.submit(upload_tree_to_console, c) for c in consoles]
            concurrent.futures.wait(futures)

        completed_count = sum(1 for t in job.targets.values() if t.status == "COMPLETED")
        if cancel_ev.is_set():
            job.status = "CANCELLED"
        elif completed_count == len(job.targets):
            job.status = "COMPLETED"
            job.source_bytes_read = total_bytes
        elif completed_count > 0:
            job.status = "PARTIAL_SUCCESS"
        else:
            job.status = "FAILED"


# ============================================================================
# 5. GITHUB AUTO-UPDATER (RELEASES & COMMITS API)
# ============================================================================

class GitHubAutoUpdater:
    """
    Checks GitHub Releases & Commits API for updates to yoyo-ui/direct-stream-ps5,
    downloads new release bundles or updated scripts, backs up current files,
    and applies updates atomically.
    """

    def __init__(self, repo: str = DEFAULT_GITHUB_REPO, current_version: str = APP_VERSION) -> None:
        self.repo = repo
        self.current_version = current_version

    @staticmethod
    def _parse_semver(ver_str: str) -> Tuple[int, ...]:
        cleaned = re.sub(r"^[^0-9]*", "", ver_str or "")
        nums = [int(x) for x in re.findall(r"\d+", cleaned)]
        return tuple(nums) if nums else (0, 0, 0)

    def check_for_updates(self, timeout: float = 8.0) -> Dict[str, Any]:
        headers = {
            "Accept": "application/vnd.github+json",
            "User-Agent": f"DirectStreamPS5-Updater/{self.current_version}",
        }
        # 1. Check latest GitHub Release on configured repo
        release_url = f"https://api.github.com/repos/{self.repo}/releases/latest"
        try:
            req = urllib.request.Request(release_url, headers=headers)
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                data = json.loads(resp.read().decode("utf-8"))
                latest_tag = data.get("tag_name", "")
                has_update = self._parse_semver(latest_tag) > self._parse_semver(self.current_version)
                assets = [
                    {
                        "name": a.get("name"),
                        "size": a.get("size"),
                        "download_url": a.get("browser_download_url"),
                        "content_type": a.get("content_type"),
                    }
                    for a in data.get("assets", [])
                ]
                return {
                    "ok": True,
                    "repo": self.repo,
                    "current_version": self.current_version,
                    "latest_version": latest_tag.lstrip("v") or self.current_version,
                    "tag_name": latest_tag,
                    "update_available": has_update,
                    "published_at": data.get("published_at", ""),
                    "release_notes": data.get("body", ""),
                    "html_url": data.get("html_url", f"https://github.com/{self.repo}/releases"),
                    "zipball_url": data.get("zipball_url", f"https://github.com/{self.repo}/archive/refs/heads/main.zip"),
                    "assets": assets,
                }
        except urllib.error.HTTPError as http_err:
            # If repository has no formal release tags yet (404), check latest commit on main branch
            if http_err.code == 404:
                return self._check_main_branch_commit(headers, timeout)
            return {
                "ok": False,
                "repo": self.repo,
                "current_version": self.current_version,
                "error": f"GitHub API HTTP {http_err.code}: {http_err.reason}",
            }
        except Exception as exc:
            return {
                "ok": False,
                "repo": self.repo,
                "current_version": self.current_version,
                "error": str(exc),
            }

    def _check_main_branch_commit(self, headers: Dict[str, str], timeout: float) -> Dict[str, Any]:
        commit_url = f"https://api.github.com/repos/{self.repo}/commits/main"
        try:
            req = urllib.request.Request(commit_url, headers=headers)
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                data = json.loads(resp.read().decode("utf-8"))
                sha = (data.get("sha") or "")[:7]
                commit_info = data.get("commit", {})
                message = commit_info.get("message", "")
                date = commit_info.get("committer", {}).get("date", "")
                return {
                    "ok": True,
                    "repo": self.repo,
                    "current_version": self.current_version,
                    "latest_version": f"{self.current_version} (main@{sha})",
                    "tag_name": f"main@{sha}",
                    "update_available": False,
                    "published_at": date,
                    "release_notes": f"Latest commit on main ({sha}):\n{message}",
                    "html_url": f"https://github.com/{self.repo}/commits/main",
                    "zipball_url": f"https://github.com/{self.repo}/archive/refs/heads/main.zip",
                    "assets": [],
                }
        except Exception as exc:
            return {
                "ok": False,
                "repo": self.repo,
                "current_version": self.current_version,
                "error": str(exc),
            }

    def _update_standalone_windows_exe(self, exe_url: str, timeout: float = 90.0) -> Dict[str, Any]:
        """Downloads the new .exe from GitHub Releases and spawns an atomic Windows swap script."""
        try:
            current_exe = CURRENT_EXECUTABLE
            new_exe = current_exe.with_name(current_exe.stem + "_new.exe")
            bak_exe = current_exe.with_name(current_exe.stem + "_backup.exe")
            headers = {"User-Agent": f"DirectStreamPS5-Updater/{self.current_version}"}
            req = urllib.request.Request(exe_url, headers=headers)
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                new_exe.write_bytes(resp.read())

            bat_path = current_exe.parent / "_apply_ps5_exe_update.bat"
            bat_content = (
                "@echo off\r\n"
                "timeout /t 2 /nobreak >nul\r\n"
                f'move /y "{current_exe}" "{bak_exe}" >nul 2>&1\r\n'
                f'move /y "{new_exe}" "{current_exe}" >nul\r\n'
                f'start "" "{current_exe}"\r\n'
                'del "%~f0"\r\n'
            )
            bat_path.write_text(bat_content, encoding="utf-8")
            subprocess.Popen(["cmd.exe", "/c", str(bat_path)], close_fds=True)
            return {
                "ok": True,
                "repo": self.repo,
                "download_url": exe_url,
                "backup_dir": str(bak_exe),
                "updated_files_count": 1,
                "message": "New standalone .exe downloaded! The application will now swap and restart.",
            }
        except Exception as exc:
            return {"ok": False, "repo": self.repo, "download_url": exe_url, "error": str(exc)}

    def download_and_apply_update(
        self,
        target_dir: Optional[pathlib.Path] = None,
        zip_url: Optional[str] = None,
        timeout: float = 60.0,
    ) -> Dict[str, Any]:
        # If running as a compiled standalone Windows .exe (PyInstaller), check for .exe asset first
        if IS_FROZEN_EXE and platform.system().lower().startswith("win"):
            info = self.check_for_updates(timeout=10.0)
            exe_asset = next(
                (a for a in info.get("assets", []) if str(a.get("name", "")).lower().endswith(".exe")),
                None,
            )
            if exe_asset and exe_asset.get("download_url"):
                return self._update_standalone_windows_exe(exe_asset["download_url"], timeout=timeout)

        app_dir = target_dir or pathlib.Path(__file__).resolve().parent
        download_url = zip_url or f"https://github.com/{self.repo}/archive/refs/heads/main.zip"
        headers = {"User-Agent": f"DirectStreamPS5-Updater/{self.current_version}"}

        try:
            req = urllib.request.Request(download_url, headers=headers)
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                raw_zip = resp.read()

            with tempfile.TemporaryDirectory(prefix="ps5_update_") as tmpdir:
                tmp_path = pathlib.Path(tmpdir)
                zf_path = tmp_path / "update.zip"
                zf_path.write_bytes(raw_zip)

                with zipfile.ZipFile(zf_path, "r") as zf:
                    zf.extractall(tmp_path / "extracted")

                extracted_roots = list((tmp_path / "extracted").iterdir())
                source_root = extracted_roots[0] if len(extracted_roots) == 1 and extracted_roots[0].is_dir() else (tmp_path / "extracted")

                backup_dir = CONFIG_DIR / f"backup_{datetime.datetime.now().strftime('%Y%m%d_%H%M%S')}"
                backup_dir.mkdir(parents=True, exist_ok=True)

                updated_files: List[str] = []
                for item in source_root.rglob("*"):
                    if item.is_file():
                        rel = item.relative_to(source_root)
                        if ".git" in rel.parts:
                            continue
                        dest_file = app_dir / rel
                        if dest_file.exists():
                            bak_file = backup_dir / rel
                            bak_file.parent.mkdir(parents=True, exist_ok=True)
                            shutil.copy2(dest_file, bak_file)
                        dest_file.parent.mkdir(parents=True, exist_ok=True)
                        shutil.copy2(item, dest_file)
                        updated_files.append(str(rel))

                return {
                    "ok": True,
                    "repo": self.repo,
                    "download_url": download_url,
                    "backup_dir": str(backup_dir),
                    "updated_files_count": len(updated_files),
                    "updated_files_sample": updated_files[:20],
                    "message": "Update downloaded and applied successfully. Restart the application to load changes.",
                }
        except Exception as exc:
            return {
                "ok": False,
                "repo": self.repo,
                "download_url": download_url,
                "error": str(exc),
            }


# ============================================================================
# 6. BILINGUAL WEB STUDIO DASHBOARD (HTML / CSS / JS) + REST API SERVER
# ============================================================================

DASHBOARD_HTML = """<!DOCTYPE html>
<html lang="ar" dir="rtl">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Direct Stream PS5 v3.0 — Multi-Console & Smart FTP Studio</title>
<style>
  :root {
    --bg: #0b0f19;
    --card: #131b2e;
    --card-border: #1e293b;
    --primary: #3b82f6;
    --primary-hover: #2563eb;
    --accent: #06b6d4;
    --success: #10b981;
    --warning: #f59e0b;
    --danger: #ef4444;
    --text: #f8fafc;
    --muted: #94a3b8;
  }
  * { box-sizing: border-box; margin: 0; padding: 0; font-family: system-ui, -apple-system, 'Segoe UI', Tahoma, sans-serif; }
  body { background: var(--bg); color: var(--text); min-height: 100vh; padding: 20px; }
  .container { max-width: 1260px; margin: 0 auto; }
  header {
    display: flex; justify-content: space-between; align-items: center;
    background: linear-gradient(135deg, #111827, #1e293b);
    padding: 18px 24px; border-radius: 14px; border: 1px solid var(--card-border);
    margin-bottom: 20px; flex-wrap: wrap; gap: 12px;
  }
  .brand h1 { font-size: 1.35rem; color: #fff; display: flex; align-items: center; gap: 10px; }
  .badge { background: rgba(59,130,246,0.2); color: #60a5fa; border: 1px solid #3b82f6; padding: 3px 10px; border-radius: 999px; font-size: 0.78rem; }
  .tabs { display: flex; gap: 10px; margin-bottom: 20px; flex-wrap: wrap; }
  .tab-btn {
    background: var(--card); color: var(--muted); border: 1px solid var(--card-border);
    padding: 11px 20px; border-radius: 10px; cursor: pointer; font-weight: 600; transition: 0.2s;
  }
  .tab-btn.active, .tab-btn:hover { background: var(--primary); color: #fff; border-color: var(--primary); }
  .panel { display: none; background: var(--card); border: 1px solid var(--card-border); border-radius: 14px; padding: 22px; margin-bottom: 20px; }
  .panel.active { display: block; }
  h2 { font-size: 1.15rem; margin-bottom: 14px; color: #e2e8f0; }
  .grid-2 { display: grid; grid-template-columns: repeat(auto-fit, minmax(340px, 1fr)); gap: 16px; }
  .form-group { margin-bottom: 14px; }
  label { display: block; font-size: 0.88rem; color: var(--muted); margin-bottom: 6px; }
  input, select {
    width: 100%; padding: 10px 12px; border-radius: 8px; border: 1px solid #334155;
    background: #0f172a; color: #fff; font-size: 0.92rem;
  }
  .btn-row { display: flex; gap: 10px; flex-wrap: wrap; margin-top: 10px; }
  button.btn {
    padding: 10px 18px; border-radius: 8px; border: none; cursor: pointer;
    font-weight: 600; font-size: 0.9rem; background: var(--primary); color: #fff; transition: 0.15s;
  }
  button.btn:hover { background: var(--primary-hover); }
  button.btn-secondary { background: #334155; }
  button.btn-success { background: var(--success); }
  button.btn-danger { background: var(--danger); }
  .format-box {
    background: rgba(6, 182, 212, 0.1); border: 1px solid var(--accent);
    border-radius: 10px; padding: 14px; margin-top: 12px;
  }
  .console-card {
    background: #0f172a; border: 1px solid #334155; border-radius: 10px; padding: 14px;
    display: flex; justify-content: space-between; align-items: center; gap: 10px; margin-bottom: 10px;
  }
  .status-dot { width: 10px; height: 10px; border-radius: 50%; display: inline-block; background: var(--muted); }
  .status-dot.ONLINE { background: var(--success); box-shadow: 0 0 8px var(--success); }
  .status-dot.OFFLINE { background: var(--danger); }
  .progress-bar { width: 100%; height: 10px; background: #1e293b; border-radius: 999px; overflow: hidden; margin-top: 6px; }
  .progress-fill { height: 100%; background: linear-gradient(90deg, var(--primary), var(--accent)); width: 0%; transition: width 0.3s; }
  table { width: 100%; border-collapse: collapse; margin-top: 12px; font-size: 0.9rem; }
  th, td { padding: 10px 12px; border-bottom: 1px solid #1e293b; text-align: right; }
  th { color: var(--muted); background: #0f172a; }
  .ltr { direction: ltr; text-align: left; font-family: monospace; }
</style>
</head>
<body>
<div class="container">
  <header>
    <div class="brand">
      <h1>🎮 Direct Stream PS5 <span class="badge">v3.0 Multi-Console + Smart Format + FTP + GitHub Auto-Update</span></h1>
      <p style="color:var(--muted); font-size:0.86rem; margin-top:4px;">بث مباشر للألعاب والملفات لأكثر من جهاز PS5 في نفس الوقت + التعرف التلقائي على صيغة اللعبة ومسار التثبيت + مدير FTP شامل</p>
    </div>
    <div class="btn-row">
      <button class="btn btn-secondary" onclick="checkGitHubUpdate()">🔄 فحص التحديثات (GitHub Auto-Update)</button>
      <button class="btn btn-secondary" onclick="scanLan()">📡 بحث تلقائي عن أجهزة PS5 بالشبكة</button>
    </div>
  </header>

  <div class="tabs">
    <button class="tab-btn active" onclick="switchTab('transfer', this)">🚀 نقل ذكي وبث متزامن (Smart Stream)</button>
    <button class="tab-btn" onclick="switchTab('consoles', this)">🖥️ أجهزة PS5 المتصلة (Multi-Console)</button>
    <button class="tab-btn" onclick="switchTab('ftp', this)">📁 نقل FTP حر وإدارة الملفات (General FTP)</button>
    <button class="tab-btn" onclick="switchTab('updates', this)">⬆️ التحديث التلقائي عبر GitHub</button>
  </div>

  <!-- TAB 1: SMART TRANSFER & MULTI-CONSOLE BROADCAST -->
  <div id="tab-transfer" class="panel active">
    <h2>1. إضافة رابط تحميل أو ملف/فولدر محلي والتعرف التلقائي على الصيغة</h2>
    <div class="grid-2">
      <div>
        <div class="form-group">
          <label>رابط التحميل المباشر (HTTP/HTTPS) أو المسار المحلي للملف/الفولدر على الـ PC:</label>
          <input type="text" id="sourceUri" class="ltr" placeholder="https://example.com/PPSA01234_Game.pkg or C:\Games\PPSA01234" oninput="autoDetectFormat()">
        </div>
        <div class="form-group">
          <label>نوع النقل:</label>
          <select id="transferMode" onchange="autoDetectFormat()">
            <option value="smart">تعرف ذكي تلقائي على صيغة اللعبة ومسار التسطيب (Smart Game Routing)</option>
            <option value="general_ftp">نقل ملف/فولدر عادي عبر الـ FTP لأي مكان (General FTP Mode)</option>
          </select>
        </div>
        <div class="form-group">
          <label>مسار التثبيت على الـ PS5 (يتم تحديده تلقائياً ويمكنك تعديله):</label>
          <input type="text" id="destPath" class="ltr" value="/data/pkg">
        </div>
        <div class="btn-row">
          <button class="btn" onclick="autoDetectFormat()">🔍 فحص الصيغة ومسار التسطيب</button>
          <button class="btn btn-success" onclick="startBroadcastTransfer()">⚡ بدء التحميل والبث لكل الأجهزة المحددة في نفس الوقت</button>
        </div>
      </div>

      <div>
        <div id="formatResult" class="format-box">
          <strong>🎯 نتيجة التعرف الذكي على الصيغة (Smart Format Detector):</strong>
          <div id="formatDetails" style="margin-top:8px; font-size:0.9rem; line-height:1.6; color:#cbd5e1;">
            اكتب اسم الملف أو الصق الرابط ليقوم البرنامج بالتعرف على صيغة اللعبة (.pkg / .ffpfsc / Folder Dump / Archive / Payload / General File) وتحديد مكان تسطيبها تلقائياً.
          </div>
        </div>
      </div>
    </div>

    <h2 style="margin-top:24px;">📊 عمليات النقل المتزامنة الجارية (Simultaneous Multi-Console Queue)</h2>
    <div id="jobsContainer">لا توجد عمليات نقل حالياً.</div>
  </div>

  <!-- TAB 2: MULTI-CONSOLE MANAGER -->
  <div id="tab-consoles" class="panel">
    <h2>🖥️ إدارة أجهزة البلايستيشن 5 المتصلة (توصيل وتحميل على أكثر من جهاز في نفس الوقت)</h2>
    <div class="grid-2">
      <div>
        <h3>إضافة جهاز PS5 جديد</h3>
        <div class="form-group" style="margin-top:10px;">
          <label>اسم الجهاز (مثال: غرفة المعيشة / جهاز رقم 2):</label>
          <input type="text" id="newConsoleName" placeholder="PS5 Console #2">
        </div>
        <div class="form-group">
          <label>عنوان الـ IP الخاص بالـ PS5:</label>
          <input type="text" id="newConsoleHost" class="ltr" placeholder="192.168.1.189">
        </div>
        <div class="form-group">
          <label>منفذ الـ FTP (الافتراضي 2121):</label>
          <input type="number" id="newConsolePort" class="ltr" value="2121">
        </div>
        <div class="form-group">
          <label>وحدة التخزين المفضلة على هذا الجهاز:</label>
          <select id="newConsoleStorage">
            <option value="internal">التخزين الداخلي الأساسي (/data)</option>
            <option value="m2_ssd">هارد M.2 الإضافي (/mnt/ext0)</option>
            <option value="usb0">فلاشة أو هارد خارجي USB0 (/mnt/usb0)</option>
          </select>
        </div>
        <div class="btn-row">
          <button class="btn" onclick="addConsole()">➕ إضافة الجهاز للقائمة</button>
          <button class="btn btn-secondary" onclick="probeAllConsoles()">🔌 فحص الاتصال بكل الأجهزة</button>
          <button class="btn btn-secondary" onclick="scanLan()">📡 بحث تلقائي في الشبكة (LAN Scan)</button>
        </div>
      </div>
      <div>
        <h3>الأجهزة المسجلة (حدد الأجهزة التي تريد البث إليها معاً)</h3>
        <div id="consolesList" style="margin-top:10px;"></div>
      </div>
    </div>
  </div>

  <!-- TAB 3: GENERAL FTP FILE MANAGER -->
  <div id="tab-ftp" class="panel">
    <h2>📁 مدير ملفات الـ FTP الشامل (نقل أي ملف أو فولدر بدون التقيد بالألعاب)</h2>
    <div class="grid-2">
      <div>
        <div class="form-group">
          <label>اختر جهاز الـ PS5 لتصفح ملفاته:</label>
          <select id="ftpConsoleSelect" onchange="browseFtpDir()"></select>
        </div>
      </div>
      <div>
        <div class="form-group">
          <label>المسار الحالي على الـ PS5:</label>
          <div style="display:flex; gap:8px;">
            <input type="text" id="ftpCurrentPath" class="ltr" value="/data">
            <button class="btn" onclick="browseFtpDir()">تصفح</button>
            <button class="btn btn-secondary" onclick="createFtpFolder()">+ فولدر جديد</button>
          </div>
        </div>
      </div>
    </div>

    <div class="form-group" style="margin-top:10px; background:#0f172a; padding:14px; border-radius:10px; border:1px solid #334155;">
      <strong>📤 رفع أي ملف أو فولدر من الكمبيوتر إلى المسار الحالي عبر الـ FTP (لجهاز واحد أو كل الأجهزة معاً):</strong>
      <div style="display:flex; gap:10px; margin-top:8px; flex-wrap:wrap;">
        <input type="text" id="ftpLocalUploadPath" class="ltr" style="flex:1;" placeholder="C:\path\to\any_file_or_folder.mp4">
        <button class="btn btn-success" onclick="uploadGeneralFtpFile(false)">رفع للجهاز المحدد فقط</button>
        <button class="btn" onclick="uploadGeneralFtpFile(true)">⚡ رفع لكل الأجهزة المفعلة معاً</button>
      </div>
    </div>

    <table>
      <thead>
        <tr>
          <th>الاسم (Name)</th>
          <th>النوع</th>
          <th>الحجم</th>
          <th>إجراءات FTP</th>
        </tr>
      </thead>
      <tbody id="ftpTableBody">
        <tr><td colspan="4" style="color:var(--muted);">اضغط "تصفح" لعرض ملفات البلايستيشن 5.</td></tr>
      </tbody>
    </table>
  </div>

  <!-- TAB 4: GITHUB AUTO-UPDATE -->
  <div id="tab-updates" class="panel">
    <h2>⬆️ التحديث التلقائي عبر GitHub (Auto-Update through GitHub)</h2>
    <p style="color:var(--muted); margin-bottom:14px;">يتصل البرنامج بمستودع GitHub الرسمي (<code>https://github.com/yoyo-ui/direct-stream-ps5</code>) للتحقق من أحدث إصدار وتحميل التحديثات وتثبيتها تلقائياً مع حفظ نسخة احتياطية.</p>
    <div class="form-group">
      <label>مستودع GitHub المعتمد للتحديث التلقائي:</label>
      <input type="text" id="githubRepoInput" class="ltr" value="yoyo-ui/direct-stream-ps5">
    </div>
    <div class="btn-row">
      <button class="btn" onclick="checkGitHubUpdate()">🔍 التحقق من وجود تحديث جديد على GitHub</button>
      <button class="btn btn-success" onclick="applyGitHubUpdate()">⬇️ تحميل وتثبيت أحدث نسخة من GitHub الآن</button>
    </div>
    <div id="updateStatusBox" class="format-box" style="margin-top:16px;">
      الإصدار الحالي: <strong>v3.0.0</strong> — اضغط للتحقق من آخر تحديثات المستودع.
    </div>
  </div>
</div>

<script>
function switchTab(name, btn) {
  document.querySelectorAll('.panel').forEach(p => p.classList.remove('active'));
  document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
  document.getElementById('tab-' + name).classList.add('active');
  btn.classList.add('active');
}

async function api(path, method='GET', body=null) {
  const opts = { method, headers: {'Content-Type': 'application/json'} };
  if (body) opts.body = JSON.stringify(body);
  const res = await fetch(path, opts);
  return await res.json();
}

async function autoDetectFormat() {
  const uri = document.getElementById('sourceUri').value.trim();
  const mode = document.getElementById('transferMode').value;
  if (!uri) return;
  const res = await api('/api/detect-format', 'POST', {
    source_uri: uri,
    force_general_ftp: mode === 'general_ftp',
    custom_ftp_path: mode === 'general_ftp' ? document.getElementById('destPath').value : null
  });
  if (res.ok) {
    const d = res.detection;
    document.getElementById('destPath').value = d.recommended_path;
    document.getElementById('formatDetails').innerHTML = `
      <div><strong>نوع الملف المكتشف:</strong> ${d.format_label_ar} <span class="badge">${d.category}</span></div>
      <div><strong>مسار التسطيب التلقائي:</strong> <code class="ltr">${d.recommended_path}</code></div>
      <div><strong>البدائل المتاحة:</strong> <code class="ltr">${(d.alternative_paths || []).join(' , ')}</code></div>
      <div><strong>استراتيجية النقل:</strong> <code>${d.extraction_strategy}</code> ${d.title_id ? ' | <strong>Title ID:</strong> <code>' + d.title_id + '</code>' : ''}</div>
      <div style="margin-top:6px; color:#a7f3d0;">💡 ${d.install_guide_ar}</div>
    `;
  }
}

async function loadConsoles() {
  const res = await api('/api/consoles');
  const list = document.getElementById('consolesList');
  const sel = document.getElementById('ftpConsoleSelect');
  list.innerHTML = '';
  sel.innerHTML = '';
  (res.consoles || []).forEach(c => {
    sel.innerHTML += `<option value="${c.id}">${c.name} (${c.host}:${c.ftp_port})</option>`;
    list.innerHTML += `
      <div class="console-card">
        <div>
          <label style="display:flex; align-items:center; gap:8px; color:#fff; font-weight:600; cursor:pointer;">
            <input type="checkbox" style="width:auto;" ${c.enabled ? 'checked' : ''} onchange="toggleConsole('${c.id}', this.checked)">
            <span class="status-dot ${c.last_status}"></span>
            ${c.name}
          </label>
          <div class="ltr" style="font-size:0.82rem; color:var(--muted); margin-top:4px;">
            ${c.host}:${c.ftp_port} | Storage: ${c.preferred_storage} | Status: ${c.last_status}
          </div>
        </div>
        <div class="btn-row" style="margin:0;">
          <button class="btn btn-secondary" onclick="probeOne('${c.id}')">فحص</button>
          <button class="btn btn-danger" onclick="deleteConsole('${c.id}')">حذف</button>
        </div>
      </div>
    `;
  });
}

async function addConsole() {
  const name = document.getElementById('newConsoleName').value.trim() || 'PS5 Console';
  const host = document.getElementById('newConsoleHost').value.trim();
  const ftp_port = parseInt(document.getElementById('newConsolePort').value || '2121', 10);
  const preferred_storage = document.getElementById('newConsoleStorage').value;
  if (!host) return alert('يرجى إدخال عنوان الـ IP الخاص بجهاز الـ PS5');
  await api('/api/consoles', 'POST', { name, host, ftp_port, preferred_storage, enabled: true });
  await loadConsoles();
}

async function toggleConsole(id, enabled) {
  await api('/api/consoles/toggle', 'POST', { console_id: id, enabled });
  await loadConsoles();
}

async function deleteConsole(id) {
  await api('/api/consoles/delete', 'POST', { console_id: id });
  await loadConsoles();
}

async function probeOne(id) {
  await api('/api/consoles/probe', 'POST', { console_id: id });
  await loadConsoles();
}

async function probeAllConsoles() {
  await api('/api/consoles/probe-all', 'POST', {});
  await loadConsoles();
}

async function scanLan() {
  const res = await api('/api/consoles/scan-lan', 'POST', {});
  await loadConsoles();
  alert(`تم فحص الشبكة! الأجهزة المكتشفة: ${(res.discovered || []).length}`);
}

async function startBroadcastTransfer() {
  const uri = document.getElementById('sourceUri').value.trim();
  const dest = document.getElementById('destPath').value.trim();
  const mode = document.getElementById('transferMode').value;
  if (!uri) return alert('يرجى إدخال رابط التحميل أو مسار الملف أولاً');
  const res = await api('/api/jobs/start', 'POST', {
    source_uri: uri,
    custom_destination_dir: dest,
    force_general_ftp: mode === 'general_ftp'
  });
  if (!res.ok) return alert('خطأ: ' + res.error);
  refreshJobs();
}

async function refreshJobs() {
  const res = await api('/api/jobs');
  const box = document.getElementById('jobsContainer');
  const jobs = res.jobs || [];
  if (!jobs.length) {
    box.innerHTML = '<div style="color:var(--muted);">لا توجد عمليات نقل حالياً.</div>';
    return;
  }
  box.innerHTML = jobs.map(j => {
    const targetsHtml = Object.values(j.targets || {}).map(t => `
      <div style="margin-top:8px; padding:8px; background:#131b2e; border-radius:8px;">
        <div style="display:flex; justify-content:space-between; font-size:0.85rem;">
          <span>🖥️ <strong>${t.console_name}</strong> (${t.host}:${t.ftp_port}) → <code class="ltr">${t.remote_final_path}</code></span>
          <span>${t.status} | ${t.progress_pct}% (${t.speed_mbps} MB/s)</span>
        </div>
        <div class="progress-bar"><div class="progress-fill" style="width:${t.progress_pct}%"></div></div>
        ${t.error ? `<div style="color:var(--danger); font-size:0.8rem; margin-top:4px;">⚠️ ${t.error}</div>` : ''}
      </div>
    `).join('');
    return `
      <div style="background:#0f172a; border:1px solid #334155; border-radius:10px; padding:14px; margin-bottom:12px;">
        <div style="display:flex; justify-content:space-between; align-items:center;">
          <div>
            <strong>📦 ${j.filename}</strong>
            <span class="badge">${j.format_info ? j.format_info.format_label_ar : j.source_type}</span>
            <span class="badge">${j.status}</span>
          </div>
          <button class="btn btn-danger" style="padding:5px 12px; font-size:0.8rem;" onclick="cancelJob('${j.job_id}')">إلغاء</button>
        </div>
        ${targetsHtml}
      </div>
    `;
  }).join('');
}

async function cancelJob(job_id) {
  await api('/api/jobs/cancel', 'POST', { job_id });
  refreshJobs();
}

async function browseFtpDir() {
  const cid = document.getElementById('ftpConsoleSelect').value;
  const path = document.getElementById('ftpCurrentPath').value.trim() || '/';
  const tbody = document.getElementById('ftpTableBody');
  const res = await api('/api/ftp/list', 'POST', { console_id: cid, remote_path: path });
  if (!res.ok) {
    tbody.innerHTML = `<tr><td colspan="4" style="color:var(--danger);">تعذر الاتصال بالـ FTP: ${res.error}</td></tr>`;
    return;
  }
  const entries = res.entries || [];
  if (!entries.length) {
    tbody.innerHTML = `<tr><td colspan="4" style="color:var(--muted);">الفولدر فارغ.</td></tr>`;
    return;
  }
  tbody.innerHTML = entries.map(e => `
    <tr>
      <td class="ltr" style="cursor:pointer; color:${e.is_dir ? '#60a5fa' : '#f8fafc'};" onclick="${e.is_dir ? `openSubDir('${e.path}')` : ''}">
        ${e.is_dir ? '📁 ' : '📄 '}${e.name}
      </td>
      <td>${e.is_dir ? 'Folder' : 'File'}</td>
      <td class="ltr">${e.is_dir ? '-' : (e.size / (1024*1024)).toFixed(2) + ' MB'}</td>
      <td>
        <button class="btn btn-danger" style="padding:4px 10px; font-size:0.78rem;" onclick="deleteFtpItem('${cid}', '${e.path}', ${e.is_dir})">حذف</button>
      </td>
    </tr>
  `).join('');
}

function openSubDir(path) {
  document.getElementById('ftpCurrentPath').value = path;
  browseFtpDir();
}

async function createFtpFolder() {
  const cid = document.getElementById('ftpConsoleSelect').value;
  const base = document.getElementById('ftpCurrentPath').value.trim() || '/';
  const name = prompt('اسم الفولدر الجديد على الـ PS5:');
  if (!name) return;
  await api('/api/ftp/mkdir', 'POST', { console_id: cid, remote_path: base.replace(/\/$/, '') + '/' + name });
  browseFtpDir();
}

async function deleteFtpItem(cid, path, is_dir) {
  if (!confirm('هل تريد حذف ' + path + ' ؟')) return;
  await api('/api/ftp/delete', 'POST', { console_id: cid, remote_path: path, is_dir });
  browseFtpDir();
}

async function uploadGeneralFtpFile(allConsoles) {
  const localPath = document.getElementById('ftpLocalUploadPath').value.trim();
  const remoteDir = document.getElementById('ftpCurrentPath').value.trim() || '/data';
  const cid = document.getElementById('ftpConsoleSelect').value;
  if (!localPath) return alert('أدخل مسار الملف أو الفولدر المحلي على الكمبيوتر');
  const res = await api('/api/jobs/start', 'POST', {
    source_uri: localPath,
    custom_destination_dir: remoteDir,
    force_general_ftp: true,
    target_console_ids: allConsoles ? null : [cid]
  });
  if (!res.ok) return alert('خطأ: ' + res.error);
  switchTab('transfer', document.querySelector('.tab-btn'));
  refreshJobs();
}

async function checkGitHubUpdate() {
  const repo = document.getElementById('githubRepoInput').value.trim() || 'yoyo-ui/direct-stream-ps5';
  const box = document.getElementById('updateStatusBox');
  box.innerHTML = '⏳ جاري الاتصال بـ GitHub API للتحقق من آخر التحديثات...';
  const res = await api('/api/update/check', 'POST', { repo });
  if (!res.ok) {
    box.innerHTML = `<span style="color:var(--warning);">⚠️ تعذر الوصول إلى GitHub API حالياً: ${res.error}</span>`;
    return;
  }
  box.innerHTML = `
    <div><strong>المستودع:</strong> <a href="${res.html_url}" target="_blank" style="color:#60a5fa;">${res.repo}</a></div>
    <div><strong>الإصدار الحالي:</strong> v${res.current_version} | <strong>أحدث إصدار على GitHub:</strong> ${res.latest_version}</div>
    <div><strong>الحالة:</strong> ${res.update_available ? '<span style="color:var(--success); font-weight:700;">🎉 يوجد تحديث جديد متاح للتحميل!</span>' : '<span style="color:#a7f3d0;">✅ أنت تستخدم أحدث إصدار بالفعل.</span>'}</div>
    <pre style="margin-top:8px; background:#0f172a; padding:10px; border-radius:8px; white-space:pre-wrap; font-size:0.82rem;">${res.release_notes || 'No release notes.'}</pre>
  `;
}

async function applyGitHubUpdate() {
  const repo = document.getElementById('githubRepoInput').value.trim() || 'yoyo-ui/direct-stream-ps5';
  const box = document.getElementById('updateStatusBox');
  box.innerHTML = '⬇️ جاري تحميل أحدث نسخة من GitHub وتثبيتها مع أخذ نسخة احتياطية...';
  const res = await api('/api/update/apply', 'POST', { repo });
  if (!res.ok) {
    box.innerHTML = `<span style="color:var(--danger);">❌ فشل التحديث التلقائي: ${res.error}</span>`;
    return;
  }
  box.innerHTML = `<span style="color:var(--success);">✅ تم تحديث ${res.updated_files_count} ملف بنجاح! النسخة الاحتياطية محفوظة في: <code class="ltr">${res.backup_dir}</code></span>`;
}

loadConsoles();
setInterval(refreshJobs, 1500);
</script>
</body>
</html>
"""


class DirectStreamHttpServer:
    """Localhost Web Studio & JSON API Server for PC Edition."""

    def __init__(
        self,
        console_manager: MultiConsoleManager,
        broadcast_engine: MultiConsoleBroadcastEngine,
        updater: GitHubAutoUpdater,
        host: str = "127.0.0.1",
        port: int = 8765,
    ) -> None:
        self.console_manager = console_manager
        self.broadcast_engine = broadcast_engine
        self.updater = updater
        self.host = host
        self.port = port
        self._httpd: Optional[http.server.ThreadingHTTPServer] = None

    def make_handler(self) -> Any:
        outer = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, format: str, *args: Any) -> None:
                pass

            def _send_json(self, payload: Dict[str, Any], status: int = 200) -> None:
                raw = json.dumps(payload, ensure_ascii=False).encode("utf-8")
                self.send_response(status)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Content-Length", str(len(raw)))
                self.end_headers()
                self.wfile.write(raw)

            def _read_json(self) -> Dict[str, Any]:
                length = int(self.headers.get("Content-Length", "0") or "0")
                if length <= 0:
                    return {}
                return json.loads(self.rfile.read(length).decode("utf-8"))

            def do_GET(self) -> None:
                if self.path in ("/", "/index.html"):
                    raw = DASHBOARD_HTML.encode("utf-8")
                    self.send_response(200)
                    self.send_header("Content-Type", "text/html; charset=utf-8")
                    self.send_header("Content-Length", str(len(raw)))
                    self.end_headers()
                    self.wfile.write(raw)
                elif self.path == "/api/consoles":
                    self._send_json({
                        "ok": True,
                        "consoles": [c.to_dict() for c in outer.console_manager.list_consoles()],
                    })
                elif self.path == "/api/jobs":
                    self._send_json({
                        "ok": True,
                        "jobs": outer.broadcast_engine.list_jobs(),
                    })
                else:
                    self._send_json({"ok": False, "error": "Not found"}, status=404)

            def do_POST(self) -> None:
                try:
                    body = self._read_json()
                    if self.path == "/api/detect-format":
                        uri = body.get("source_uri", "")
                        force_ftp = bool(body.get("force_general_ftp", False))
                        custom_path = body.get("custom_ftp_path")
                        if not uri.lower().startswith(("http://", "https://")) and pathlib.Path(uri).exists():
                            det = SmartFormatDetector.inspect_local_path(uri)
                            if force_ftp:
                                det = SmartFormatDetector.detect(uri, force_general_ftp=True, custom_ftp_path=custom_path)
                        else:
                            det = SmartFormatDetector.detect(uri, force_general_ftp=force_ftp, custom_ftp_path=custom_path)
                        self._send_json({"ok": True, "detection": det.to_dict()})

                    elif self.path == "/api/consoles":
                        prof = ConsoleProfile.from_dict(body)
                        outer.console_manager.add_or_update_console(prof)
                        self._send_json({"ok": True, "console": prof.to_dict()})

                    elif self.path == "/api/consoles/toggle":
                        cid = body.get("console_id", "")
                        c = outer.console_manager.get_console(cid)
                        if c:
                            c.enabled = bool(body.get("enabled", True))
                            outer.console_manager.save()
                        self._send_json({"ok": True})

                    elif self.path == "/api/consoles/delete":
                        outer.console_manager.remove_console(body.get("console_id", ""))
                        self._send_json({"ok": True})

                    elif self.path == "/api/consoles/probe":
                        res = outer.console_manager.probe_console(body.get("console_id", ""))
                        self._send_json(res)

                    elif self.path == "/api/consoles/probe-all":
                        res = outer.console_manager.probe_all()
                        self._send_json({"ok": True, "results": res})

                    elif self.path == "/api/consoles/scan-lan":
                        found = outer.console_manager.scan_lan_for_ps5(
                            subnet_prefix=body.get("subnet_prefix"),
                            ftp_port=int(body.get("ftp_port", 2121)),
                            auto_add=True,
                        )
                        self._send_json({"ok": True, "discovered": found})

                    elif self.path == "/api/jobs/start":
                        job = outer.broadcast_engine.create_and_start_job(
                            source_uri=body.get("source_uri", ""),
                            target_console_ids=body.get("target_console_ids"),
                            custom_destination_dir=body.get("custom_destination_dir"),
                            force_general_ftp=bool(body.get("force_general_ftp", False)),
                            overwrite=bool(body.get("overwrite", True)),
                            run_async=True,
                        )
                        self._send_json({"ok": True, "job": job.to_dict()})

                    elif self.path == "/api/jobs/cancel":
                        outer.broadcast_engine.cancel_job(body.get("job_id", ""))
                        self._send_json({"ok": True})

                    elif self.path == "/api/ftp/list":
                        c = outer.console_manager.get_console(body.get("console_id", ""))
                        if not c:
                            self._send_json({"ok": False, "error": "Console not found"})
                            return
                        entries = GeneralFtpManager.list_directory(c, body.get("remote_path", "/"))
                        self._send_json({"ok": True, "entries": entries})

                    elif self.path == "/api/ftp/mkdir":
                        c = outer.console_manager.get_console(body.get("console_id", ""))
                        if not c:
                            self._send_json({"ok": False, "error": "Console not found"})
                            return
                        res = GeneralFtpManager.mkdir(c, body.get("remote_path", "/data/new_folder"))
                        self._send_json(res)

                    elif self.path == "/api/ftp/delete":
                        c = outer.console_manager.get_console(body.get("console_id", ""))
                        if not c:
                            self._send_json({"ok": False, "error": "Console not found"})
                            return
                        res = GeneralFtpManager.delete_item(
                            c,
                            body.get("remote_path", ""),
                            bool(body.get("is_dir", False)),
                        )
                        self._send_json(res)

                    elif self.path == "/api/update/check":
                        repo = body.get("repo") or outer.updater.repo
                        outer.updater.repo = repo
                        self._send_json(outer.updater.check_for_updates())

                    elif self.path == "/api/update/apply":
                        repo = body.get("repo") or outer.updater.repo
                        outer.updater.repo = repo
                        self._send_json(outer.updater.download_and_apply_update())

                    else:
                        self._send_json({"ok": False, "error": "Unknown endpoint"}, status=404)
                except Exception as exc:
                    self._send_json({"ok": False, "error": str(exc)}, status=500)

        return Handler

    def start_background(self) -> str:
        self._httpd = http.server.ThreadingHTTPServer((self.host, self.port), self.make_handler())
        actual_port = self._httpd.server_address[1]
        self.port = actual_port
        t = threading.Thread(target=self._httpd.serve_forever, daemon=True)
        t.start()
        return f"http://{self.host}:{actual_port}"

    def stop(self) -> None:
        if self._httpd:
            self._httpd.shutdown()
            self._httpd.server_close()


# ============================================================================
# 7. NATIVE WINDOWS / PC DESKTOP GUI (TKINTER)
# ============================================================================

def launch_native_pc_gui(dashboard_url: str, console_mgr: MultiConsoleManager, engine: MultiConsoleBroadcastEngine, updater: GitHubAutoUpdater) -> None:
    """Launches a native Desktop GUI window on Windows/macOS/Linux alongside the Web Server."""
    import tkinter as tk
    from tkinter import filedialog, messagebox, ttk

    root = tk.Tk()
    root.title(f"{APP_NAME} v{APP_VERSION}")
    root.geometry("920x640")
    root.configure(bg="#0b0f19")

    header = tk.Frame(root, bg="#131b2e", padx=16, pady=12)
    header.pack(fill="x")
    tk.Label(
        header,
        text=f"🎮 Direct Stream PS5 — PC Desktop Edition v{APP_VERSION}",
        fg="#ffffff",
        bg="#131b2e",
        font=("Segoe UI", 14, "bold"),
    ).pack(side="left")

    tk.Button(
        header,
        text="🌐 Open Full Web Dashboard",
        bg="#3b82f6",
        fg="#ffffff",
        relief="flat",
        padx=12,
        pady=6,
        command=lambda: webbrowser.open(dashboard_url),
    ).pack(side="right", padx=4)

    body = tk.Frame(root, bg="#0b0f19", padx=16, pady=12)
    body.pack(fill="both", expand=True)

    # Source & Smart Detection section
    tk.Label(body, text="Source Download URL or Local File / Folder Path:", fg="#94a3b8", bg="#0b0f19").pack(anchor="w")
    src_frame = tk.Frame(body, bg="#0b0f19")
    src_frame.pack(fill="x", pady=4)
    src_var = tk.StringVar()
    src_entry = tk.Entry(src_frame, textvariable=src_var, bg="#131b2e", fg="#ffffff", insertbackground="#ffffff", font=("Consolas", 10))
    src_entry.pack(side="left", fill="x", expand=True, ipady=6)

    dest_var = tk.StringVar(value="/data/pkg")
    fmt_label_var = tk.StringVar(value="Smart Format Detector: Ready")

    def on_detect(*_args: Any) -> None:
        val = src_var.get().strip()
        if not val:
            return
        det = SmartFormatDetector.inspect_local_path(val) if pathlib.Path(val).exists() else SmartFormatDetector.detect(val)
        dest_var.set(det.recommended_path)
        fmt_label_var.set(f"Detected: {det.format_label_en}  |  Auto Path: {det.recommended_path}  |  Strategy: {det.extraction_strategy}")

    def pick_file() -> None:
        p = filedialog.askopenfilename()
        if p:
            src_var.set(p)
            on_detect()

    def pick_folder() -> None:
        p = filedialog.askdirectory()
        if p:
            src_var.set(p)
            on_detect()

    tk.Button(src_frame, text="📄 File...", bg="#334155", fg="#fff", relief="flat", command=pick_file).pack(side="left", padx=4)
    tk.Button(src_frame, text="📁 Folder...", bg="#334155", fg="#fff", relief="flat", command=pick_folder).pack(side="left", padx=4)
    tk.Button(src_frame, text="🔍 Detect Format", bg="#06b6d4", fg="#fff", relief="flat", command=on_detect).pack(side="left", padx=4)

    tk.Label(body, textvariable=fmt_label_var, fg="#38bdf8", bg="#0b0f19", font=("Segoe UI", 9, "bold")).pack(anchor="w", pady=4)

    tk.Label(body, text="Target PS5 Installation / FTP Path (auto-filled or custom):", fg="#94a3b8", bg="#0b0f19").pack(anchor="w")
    tk.Entry(body, textvariable=dest_var, bg="#131b2e", fg="#ffffff", insertbackground="#ffffff", font=("Consolas", 10)).pack(fill="x", pady=4, ipady=5)

    general_ftp_var = tk.BooleanVar(value=False)
    tk.Checkbutton(
        body,
        text="General FTP Transfer Mode (نقل أي ملف أو فولدر عادي عبر الـ FTP بدون التقيد بالألعاب)",
        variable=general_ftp_var,
        bg="#0b0f19",
        fg="#e2e8f0",
        selectcolor="#131b2e",
    ).pack(anchor="w", pady=4)

    def start_transfer() -> None:
        uri = src_var.get().strip()
        if not uri:
            messagebox.showwarning("Missing Source", "Please enter a URL or choose a local file/folder.")
            return
        try:
            job = engine.create_and_start_job(
                source_uri=uri,
                custom_destination_dir=dest_var.get().strip(),
                force_general_ftp=general_ftp_var.get(),
                run_async=True,
            )
            messagebox.showinfo(
                "Broadcast Started",
                f"Started simultaneous transfer '{job.filename}' to {len(job.targets)} PS5 console(s)!\nDestination: {job.destination_dir}",
            )
        except Exception as exc:
            messagebox.showerror("Error", str(exc))

    action_row = tk.Frame(body, bg="#0b0f19")
    action_row.pack(fill="x", pady=8)
    tk.Button(
        action_row,
        text="⚡ Start Simultaneous Transfer to All Enabled PS5 Consoles",
        bg="#10b981",
        fg="#fff",
        font=("Segoe UI", 10, "bold"),
        relief="flat",
        padx=14,
        pady=8,
        command=start_transfer,
    ).pack(side="left")

    def check_updates_gui() -> None:
        res = updater.check_for_updates()
        if not res.get("ok"):
            messagebox.showwarning("GitHub Auto-Update", f"Could not reach GitHub API: {res.get('error')}")
            return
        msg = (
            f"Repository: {res.get('repo')}\n"
            f"Current Version: v{res.get('current_version')}\n"
            f"Latest on GitHub: {res.get('latest_version')}\n\n"
            f"Update Available: {'YES' if res.get('update_available') else 'Up to date'}\n\n"
            f"Would you like to download and apply the latest files from GitHub now?"
        )
        if messagebox.askyesno("GitHub Auto-Update", msg):
            up_res = updater.download_and_apply_update()
            if up_res.get("ok"):
                messagebox.showinfo("Update Complete", f"Updated {up_res.get('updated_files_count')} files!\nBackup: {up_res.get('backup_dir')}")
            else:
                messagebox.showerror("Update Failed", str(up_res.get("error")))

    tk.Button(
        action_row,
        text="🔄 GitHub Auto-Update",
        bg="#3b82f6",
        fg="#fff",
        relief="flat",
        padx=12,
        pady=8,
        command=check_updates_gui,
    ).pack(side="right")

    status_box = tk.Text(body, height=14, bg="#131b2e", fg="#cbd5e1", font=("Consolas", 9))
    status_box.pack(fill="both", expand=True, pady=8)

    def poll_status() -> None:
        consoles = console_mgr.list_consoles()
        jobs = engine.list_jobs()
        lines = [
            f"=== Connected PS5 Consoles ({len(consoles)}) | Web UI: {dashboard_url} ===",
        ]
        for c in consoles:
            lines.append(f"  [{'ENABLED' if c.enabled else 'DISABLED'}] {c.name} -> {c.host}:{c.ftp_port} ({c.preferred_storage}) [{c.last_status}]")
        lines.append("\n=== Active & Recent Broadcast Jobs ===")
        for j in jobs[:8]:
            lines.append(f"Job {j['job_id']}: {j['filename']} [{j['status']}] -> {j['destination_dir']}")
            for t in j["targets"].values():
                lines.append(f"   -> {t['console_name']} ({t['host']}): {t['status']} | {t['progress_pct']}% ({t['speed_mbps']} MB/s)")
        status_box.delete("1.0", "end")
        status_box.insert("1.0", "\n".join(lines))
        root.after(1500, poll_status)

    poll_status()
    root.mainloop()


# ============================================================================
# 8. CLI ENTRYPOINT
# ============================================================================

def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=APP_NAME)
    parser.add_argument("--host", default="127.0.0.1", help="Web dashboard bind address (default: 127.0.0.1, use 0.0.0.0 for LAN)")
    parser.add_argument("--port", type=int, default=8765, help="Web dashboard port (default: 8765)")
    parser.add_argument("--gui", action="store_true", help="Launch Native Desktop GUI window (Tkinter) alongside Web Server")
    parser.add_argument("--no-browser", action="store_true", help="Do not auto-open browser on startup")
    parser.add_argument("--check-update", action="store_true", help="Check GitHub for updates and exit")
    parser.add_argument("--self-update", action="store_true", help="Download and apply latest update from GitHub and exit")
    parser.add_argument("--detect", metavar="URI_OR_PATH", help="Detect game/file format and print recommended PS5 installation path")
    args = parser.parse_args(argv)

    updater = GitHubAutoUpdater()
    if args.check_update:
        print(json.dumps(updater.check_for_updates(), indent=2, ensure_ascii=False))
        return 0
    if args.self_update:
        print(json.dumps(updater.download_and_apply_update(), indent=2, ensure_ascii=False))
        return 0
    if args.detect:
        res = SmartFormatDetector.inspect_local_path(args.detect) if pathlib.Path(args.detect).exists() else SmartFormatDetector.detect(args.detect)
        print(json.dumps(res.to_dict(), indent=2, ensure_ascii=False))
        return 0

    console_mgr = MultiConsoleManager()
    engine = MultiConsoleBroadcastEngine(console_mgr)
    server = DirectStreamHttpServer(console_mgr, engine, updater, host=args.host, port=args.port)
    url = server.start_background()
    print(f"[{APP_NAME} v{APP_VERSION}]")
    print(f" -> Web Studio Dashboard running at: {url}")

    if args.gui or (IS_FROZEN_EXE and not args.no_browser):
        launch_native_pc_gui(url, console_mgr, engine, updater)
        return 0

    if not args.no_browser:
        with contextlib.suppress(Exception):
            webbrowser.open(url)

    try:
        while True:
            time.sleep(1.0)
    except KeyboardInterrupt:
        print("\nShutting down Direct Stream PS5...")
        server.stop()
    return 0


if __name__ == "__main__":
    sys.exit(main())