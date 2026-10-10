#!/usr/bin/env python3
"""
Genera app/src/main/assets/rail_graph.bin: il grafo della rete ferroviaria italiana
estratto da OpenStreetMap, usato dall'app per disegnare linee e percorsi sui binari reali (offline).

Uso:
    pip install osmium
    curl -LO https://download.geofabrik.de/europe/italy-latest.osm.pbf
    python tools/build_rail_graph.py italy-latest.osm.pbf app/src/main/assets/rail_graph.bin

Dati © OpenStreetMap contributors, licenza ODbL 1.0 (https://www.openstreetmap.org/copyright).

Formato (gzip, interi big-endian come java.io.DataInputStream):
    "TLRG" int versione
    int N  ; N x (int lat_e6, int lon_e6)                    nodi del grafo (incroci, estremi, stazioni)
    int E  ; E x (int a, int b, int lunghezza_m, byte flag,  archi; flag bit0 = alta velocità, bit1 = binario di servizio
                  int k, k x (int dlat_e6, int dlon_e6))     punti intermedi, delta dal precedente
    int S  ; S x (UTF codice_stazione, int nodo)             aggancio stazioni ViaggiaTreno -> nodo
"""
import gzip
import json
import math
import struct
import sys
import time
import urllib.request
from collections import defaultdict

import osmium

VT = "http://www.viaggiatreno.it/infomobilita/resteasy/viaggiatreno/"
UA = {"User-Agent": "Mozilla/5.0 (Linux; Android 14) Chrome/130.0 Mobile Safari/537.36"}

SERVICE_TRACKS = {"yard", "siding", "spur"}
EXCLUDED_USAGE = {"industrial", "military", "test"}
SNAP_MAX_M = 1500
BRIDGE_M = 200
MIN_COMPONENT = 300
SIMPLIFY_M = 6.0


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


def dist_m(lat1, lon1, lat2, lon2):
    r = math.pi / 180
    a = math.sin((lat2 - lat1) * r / 2) ** 2 + math.cos(lat1 * r) * math.cos(lat2 * r) * math.sin((lon2 - lon1) * r / 2) ** 2
    return 2 * 6371000 * math.asin(math.sqrt(a))


def is_highspeed(tags):
    if tags.get("highspeed") == "yes":
        return True
    try:
        return int(tags.get("maxspeed", "0").split()[0]) >= 250
    except ValueError:
        return False


def fetch_stations():
    stations = {}
    for r in range(1, 23):
        req = urllib.request.Request(VT + f"elencoStazioni/{r}", headers=UA)
        for s in json.loads(urllib.request.urlopen(req, timeout=60).read().decode("utf-8")):
            if s.get("lat"):
                stations[s["codiceStazione"]] = (s["lat"], s["lon"])
    return stations


def simplify(points, tol_m):
    """Douglas-Peucker su coordinate (lat, lon), tolleranza in metri (proiezione equirettangolare locale)."""
    if len(points) <= 2:
        return points
    lat0 = math.radians(points[0][0])
    kx = 111320 * math.cos(lat0)
    ky = 110540
    xy = [(p[1] * kx, p[0] * ky) for p in points]
    keep = [False] * len(points)
    keep[0] = keep[-1] = True
    stack = [(0, len(points) - 1)]
    while stack:
        i, j = stack.pop()
        (x1, y1), (x2, y2) = xy[i], xy[j]
        dx, dy = x2 - x1, y2 - y1
        norm = math.hypot(dx, dy) or 1e-9
        best, idx = 0.0, -1
        for k in range(i + 1, j):
            x, y = xy[k]
            d = abs(dy * (x - x1) - dx * (y - y1)) / norm
            if d > best:
                best, idx = d, k
        if best > tol_m and idx > 0:
            keep[idx] = True
            stack.append((i, idx))
            stack.append((idx, j))
    return [p for p, k in zip(points, keep) if k]


