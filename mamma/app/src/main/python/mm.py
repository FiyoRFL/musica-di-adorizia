"""Respaldo de YouTube para Musica de Mama: yt-dlp (el mismo de FIYODIGGER) da el link directo al audio."""
import json
import os
import sys
import threading
import urllib.request
import zipfile

_YT_URL = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp"
_ydl = None


def setup(zip_path):
    """Usa el yt-dlp mas nuevo bajado en una apertura anterior; si no hay, el que viene en la app."""
    global _ydl
    if os.path.exists(zip_path) and zipfile.is_zipfile(zip_path):
        sys.path.insert(0, zip_path)
    try:
        import yt_dlp
    except Exception:
        if zip_path in sys.path:
            sys.path.remove(zip_path)
        for m in [m for m in sys.modules if m == "yt_dlp" or m.startswith("yt_dlp.")]:
            del sys.modules[m]
        import yt_dlp
    _ydl = yt_dlp
    return _ydl.version.__version__


def update_bg(zip_path):
    """Baja la ultima version de yt-dlp para la proxima vez (YouTube cambia seguido)."""
    def work():
        try:
            tmp = zip_path + ".tmp"
            req = urllib.request.Request(_YT_URL, headers={"User-Agent": "MusicaDeMama"})
            with urllib.request.urlopen(req, timeout=60) as r, open(tmp, "wb") as f:
                while True:
                    b = r.read(1 << 16)
                    if not b:
                        break
                    f.write(b)
            if zipfile.is_zipfile(tmp):
                os.replace(tmp, zip_path)
            else:
                os.remove(tmp)
        except Exception:
            pass
    threading.Thread(target=work, daemon=True).start()
    return True


def resolve(vid):
    """Devuelve JSON {url, ua} con el link directo al audio del video."""
    last = ""
    for pc in (["visionos"], ["android_vr"], None):
        opts = {"format": "bestaudio[ext=m4a]/bestaudio/best", "quiet": True, "no_warnings": True,
                "noplaylist": True, "socket_timeout": 20, "skip_download": True}
        if pc:
            opts["extractor_args"] = {"youtube": {"player_client": pc}}
        try:
            with _ydl.YoutubeDL(opts) as y:
                info = y.extract_info("https://www.youtube.com/watch?v=" + vid, download=False)
            url = info.get("url")
            if not url and info.get("requested_formats"):
                url = info["requested_formats"][0].get("url")
            if url:
                h = info.get("http_headers") or {}
                return json.dumps({"url": url, "ua": h.get("User-Agent", "")})
            last = "sin link"
        except Exception as e:
            last = str(e).strip().splitlines()[-1] if str(e).strip() else repr(e)
    raise RuntimeError(last[:200] or "no se pudo")
