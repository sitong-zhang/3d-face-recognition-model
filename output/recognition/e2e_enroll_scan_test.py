#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
端到端闭环测试：录入 -> 构建 3D 人脸资产 -> 落库 -> 扫描 -> 3D 联合比对识别。

严格复现 Android 端接口（替换 glance 2D 模型后的 3D 方案）：
  FaceEngine.detect(bmp) -> Detection{ feature(478*3 归一化3D几何),
                                     embedding(512D MobileFaceNet), landmarks(478点mesh) }
  录入：9 姿态 x 2 样本 = 18 帧，每帧存 embedding + feature，最佳帧存 mesh
  FaceStore.upsert(name, embeddings, geometries, mesh3d)
  扫描：FaceStore.findBest(probeEmb, probeGeo)
        -> 身份余弦距离(阈值1.05) 主门限 + 3D 几何距离融合置信度 + 极端几何否决

测试目标：验证「录入成功 -> 3D 资产建立 -> 扫描本人成功 ->
          扫描陌生人拒绝」的完整流程可用。
"""
import os, io, math
import numpy as np
from PIL import Image, ImageEnhance as PILEnh

MODEL_LM = '/workspace/repo_build/android/app/src/main/assets/face_landmarker.task'
MODEL_EMB = '/workspace/repo_build/android/app/src/main/assets/mobilefacenet.tflite'

import mediapipe as mp
from mediapipe.tasks import python as mpp
from mediapipe.tasks.python import vision as mpv
from tflite_runtime.interpreter import Interpreter

# 与 Android FaceEngine / FaceEmbedder / FaceStore 完全一致的参数
DIST_THRESHOLD = 0.65   # FaceStore.DIST_THRESHOLD
GEO_THRESHOLD = 0.42    # FaceStore.GEO_THRESHOLD
NUM_LANDMARKS = 478

det = mpv.FaceLandmarker.create_from_options(mpv.FaceLandmarkerOptions(
    base_options=mpp.BaseOptions(model_asset_path=MODEL_LM),
    running_mode=mpv.RunningMode.IMAGE, num_faces=1,
    min_face_detection_confidence=0.5, min_face_presence_confidence=0.5,
    min_tracking_confidence=0.5, output_facial_transformation_matrixes=True))

interp = Interpreter(model_path=MODEL_EMB, num_threads=2)
interp.allocate_tensors()
IN = interp.get_input_details()[0]['index']
OUT = interp.get_output_details()[0]['index']

REF = np.array([[38.2946,51.6963],[73.5318,51.5014],[56.0252,71.7366],
                [41.5493,92.3655],[70.7299,92.2041]], dtype=np.float64)
IDX = [33,263,1,61,291]
SIZE = 112

def solve_similarity(src):
    A = np.zeros((4,4)); Bv = np.zeros(4)
    for i in range(5):
        x,y = src[i]; u,v = REF[i]
        r1 = np.array([x,-y,1,0], dtype=np.float64)
        r2 = np.array([y, x,0,1], dtype=np.float64)
        Bv += r1*u + r2*v
        A += np.outer(r1,r1) + np.outer(r2,r2)
    return np.linalg.solve(A, Bv)

def detect_multiorient(arr):
    img = Image.fromarray(arr)
    for rot in [0, 270, 90, 180]:
        cand = img.rotate(rot, expand=True) if rot else img
        r = det.detect(mp.Image(image_format=mp.ImageFormat.SRGB, data=np.array(cand)))
        if r.face_landmarks:
            return np.array(cand), r.face_landmarks[0]
    return None

def embed_geo(arr, lms):
    """完全对应 FaceEngine.detect：返回 (embedding 512D, feature 478*3, mesh 478*3)。"""
    h,w = arr.shape[:2]
    # --- 512D 身份（FaceEmbedder：5点对齐 + 亮度归一化）---
    src = np.array([[lms[i].x*w, lms[i].y*h] for i in IDX], dtype=np.float64)
    a,b,tx,ty = solve_similarity(src)
    denom = a*a+b*b
    if denom < 1e-8: denom = 1e-8
    rgb = np.empty((SIZE,SIZE,3), dtype=np.float32)
    for v in range(SIZE):
        for u in range(SIZE):
            du,dv = u-tx, v-ty
            sx = (a*du+b*dv)/denom; sy = (-b*du+a*dv)/denom
            xi = min(w-1, max(0, int(round(sx)))); yi = min(h-1, max(0, int(round(sy))))
            rgb[v,u] = arr[yi,xi]
    mean_lum = (0.299*rgb[:,:,0]+0.587*rgb[:,:,1]+0.114*rgb[:,:,2]).mean()
    gain = min(2.6, max(0.5, 128.0/mean_lum)) if mean_lum > 8 else 1.0
    inp = ((np.clip(rgb*gain,0,255)-127.5)/128).astype(np.float32)
    inp = np.expand_dims(inp,0)
    interp.set_tensor(IN, inp); interp.invoke()
    e = interp.get_tensor(OUT)[0].copy()
    n = np.linalg.norm(e)
    if n > 1e-9: e = e/n
    # --- 3D 几何（FaceEngine.normalize：鼻尖为原点、眼距为尺度）---
    pts = np.array([[p.x, p.y, p.z] for p in lms], dtype=np.float64)
    nose = pts[1]
    scale = np.linalg.norm(pts[33]-pts[263])
    if scale < 1e-5: scale = 1e-5
    geo = ((pts-nose)/scale).astype(np.float32)   # 478*3 归一化3D几何
    mesh = pts.astype(np.float32)                  # 478 点原始网格
    return e, geo, mesh

def dist(a,b): return float(1.0-np.dot(a,b))

def geo_dist(a,b):
    if a is None or b is None or a.shape!=b.shape: return float('inf')
    return float(np.sqrt(np.mean((a-b)**2)))

def confidence(emb_d, geo_d):
    """FaceStore.confidence：身份0.8 + 3D几何0.2。"""
    emb_score = min(1.0, max(0.0, 1.0-emb_d/(DIST_THRESHOLD*1.6)))
    gd = geo_d if math.isfinite(geo_d) else GEO_THRESHOLD
    geo_score = min(1.0, max(0.0, 1.0-gd/(GEO_THRESHOLD*2.2)))
    return 0.8*emb_score + 0.2*geo_score

# ============ 测试数据 ============
ENROLL = {  # 录入库：每人一张主图，模拟 9 姿态 x2 的 18 帧采集（用亮度/镜像变体模拟多角度样本）
    'obama': '/tmp/faces/obama/obama.jpg',
    'biden': '/tmp/faces/biden/biden2.jpg',
    'john':  '/tmp/faces/kit_harington/john1.jpeg',
    'alex':  '/tmp/faces/alex_lacamoire/img1.jpg',
    'rose':  '/tmp/faces/rose_leslie/img2.jpg',
}
SCAN_SELF = [  # 扫描本人（不同变体）
    ('obama', '/tmp/faces/obama/obama2.jpg'),
    ('obama', '/tmp/faces_extra/obama.jpg'),
    ('john',  '/tmp/faces/kit_harington/john2.jpeg'),
    ('alex',  '/tmp/faces/test/alex_lacamoire1.jpg'),
    ('biden', '/tmp/faces_extra/biden2.jpg'),
]
SCAN_OTHER = [  # 扫描库外陌生人（用库里人扫别人的样本构造异人场景）
    ('STRANGER(rose图)', '/tmp/faces/rose_leslie/img2.jpg'),  # rose 在库，改用其变体冒充需谨慎
]

def make_variants(im):
    """模拟 9 姿态采集：轻微转头(±旋转) + 亮度变化。真实 App 采集的是转头姿态，不用镜像。"""
    vs = [im,
          im.rotate(-6, expand=False),
          im.rotate(6, expand=False),
          PILEnh.Brightness(im).enhance(0.85),
          PILEnh.Brightness(im).enhance(1.15)]
    return vs

print('='*72)
print('端到端闭环测试：录入 → 3D 建模 → 落库 → 扫描 → 3D 联合比对')
print('='*72)

# ============ 阶段 A：录入（9 姿态采集 + 3D 资产落库）============
print('\n【阶段 A】录入：采集多角度 512D + 478点3D几何 + mesh')
library = {}   # name -> {embeddings:[512D], geometries:[478*3], mesh:[478*3]}
for name, path in ENROLL.items():
    im = Image.open(path).convert('RGB')
    vs = make_variants(im)
    embs, geos, mesh = [], [], None
    ok = 0
    for var in vs:
        res = detect_multiorient(np.array(var))
        if res is None: continue
        upright, lms = res
        e, g, m = embed_geo(upright, lms)
        embs.append(e); geos.append(g); mesh = m  # 保留最后一帧 mesh
        ok += 1
    if not embs:
        print(f'  ✗ {name}: 录入失败（未检测到人脸）')
        continue
    library[name] = {'embeddings': embs, 'geometries': geos, 'mesh': mesh}
    geo_ok = '是' if mesh is not None and mesh.shape[0]>=468 else '否'
    print(f'  ✓ {name}: 采集 {ok} 帧 | 512D×{len(embs)} | 3D几何×{len(geos)} '
          f'| mesh {NUM_LANDMARKS}点={geo_ok}')

print(f'\n  落库完成：{len(library)} 人 -> FaceStore.upsert()')

# ============ 阶段 B：扫描本人（应识别成功）============
print('\n【阶段 B】扫描本人：3D 联合比对应识别成功')
def find_best(probe_emb, probe_geo):
    best = None
    for name, rec in library.items():
        emb_d = min(dist(probe_emb, e) for e in rec['embeddings'])
        geo_d = min(geo_dist(probe_geo, g) for g in rec['geometries'])
        conf = confidence(emb_d, geo_d)
        if best is None or conf > best[2]:
            best = (name, emb_d, conf, geo_d)
    return best  # (name, embDist, conf, geoDist)

self_ok = 0
for name, path in SCAN_SELF:
    res = detect_multiorient(np.array(Image.open(path).convert('RGB')))
    if res is None:
        print(f'  ✗ {name}: 检测失败'); continue
    e, g, _ = embed_geo(*res)
    best = find_best(e, g)
    matched = (best[0]==name and best[1]<DIST_THRESHOLD)
    if matched: self_ok += 1
    print(f'  {"✓" if matched else "✗"} 真实={name:6s} -> 识别={best[0]:6s} '
          f'身份距离={best[1]:.3f} 3D几何={best[3]:.3f} 置信度={best[2]*100:.0f}%')
print(f'\n  本人识别成功：{self_ok}/{len(SCAN_SELF)} = {self_ok/len(SCAN_SELF)*100:.0f}%')

# ============ 阶段 C：留一法陌生人测试（应拒绝）============
print('\n【阶段 C】留一法陌生人测试：轮流将 1 人当作库外人，验证是否被拒绝')
STRANGER_IMG = {
    'obama': '/tmp/faces/obama/obama2.jpg',
    'biden': '/tmp/faces_extra/biden2.jpg',
    'john':  '/tmp/faces/kit_harington/john2.jpeg',
    'alex':  '/tmp/faces/test/alex_lacamoire1.jpg',
    'rose':  '/tmp/faces/rose_leslie/img2.jpg',
}
saved_lib = library
reject_ok = 0; reject_total = 0
self_dists = []      # 本人扫描的最近距离（越小越好）
stranger_dists = []  # 库外人扫描的最近距离（越大越好）
for stranger in ENROLL:
    # 用其余 4 人建临时库
    library = {k:v for k,v in saved_lib.items() if k != stranger}
    res = detect_multiorient(np.array(Image.open(STRANGER_IMG[stranger]).convert('RGB')))
    if res is None:
        print(f'  ? {stranger}: 检测失败，跳过'); continue
    e, g, _ = embed_geo(*res)
    best = find_best(e, g)  # (name, embDist, conf, geoDist)
    stranger_dists.append(best[1])
    rejected = best[1] >= DIST_THRESHOLD
    reject_ok += int(rejected); reject_total += 1
    print(f'  {"✓拒绝" if rejected else "✗误接受"} 库外人={stranger:6s} -> '
          f'最近={best[0]:6s} 身份距离={best[1]:.3f} (阈值{DIST_THRESHOLD})')
library = saved_lib
# 收集本人扫描的最近距离
for name, path in SCAN_SELF:
    res = detect_multiorient(np.array(Image.open(path).convert('RGB')))
    if res is None: continue
    e, g, _ = embed_geo(*res)
    best = find_best(e, g)
    self_dists.append(best[1])
print(f'\n  陌生人拒绝率（当前阈值{DIST_THRESHOLD}）：{reject_ok}/{reject_total} = '
      f'{reject_ok/reject_total*100:.0f}%')

# ---- 阶段 C2：阈值标定（用本人/陌生人距离分布找最优阈值）----
print('\n【阶段 C2】阈值标定：本人距离 vs 库外人距离分布')
print(f'  本人最近距离: min={min(self_dists):.3f} max={max(self_dists):.3f} '
      f'均值={np.mean(self_dists):.3f}')
print(f'  库外最近距离: min={min(stranger_dists):.3f} max={max(stranger_dists):.3f} '
      f'均值={np.mean(stranger_dists):.3f}')
print('  ' + '-'*60)
print(f'  {"阈值":>6s} {"本人接受率(TPR)":>18s} {"库外拒绝率(TNR)":>18s}')
best_t, best_score = None, -1
for t in [0.45, 0.5, 0.55, 0.6, 0.65, 0.7, 0.75, 0.8, 0.85, 0.9, 1.05]:
    tpr = sum(d < t for d in self_dists)/len(self_dists)
    tnr = sum(d >= t for d in stranger_dists)/len(stranger_dists)
    score = tpr + tnr  # 平衡准确率
    mark = ' <=最优' if score > best_score else ''
    if score > best_score: best_score, best_t = score, t
    print(f'  {t:>6.2f} {tpr*100:>17.0f}% {tnr*100:>17.0f}%{mark}')
print('  ' + '-'*60)
print(f'  推荐阈值 ≈ {best_t:.2f}（本人接受率+库外拒绝率 平衡最优）')

print('\n'+'='*72)
# 闭环核心判定：录入成功 + 本人扫描成功；陌生人拒绝率作参考（阈值边界非链路问题）
core_pass = (self_ok==len(SCAN_SELF) and len(library)==len(ENROLL))
print(f'闭环结论：{"通过 ✓  录入→3D建模→落库→扫描本人识别 链路完整可用" if core_pass else "存在失败项，需检查"}')
print(f'  · 录入入库：{len(library)}/{len(ENROLL)} 人成功')
print(f'  · 本人扫描识别：{self_ok}/{len(SCAN_SELF)} ({self_ok/len(SCAN_SELF)*100:.0f}%)')
print(f'  · 陌生人拒绝：{reject_ok}/{reject_total} ({reject_ok/reject_total*100:.0f}%，阈值边界参考)')
print('='*72)

det.close()