def main(pbf, out):
    log("Lettura delle vie ferroviarie…")
    ways = []  # (node_ids, highspeed)
    fp = osmium.FileProcessor(pbf, osmium.osm.WAY).with_filter(osmium.filter.KeyFilter("railway"))
    for w in fp:
        t = w.tags
        if t.get("railway") not in ("rail", "narrow_gauge"):
            continue
        if t.get("usage") in EXCLUDED_USAGE:
            continue
        ids = [n.ref for n in w.nodes]
        if len(ids) >= 2:
            # flag: bit0 alta velocità, bit1 binario di servizio (scalo, tronchino, raccordo):
            # incluso perché nelle stazioni collega le linee, ma penalizzato nel calcolo
            flag = (1 if is_highspeed(t) else 0) | (2 if t.get("service") in SERVICE_TRACKS else 0)
            ways.append((ids, flag))
    needed = {i for ids, _ in ways for i in ids}
    log(f"{len(ways)} vie, {len(needed)} nodi da localizzare")

    coords = {}
    fp = osmium.FileProcessor(pbf, osmium.osm.NODE).with_filter(osmium.filter.IdFilter(needed))
    for n in fp:
        coords[n.id] = (n.location.lat, n.location.lon)
    log(f"{len(coords)} nodi localizzati")

    ways = [([i for i in ids if i in coords], hs) for ids, hs in ways]
    ways = [(ids, hs) for ids, hs in ways if len(ids) >= 2]

    # Componenti connesse (union-find sui nodi OSM)
    parent = {}

    def find(x):
        parent.setdefault(x, x)
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    def union(a, b):
        ra, rb = find(a), find(b)
        if ra != rb:
            parent[ra] = rb

    degree = defaultdict(int)
    for ids, _ in ways:
        for a, b in zip(ids, ids[1:]):
            union(a, b)
            degree[a] += 1
            degree[b] += 1

    # Ricucitura delle piccole interruzioni della mappatura: un estremo libero (grado 1)
    # viene collegato al nodo più vicino di un'altra componente entro BRIDGE_M metri
    raw_grid = defaultdict(list)
    for nid in degree:
        lat, lon = coords[nid]
        raw_grid[(int(lat * 500), int(lon * 500))].append(nid)
    bridges = 0
    for d in [n for n, k in degree.items() if k == 1]:
        lat, lon = coords[d]
        gx, gy = int(lat * 500), int(lon * 500)
        best, best_d = None, BRIDGE_M
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                for nid in raw_grid.get((gx + dx, gy + dy), ()):
                    if find(nid) == find(d):
                        continue
                    dd = dist_m(lat, lon, *coords[nid])
                    if dd < best_d:
                        best, best_d = nid, dd
        if best is not None:
            ways.append(([d, best], 0))
            union(d, best)
            bridges += 1
    comp_size = defaultdict(int)
    for nid in degree:
        comp_size[find(nid)] += 1
    log(f"{bridges} interruzioni ricucite; {len(comp_size)} componenti, la maggiore con {max(comp_size.values())} nodi")

    # Uso di ciascun nodo: >1 via o estremo di via => nodo del grafo
    use = defaultdict(int)
    for ids, _ in ways:
        for i in ids:
            use[i] += 1
        use[ids[0]] += 1
        use[ids[-1]] += 1

    # le stazioni si agganciano ai binari di linea, non a scali e tronchini
    main_nodes = {i for ids, flag in ways if not flag & 2 for i in ids}
    log("Aggancio stazioni…")
    stations = fetch_stations()
    grid = defaultdict(list)
    for nid, (lat, lon) in coords.items():
        grid[(int(lat * 50), int(lon * 50))].append(nid)
    station_node = {}
    for code, (lat, lon) in stations.items():
        gx, gy = int(lat * 50), int(lon * 50)
        # si preferiscono le reti connesse estese ai frammenti isolati
        best, best_d, best_small, best_small_d = None, SNAP_MAX_M, None, SNAP_MAX_M
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                for nid in grid.get((gx + dx, gy + dy), ()):
                    if nid not in degree or nid not in main_nodes:
                        continue
                    d = dist_m(lat, lon, *coords[nid])
                    if comp_size[find(nid)] >= MIN_COMPONENT:
                        if d < best_d:
                            best, best_d = nid, d
                    elif d < best_small_d:
                        best_small, best_small_d = nid, d
        best = best if best is not None else best_small
        if best is not None:
            station_node[code] = best
            use[best] += 2  # la stazione diventa nodo del grafo
    log(f"{len(station_node)}/{len(stations)} stazioni agganciate ai binari")

    # Archi: le vie spezzate nei nodi del grafo
    index = {}
    nodes = []

    def node_of(osm_id):
        if osm_id not in index:
            index[osm_id] = len(nodes)
            nodes.append(coords[osm_id])
        return index[osm_id]

    edges = []
    seen = set()
    for ids, hs in ways:
        start = 0
        for k in range(1, len(ids)):
            if use[ids[k]] > 1 or k == len(ids) - 1:
                seg = ids[start:k + 1]
                a, b = node_of(seg[0]), node_of(seg[-1])
                if a != b:
                    pts = [coords[i] for i in seg]
                    length = sum(dist_m(*pts[j], *pts[j + 1]) for j in range(len(pts) - 1))
                    key = (min(a, b), max(a, b), round(length))
                    if key not in seen:
                        seen.add(key)
                        edges.append((a, b, length, hs, simplify(pts, SIMPLIFY_M)[1:-1]))
                start = k
    log(f"Grafo: {len(nodes)} nodi, {len(edges)} archi, "
        f"{sum(len(e[4]) for e in edges)} punti intermedi, {sum(e[2] for e in edges) / 1000:.0f} km di binari")

    e6 = lambda v: int(round(v * 1e6))
    with gzip.open(out, "wb", compresslevel=9) as f:
        f.write(b"TLRG")
        f.write(struct.pack(">i", 1))
        f.write(struct.pack(">i", len(nodes)))
        for lat, lon in nodes:
            f.write(struct.pack(">ii", e6(lat), e6(lon)))
        f.write(struct.pack(">i", len(edges)))
        for a, b, length, hs, mid in edges:
            f.write(struct.pack(">iiibi", a, b, round(length), hs, len(mid)))
            plat, plon = e6(nodes[a][0]), e6(nodes[a][1])
            for lat, lon in mid:
                la, lo = e6(lat), e6(lon)
                f.write(struct.pack(">ii", la - plat, lo - plon))
                plat, plon = la, lo
        f.write(struct.pack(">i", len(station_node)))
        for code, nid in sorted(station_node.items()):
            b = code.encode("utf-8")
            f.write(struct.pack(">H", len(b)) + b)
            f.write(struct.pack(">i", node_of(nid)))
    log("Scritto", out)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
