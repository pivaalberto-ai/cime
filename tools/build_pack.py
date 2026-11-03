#!/usr/bin/env python3
"""
Genera un pacchetto regione .pkpack per l'app Cime.

Sorgenti:
  - Terreno: Copernicus DEM GLO-30 (risoluzione 1"), tile pubblici su AWS Open Data, ricampionati
    a una griglia più rada (default 3" ≈ 90 m) per contenere le dimensioni.
  - Cime: nodi OpenStreetMap natural=peak con nome, via Overpass API.

Esempio (Lombardia e dintorni):
  python build_pack.py --bbox 45.4,8.5,46.7,11.0 --name "Lombardia" --out lombardia.pkpack

Licenze dei dati da citare nell'app: © OpenStreetMap contributors (ODbL);
Copernicus DEM © DLR e.V. 2010-2014 e © Airbus Defence and Space GmbH 2014-2018,
fornito sotto programma Copernicus dell'Unione Europea.
"""
from __future__ import annotations

import argparse
import json
import math
import re
import struct
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

import numpy as np

DEM_URL = "https://copernicus-dem-30m.s3.amazonaws.com/{n}/{n}.tif"
OVERPASS_URL = "https://overpass-api.de/api/interpreter"
USER_AGENT = "Cime-pack-builder/0.1"
TILE = 3600          # pixel per grado nei tile Copernicus (sotto i 50° di latitudine)
NODATA = -32768
HEADER_SIZE = 128
MAGIC = b"PKPK"
VERSION = 1


# ---------------------------------------------------------------- DEM

def tile_name(lat: int, lon: int) -> str:
    ns = "N" if lat >= 0 else "S"
    ew = "E" if lon >= 0 else "W"
    return f"Copernicus_DSM_COG_10_{ns}{abs(lat):02d}_00_{ew}{abs(lon):03d}_00_DEM"


def fetch_tile(lat: int, lon: int, cache: Path) -> Path | None:
    """Scarica (o riusa dalla cache) un tile. None = tile inesistente (mare)."""
    name = tile_name(lat, lon)
    path = cache / f"{name}.tif"
    missing = cache / f"{name}.missing"
    if path.exists():
        return path
    if missing.exists():
        return None
    url = DEM_URL.format(n=name)
    print(f"  scarico {name} …", flush=True)
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            data = r.read()
    except urllib.error.HTTPError as e:
        if e.code in (403, 404):  # il bucket risponde così per i tile che non esistono
            missing.touch()
            return None
        raise
    tmp = path.with_suffix(".part")
    tmp.write_bytes(data)
    tmp.rename(path)
    return path


def read_tile(path: Path) -> np.ndarray:
    import tifffile
    a = tifffile.imread(path).astype(np.float32)
    if a.ndim != 2:
        raise ValueError(f"{path}: forma inattesa {a.shape}")
    a[(a < -1000) | ~np.isfinite(a)] = np.nan
    rows, cols = a.shape
    # Sopra i 50° i tile hanno meno colonne: le riportiamo a 3600 per avere celle quadrate in gradi.
    if rows != TILE:
        a = a[(np.arange(TILE) * rows // TILE), :]
    if cols != TILE:
        a = a[:, (np.arange(TILE) * cols // TILE)]
    return a


def block_mean(a: np.ndarray, k: int) -> np.ndarray:
    if k == 1:
        return a
    n = a.shape[0] // k
    with np.errstate(invalid="ignore"), _quiet_nanmean():
        return np.nanmean(a[: n * k, : n * k].reshape(n, k, n, k), axis=(1, 3))


class _quiet_nanmean:
    def __enter__(self):
        import warnings
        self._w = warnings.catch_warnings()
        self._w.__enter__()
        warnings.simplefilter("ignore", RuntimeWarning)

    def __exit__(self, *exc):
        self._w.__exit__(*exc)


def build_dem(s: float, w: float, n: float, e: float, step: int, cache: Path, tile_dir: Path | None):
    """
    Restituisce (griglia int16 nord→sud, lat centro prima riga, lon centro prima colonna, passo in gradi).
    Convenzione Copernicus: la riga 0 di un tile ha centro alla latitudine intera superiore,
    la colonna 0 alla longitudine intera occidentale.
    """
    if TILE % step:
        raise SystemExit("--step deve dividere 3600 (1, 2, 3, 4, 5, 6, …)")
    lat0, lat1 = math.floor(s), math.ceil(n) - 1
    lon0, lon1 = math.floor(w), math.ceil(e) - 1
    per = TILE // step
    nlat, nlon = lat1 - lat0 + 1, lon1 - lon0 + 1
    grid = np.full((nlat * per, nlon * per), np.nan, dtype=np.float32)
    for lat in range(lat1, lat0 - 1, -1):
        for lon in range(lon0, lon1 + 1):
            if tile_dir is not None:
                p = tile_dir / f"{tile_name(lat, lon)}.tif"
                p = p if p.exists() else None
            else:
                p = fetch_tile(lat, lon, cache)
            if p is None:
                continue  # mare: resterà NaN e diventerà 0
            r = (lat1 - lat) * per
            c = (lon - lon0) * per
            grid[r:r + per, c:c + per] = block_mean(read_tile(p), step)

    cell = step / TILE
    # Centro della prima cella media: media dei centri dei pixel da 1" che contiene.
    north = lat1 + 1 - (step - 1) / 2 / TILE
    west = lon0 + (step - 1) / 2 / TILE

    # Ritaglio al riquadro richiesto.
    r0 = max(0, int(math.floor((north - n) / cell)))
    r1 = min(grid.shape[0] - 1, int(math.ceil((north - s) / cell)))
    c0 = max(0, int(math.floor((w - west) / cell)))
    c1 = min(grid.shape[1] - 1, int(math.ceil((e - west) / cell)))
    grid = grid[r0:r1 + 1, c0:c1 + 1]
    north -= r0 * cell
    west += c0 * cell

    # I tile mancanti sono mare aperto: quota 0. Eventuali buchi isolati restano nodata.
    out = np.where(np.isnan(grid), 0, np.clip(np.round(grid), -500, 9000)).astype("<i2")
    return out, north, west, cell


def dem_sample(grid: np.ndarray, north: float, west: float, cell: float, lat: float, lon: float) -> float:
    r = (north - lat) / cell
    c = (lon - west) / cell
    if r < 0 or c < 0 or r > grid.shape[0] - 1 or c > grid.shape[1] - 1:
        return float("nan")
    r0 = min(int(r), grid.shape[0] - 2)
    c0 = min(int(c), grid.shape[1] - 2)
    tr, tc = r - r0, c - c0
    a, b = float(grid[r0, c0]), float(grid[r0, c0 + 1])
    cc, d = float(grid[r0 + 1, c0]), float(grid[r0 + 1, c0 + 1])
    return (a + (b - a) * tc) * (1 - tr) + (cc + (d - cc) * tc) * tr


# ---------------------------------------------------------------- Cime

def fetch_peaks(s: float, w: float, n: float, e: float) -> list[dict]:
    query = (
        "[out:json][timeout:300];"
        f'(node["natural"="peak"]["name"]({s},{w},{n},{e});'
        f'node["natural"="volcano"]["name"]({s},{w},{n},{e}););'
        "out body;"
    )
    data = urllib.parse.urlencode({"data": query}).encode()
    for attempt in range(4):
        req = urllib.request.Request(OVERPASS_URL, data=data, headers={"User-Agent": USER_AGENT})
        try:
            with urllib.request.urlopen(req, timeout=360) as r:
                return json.load(r)["elements"]
        except urllib.error.HTTPError as ex:
            if ex.code in (429, 504) and attempt < 3:
                wait = 30 * (attempt + 1)
                print(f"  Overpass occupato ({ex.code}), riprovo tra {wait} s …", flush=True)
                time.sleep(wait)
                continue
            raise
    return []


_num = re.compile(r"-?\d+(?:[.,]\d+)?")


def parse_ele(v: str | None) -> float | None:
    if not v:
        return None
    v = v.strip().lower()
    m = _num.search(v.replace(" ", "").replace(" ", ""))
    if not m:
        return None
    txt = m.group(0)
    # "3.476" o "3,476" con tre decimali e senza unità sono quasi sempre migliaia, non decimali.
    if re.fullmatch(r"\d{1,2}[.,]\d{3}", txt):
        txt = txt.replace(".", "").replace(",", "")
    x = float(txt.replace(",", "."))
    if "ft" in v or "'" in v:
        x *= 0.3048
    return x if -500 < x < 9000 else None


def build_peaks(elements, lang, grid, north, west, cell):
    peaks = []
    for el in elements:
        if el.get("type") != "node":
            continue
        tags = el.get("tags", {})
        name = (lang and tags.get(f"name:{lang}")) or tags.get("name")
        if not name:
            continue
        lat, lon = el["lat"], el["lon"]
        ele = parse_ele(tags.get("ele"))
        ground = dem_sample(grid, north, west, cell, lat, lon)
        if math.isnan(ground):
            continue
        # Quota OSM sospetta (errore di battitura) se si discosta troppo dal terreno: usiamo il DEM.
        if ele is None or abs(ele - ground) > 300:
            ele = ground
        peaks.append((lat, lon, ele, int(el["id"]), name.strip()))
    peaks.sort(key=lambda p: -p[2])
    return peaks


# ---------------------------------------------------------------- Scrittura

def truncate_utf8(s: str, limit: int) -> bytes:
    b = s.encode("utf-8")
    while len(b) > limit:
        s = s[:-1]
        b = s.encode("utf-8")
    return b


def write_pack(path: Path, name: str, grid: np.ndarray, north: float, west: float, cell: float, peaks):
    rows, cols = grid.shape
    name_b = truncate_utf8(name, HEADER_SIZE - 66)
    dem_off = HEADER_SIZE
    peaks_off = dem_off + rows * cols * 2
    header = bytearray(HEADER_SIZE)
    struct.pack_into("<4si3d4i2qH", header, 0, MAGIC, VERSION, north, west, cell,
                     rows, cols, len(peaks), NODATA, dem_off, peaks_off, len(name_b))
    header[66:66 + len(name_b)] = name_b
    with open(path, "wb") as f:
        f.write(header)
        f.write(np.ascontiguousarray(grid, dtype="<i2").tobytes())
        for lat, lon, ele, pid, pname in peaks:
            nb = truncate_utf8(pname, 200)
            f.write(struct.pack("<2dfqH", lat, lon, ele, pid, len(nb)))
            f.write(nb)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--bbox", required=True, help="sud,ovest,nord,est in gradi decimali")
    ap.add_argument("--name", required=True, help="nome della regione mostrato nell'app")
    ap.add_argument("--out", required=True, type=Path)
    ap.add_argument("--step", type=int, default=3,
                    help="passo DEM in secondi d'arco (1≈30 m, 3≈90 m, 6≈180 m). Default 3")
    ap.add_argument("--margin-km", type=float, default=60,
                    help="terreno extra attorno al riquadro, per vedere le cime oltre il confine. Default 60")
    ap.add_argument("--lang", default="it", help="preferisci name:<lang> se presente (vuoto per disattivare)")
    ap.add_argument("--cache", type=Path, default=Path("dem_cache"))
    ap.add_argument("--tile-dir", type=Path, help="usa tile .tif già scaricati invece di scaricarli")
    ap.add_argument("--peaks-json", type=Path, help="usa una risposta Overpass salvata invece di interrogarla")
    a = ap.parse_args(argv)

    s, w, n, e = (float(x) for x in a.bbox.split(","))
    if not (s < n and w < e):
        raise SystemExit("bbox non valido: serve sud < nord e ovest < est")
    if max(abs(s), abs(n)) >= 50:
        print("Attenzione: oltre i 50° di latitudine i tile Copernicus vengono ricampionati in larghezza.")
    dlat = a.margin_km / 111.2
    dlon = a.margin_km / (111.2 * math.cos(math.radians((s + n) / 2)))
    S, W, N, E = s - dlat, w - dlon, n + dlat, e + dlon
    a.cache.mkdir(parents=True, exist_ok=True)

    print(f"Terreno {S:.2f},{W:.2f} → {N:.2f},{E:.2f}, passo {a.step}\"")
    grid, north, west, cell = build_dem(S, W, N, E, a.step, a.cache, a.tile_dir)
    print(f"  griglia {grid.shape[0]}×{grid.shape[1]}, {grid.nbytes / 1e6:.1f} MB, "
          f"quota max {int(grid.max())} m")

    print("Cime da OpenStreetMap …")
    if a.peaks_json:
        elements = json.loads(a.peaks_json.read_text())["elements"]
    else:
        elements = fetch_peaks(S, W, N, E)
    peaks = build_peaks(elements, a.lang or None, grid, north, west, cell)
    print(f"  {len(peaks)} cime con nome")

    write_pack(a.out, a.name, grid, north, west, cell, peaks)
    print(f"Fatto: {a.out} ({a.out.stat().st_size / 1e6:.1f} MB)")


if __name__ == "__main__":
    sys.exit(main())
